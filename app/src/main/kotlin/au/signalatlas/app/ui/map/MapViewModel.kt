package au.signalatlas.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.signalatlas.app.data.BasemapKeys
import au.signalatlas.app.data.Coverage
import au.signalatlas.app.data.LocationSource
import au.signalatlas.app.data.TowerData
import au.signalatlas.app.data.TowerSnapshot
import au.signalatlas.app.data.Updates
import au.signalatlas.core.coverage.CoverageSample
import au.signalatlas.core.coverage.CoverageSelection
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
    /** False until the user picks a network, so the default keeps following the filter chips. */
    private var networkChosen = false

    init {
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
            .mapLatest { id -> id to id?.let { withContext(io) { loadDetail(it) } } }
            .onEach { (id, detail) ->
                // A site that no longer exists (e.g. after an update) closes the sheet.
                if (id != null && detail == null) selectedId.value = null
                _state.update { it.copy(selected = detail, selectedId = if (detail == null) null else id) }
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
        filter.value = f
        _state.update { it.copy(filter = f) }
        if (_state.value.coverage.on) applyCoverage() // the band filter narrows the overlay too
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

    fun setCoverageNetwork(n: Network) {
        networkChosen = true
        updateCoverage { it.copy(network = n) }
    }

    fun setCoverageFiveGOnly(fiveGOnly: Boolean) = updateCoverage { it.copy(fiveGOnly = fiveGOnly) }

    fun setCoverageIndoor(indoor: Boolean) = updateCoverage { it.copy(indoor = indoor) }

    /** Signal or Reliability: the same cached tiles, drawn through the other colour ramp. */
    fun setCoverageView(view: CoverageSelection.View) = updateCoverage { it.copy(view = view) }

    /** The settled map zoom, for the "Zoom in" chip. */
    fun onZoom(zoom: Double) = _state.update { it.copy(zoom = zoom) }

    /** The tiles the viewport needs; ignored while coverage is off. */
    fun onVisibleTiles(tiles: Set<Triple<Int, Int, Int>>) = coverage.setVisible(tiles)

    /**
     * Long-press at a point: shows "Calculating…" and asks for the prediction every [READOUT_RETRY_MS] until a tile
     * is there or [READOUT_TIMEOUT_MS] has passed.
     */
    fun longPress(lat: Double, lon: Double) {
        if (!_state.value.coverage.on) return
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

    fun selectSite(id: Int?) {
        selectedId.value = id
        if (id == null) _state.update { it.copy(selected = null, selectedId = null) }
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
