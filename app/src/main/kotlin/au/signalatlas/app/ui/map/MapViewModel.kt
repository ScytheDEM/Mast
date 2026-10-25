package au.signalatlas.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.signalatlas.app.data.BasemapKeys
import au.signalatlas.app.data.Coverage
import au.signalatlas.app.data.LocationSource
import au.signalatlas.app.data.PatternAccess
import au.signalatlas.app.data.TowerData
import au.signalatlas.app.data.TowerSnapshot
import au.signalatlas.app.data.Updates
import au.signalatlas.core.coverage.Accuracy
import au.signalatlas.core.coverage.CoverageSample
import au.signalatlas.core.coverage.CoverageSelection
import au.signalatlas.core.coverage.FetchProgress
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.SiteDetail
import au.signalatlas.core.model.Tech
import au.signalatlas.core.model.TowerFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * State for the map, its sheets and the first-run and updates screens.
 *
 * Every DB query re-reads [TowerData.current] and keeps no snapshot beyond that single query, so
 * a reload swapping the DB is always picked up. Queries run on [compute] (in-memory scans and
 * sorts) or [io] (SQLite); nothing heavy runs on the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MapViewModel(
    private val data: TowerData,
    private val updates: Updates,
    basemap: BasemapKeys,
    location: LocationSource,
    private val coverage: Coverage,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** The installed antenna patterns, for the open site's radiation plot (none = typical patterns only). */
    private val patterns: PatternAccess = PatternAccess.None,
) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private val filter = MutableStateFlow(TowerFilter())
    private val selectedId = MutableStateFlow<Int?>(null)
    private val query = MutableStateFlow("")
    private val granted = MutableStateFlow(false)
    private val position = MutableStateFlow<Pair<Double, Double>?>(null)
    private var checkJob: Job? = null
    private var readoutJob: Job? = null
    private var increaseJob: Job? = null
    private val visibleTiles = MutableStateFlow<Set<Triple<Int, Int, Int>>>(emptySet())
    /** False until the user picks a network, so the default keeps following the filter chips. */
    private var networkChosen = false

    init {
        // The service outlives this view model; a fresh one starts with coverage off, so the overlay must be too.
        coverage.selection = null
        combine(data.started, data.changes) { started, snap -> started to snap }
            .onEach { (started, snap) ->
                _state.update {
                    it.copy(
                        loading = !started && snap == null,
                        hasData = snap != null,
                        availableBands = snap?.allBands.orEmpty(),
                        data = snap?.summary(),
                    )
                }
            }
            .launchIn(viewModelScope)

        // The full filtered list feeds the map's clustering; rebuilt only when the DB or filter changes.
        combine(data.changes, filter) { snap, f -> snap to f }
            .distinctUntilChanged()
            .mapLatest { (snap, f) -> if (snap == null) emptyList() else withContext(compute) { snap.allSites(f) } }
            .onEach { sites -> _state.update { it.copy(sites = sites) } }
            .launchIn(viewModelScope)

        combine(data.changes, selectedId) { _, id -> id }
            .mapLatest { id ->
                val detail = id?.let { withContext(io) { loadDetail(it) } }
                Triple(id, detail, detail?.let { withContext(io) { sectorPlots(it, patterns) } }.orEmpty())
            }
            .onEach { (id, detail, plots) ->
                // A site that no longer exists (e.g. after an update) closes the sheet.
                if (id != null && detail == null) selectedId.value = null
                _state.update { it.copy(selected = detail, selectedId = if (detail == null) null else id, selectedPlots = plots) }
            }
            .launchIn(viewModelScope)

        combine(query.debounce(SEARCH_DEBOUNCE_MS), data.changes) { q, _ -> q.trim() }
            .mapLatest { q ->
                q to if (q.isEmpty()) emptyList() else withContext(compute) { data.current()?.search(q).orEmpty() }
            }
            .onEach { (q, results) -> _state.update { it.copy(search = it.search.copy(results = results, resultsFor = q)) } }
            .launchIn(viewModelScope)

        location.last.onEach { it?.let { (lat, lon) -> onLocation(lat, lon) } }.launchIn(viewModelScope)
        location.enabled.onEach { on -> _state.update { it.copy(locationEnabled = on) } }.launchIn(viewModelScope)
        location.estimated.onEach { on -> _state.update { it.copy(locationEstimated = on) } }.launchIn(viewModelScope)

        combine(data.changes, filter, granted, position) { snap, f, ok, pos -> NearbyQuery(snap, f, ok, pos) }
            .mapLatest { q ->
                val pos = q.position
                if (!q.granted || pos == null || q.snap == null) {
                    emptyList()
                } else {
                    withContext(compute) {
                        data.current()?.nearest(pos.first, pos.second, q.filter, NEARBY_LIMIT).orEmpty().sortedBy { it.second }
                    }
                }
            }
            .onEach { nearby -> _state.update { it.copy(nearby = nearby) } }
            .launchIn(viewModelScope)

        updates.status.onEach { s -> _state.update { it.copy(update = s) } }.launchIn(viewModelScope)
        updates.patternStatus.onEach { p -> _state.update { it.copy(patterns = p) } }.launchIn(viewModelScope)
        coverage.urlTemplate.onEach { t -> _state.update { it.copy(coverage = it.coverage.copy(urlTemplate = t)) } }
            .launchIn(viewModelScope)

        // The accuracy chip: follows the tiles in view and re-reads when terrain data changes or coverage is toggled.
        combine(visibleTiles, coverage.terrainEpoch, coverage.urlTemplate) { tiles, _, template -> tiles.takeIf { template != null } }
            .flatMapLatest { tiles -> if (tiles == null) flowOf(Accuracy.QUICK) else coverage.accuracyFor(tiles) }
            .distinctUntilChanged()
            .onEach { a -> _state.update { it.copy(coverage = it.coverage.copy(accuracy = a)) } }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            // A failed lookup (e.g. corrupt DataStore) must not crash start-up: no key, fallback style.
            val lookup = async {
                try {
                    basemap.currentKey()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            // Show the map after at most BASEMAP_WAIT_MS; a later key costs one style reload.
            val inTime = withTimeoutOrNull(BASEMAP_WAIT_MS) { Resolved(lookup.await()) }
            _state.update { it.copy(basemapKey = inTime?.key, basemapResolved = true) }
            if (inTime == null) {
                val key = lookup.await()
                _state.update { it.copy(basemapKey = key) }
            }
        }
    }

    fun setFilter(f: TowerFilter) {
        val bandsChanged = filter.value.bands != f.bands
        filter.value = f
        _state.update { it.copy(filter = f) }
        if (_state.value.coverage.on && bandsChanged) { // the band filter narrows the overlay too
            dismissReadout()
            applyCoverage()
        }
    }

    /**
     * Switches the coverage overlay. Turning it on picks the first network selected in the filter chips (Telstra if
     * all are), unless the user already chose one.
     */
    fun setCoverageOn(on: Boolean) {
        if (on == _state.value.coverage.on) return
        updateCoverage { c ->
            c.copy(on = on, network = if (on && !networkChosen) defaultNetwork(filter.value) else c.network)
        }
    }

    /**
     * "Show predicted coverage for {network}" on a site: turns the overlay on for that network (and keeps it as the
     * user's choice).
     */
    fun showCoverageFor(n: Network) {
        networkChosen = true
        _state.update { it.copy(section = MapSection.COVERAGE) }
        updateCoverage { it.copy(on = true, network = n) }
    }

    /**
     * The navigation bar: Coverage turns the layer on (with its controls), any other section turns it off. The selected
     * site stays selected across sections.
     */
    fun selectSection(section: MapSection) {
        if (section == _state.value.section) return
        _state.update { it.copy(section = section) }
        setCoverageOn(section == MapSection.COVERAGE)
    }

    fun setCoverageNetwork(n: Network) {
        networkChosen = true
        updateCoverage { it.copy(network = n) }
    }

    fun setCoverageFiveGOnly(fiveGOnly: Boolean) = updateCoverage { it.copy(fiveGOnly = fiveGOnly) }

    fun setCoverageIndoor(indoor: Boolean) = updateCoverage { it.copy(indoor = indoor) }

    /** Signal or Reliability: the same cached tiles, drawn through the other colour ramp. */
    fun setCoverageView(view: CoverageSelection.View) = updateCoverage { it.copy(view = view) }

    /** The map's coverage source has (true) or has no (false) tiles outstanding; drives the "Calculating coverage…" pill. */
    fun onCoverageLoading(loading: Boolean) = _state.update { it.copy(coverage = it.coverage.copy(calculating = loading)) }

    /** The settled map zoom, for the "Zoom in" chip. */
    fun onZoom(zoom: Double) = _state.update { it.copy(zoom = zoom) }

    /** The tiles the viewport needs; ignored while coverage is off. */
    fun onVisibleTiles(tiles: Set<Triple<Int, Int, Int>>) {
        visibleTiles.value = tiles
        coverage.setVisible(tiles)
    }

    /** The chip was tapped while the view is not fully terrain: opens the sheet and asks how much there is to download. */
    fun openIncreaseResolution() {
        if (_state.value.coverage.accuracy == Accuracy.TERRAIN) return
        increaseJob?.cancel()
        setIncrease(IncreaseUiState())
        increaseJob = viewModelScope.launch {
            val bytes = withContext(io) { coverage.estimateIncreaseBytes() }
            setIncrease(
                when {
                    bytes != null -> IncreaseUiState(IncreaseStatus.READY, bytes)
                    !withContext(io) { coverage.isOnline() } -> IncreaseUiState(IncreaseStatus.OFFLINE)
                    else -> IncreaseUiState(IncreaseStatus.ZOOM_IN)
                },
            )
        }
    }

    /** The sheet's Download button: fetches on an unmetered network, otherwise asks to use mobile data first. */
    fun downloadIncrease() = fetchIncrease(allowMetered = false)

    /** "Use mobile data for about N MB?" was accepted. */
    fun confirmIncreaseOnMobileData() = fetchIncrease(allowMetered = true)

    fun dismissIncreaseResolution() {
        increaseJob?.cancel()
        setIncrease(null)
    }

    private fun setIncrease(s: IncreaseUiState?) = _state.update { it.copy(coverage = it.coverage.copy(increase = s)) }

    private fun fetchIncrease(allowMetered: Boolean) {
        val before = _state.value.coverage.increase ?: return
        if (before.status == IncreaseStatus.FETCHING || before.status == IncreaseStatus.LOADING) return
        increaseJob?.cancel()
        var bytes = before.bytes
        setIncrease(IncreaseUiState(IncreaseStatus.FETCHING, bytes))
        increaseJob = viewModelScope.launch {
            coverage.increaseResolution(allowMetered).collect { p ->
                when (p) {
                    is FetchProgress.Estimating -> {
                        bytes = p.bytes
                        setIncrease(IncreaseUiState(IncreaseStatus.FETCHING, bytes))
                    }
                    is FetchProgress.Fetching -> setIncrease(IncreaseUiState(IncreaseStatus.FETCHING, bytes, p.fraction))
                    FetchProgress.Done -> setIncrease(null) // the chip follows when the tiles refresh
                    is FetchProgress.Failed -> setIncrease(
                        IncreaseUiState(
                            when (p.reason) {
                                "metered" -> IncreaseStatus.CONFIRM_METERED
                                "offline" -> IncreaseStatus.OFFLINE
                                "no view", "zoom in" -> IncreaseStatus.ZOOM_IN
                                "storage" -> IncreaseStatus.STORAGE_FULL
                                else -> IncreaseStatus.ERROR
                            },
                            bytes,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Long-press at a point: shows "Calculating…" and asks for the prediction every [READOUT_RETRY_MS] until a tile
     * is there or [READOUT_TIMEOUT_MS] has passed.
     */
    fun longPress(lat: Double, lon: Double) {
        val c = _state.value.coverage
        if (!c.on || c.needsZoom(_state.value.zoom)) return // the zoom chip already says why there is nothing
        readoutJob?.cancel()
        setReadout(Readout(lat, lon, ReadoutResult.Calculating))
        readoutJob = viewModelScope.launch {
            var waited = 0L
            while (true) {
                val sample = withContext(compute) { coverage.valueAt(lat, lon) }
                if (sample != null) {
                    setReadout(Readout(lat, lon, sample.toResult()))
                    return@launch
                }
                if (waited >= READOUT_TIMEOUT_MS) break
                delay(READOUT_RETRY_MS)
                waited += READOUT_RETRY_MS
            }
            setReadout(Readout(lat, lon, ReadoutResult.NoPrediction))
        }
    }

    fun dismissReadout() {
        readoutJob?.cancel()
        setReadout(null)
    }

    private fun setReadout(r: Readout?) = _state.update { it.copy(coverage = it.coverage.copy(readout = r)) }

    private fun updateCoverage(change: (CoverageUiState) -> CoverageUiState) {
        val before = _state.value.coverage
        _state.update { it.copy(coverage = change(it.coverage)) }
        val after = _state.value.coverage
        // A view switch keeps the point's numbers; anything else changes the tiles they came from.
        if (before.copy(view = after.view, urlTemplate = after.urlTemplate) != after) dismissReadout()
        if (!after.on && after.increase != null) dismissIncreaseResolution()
        if (!after.on && after.calculating) _state.update { it.copy(coverage = it.coverage.copy(calculating = false)) }
        applyCoverage()
    }

    private fun applyCoverage() {
        val c = _state.value.coverage
        coverage.selection = if (!c.on) null else CoverageSelection(
            network = c.network,
            techs = if (c.fiveGOnly) setOf(Tech.NR) else setOf(Tech.LTE, Tech.NR),
            bands = filter.value.bands,
            indoor = c.indoor,
            view = c.view,
        )
    }

    private fun defaultNetwork(f: TowerFilter): Network =
        COVERAGE_NETWORKS.firstOrNull { it in f.networks && f.networks.size < Network.entries.size } ?: Network.TELSTRA

    private fun CoverageSample.toResult(): ReadoutResult {
        val rsrp = rsrpDbm?.roundToInt() ?: return ReadoutResult.NoCoverage
        val quality = SignalQuality.of(rsrp) ?: return ReadoutResult.NoCoverage
        return ReadoutResult.Predicted(
            rsrpDbm = rsrp,
            quality = quality,
            reliablePercent = probability?.let { (it * 100).roundToInt().coerceIn(0, 100) },
            best = best?.let { BestSector(it.network, it.bandLabel, it.distanceM) },
        )
    }

    /** Tapping the map elsewhere closes the readout. */
    fun onMapTap() {
        if (_state.value.coverage.readout != null) dismissReadout()
    }

    fun downloadPatternsNow() = updates.downloadPatternsNow()

    /**
     * A tower tapped on the map: tapping the selected tower again deselects it (its lobes go), any other tower becomes the
     * selection. Collapsing or swiping the sheet down never deselects.
     */
    fun onSiteTapped(id: Int) = selectSite(if (id == selectedId.value) null else id)

    fun selectSite(id: Int?) {
        selectedId.value = id
        if (id == null) _state.update { it.copy(selected = null, selectedId = null, selectedPlots = emptyList()) }
    }

    /** The search field's text changed; results follow after [SEARCH_DEBOUNCE_MS], tagged with their query. */
    fun search(q: String) {
        query.value = q
        _state.update {
            val blank = q.isBlank()
            it.copy(search = it.search.copy(query = q, results = if (blank) emptyList() else it.search.results, resultsFor = if (blank) "" else it.search.resultsFor))
        }
    }

    fun onLocation(lat: Double, lon: Double) {
        position.value = lat to lon
        _state.update { it.copy(location = lat to lon) }
    }

    fun onLocationPermission(granted: Boolean) {
        this.granted.value = granted
        _state.update { it.copy(locationGranted = granted) }
    }

    /** Asks ACMA whether newer data exists; a check already in flight is not repeated. */
    fun checkUpdates() {
        if (checkJob?.isActive == true) return
        checkJob = viewModelScope.launch { updates.check() }
    }

    fun updateNow(allowMetered: Boolean) = updates.updateNow(allowMetered)

    fun cancelUpdate() = updates.cancel()

    /** A reload may retire the store mid-query; then the current one is asked once more. */
    private fun loadDetail(id: Int): SiteDetail? =
        data.current()?.siteDetail(id) ?: data.current()?.siteDetail(id)

    private fun TowerSnapshot.summary() = DataSummary(
        extractStamp = meta.extractStamp,
        importedAt = meta.importedAt,
        sites = meta.report.sites,
        sectors = meta.report.sectors,
        carriers = meta.report.carriers,
    )

    private data class NearbyQuery(
        val snap: TowerSnapshot?,
        val filter: TowerFilter,
        val granted: Boolean,
        val position: Pair<Double, Double>?,
    )

    private class Resolved(val key: String?)

    companion object {
        const val SEARCH_DEBOUNCE_MS = 150L
        const val BASEMAP_WAIT_MS = 1_500L
        const val NEARBY_LIMIT = 50
        const val READOUT_RETRY_MS = 500L
        const val READOUT_TIMEOUT_MS = 10_000L
        /** Coverage network choices, in display order (the Optus–TPG JV is part of Optus and TPG). */
        val COVERAGE_NETWORKS = listOf(Network.TELSTRA, Network.OPTUS, Network.TPG, Network.NBN)
    }
}
