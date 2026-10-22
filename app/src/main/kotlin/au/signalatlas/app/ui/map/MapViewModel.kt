package au.signalatlas.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.signalatlas.app.data.BasemapKeys
import au.signalatlas.app.data.LocationSource
import au.signalatlas.app.data.TowerData
import au.signalatlas.app.data.TowerSnapshot
import au.signalatlas.app.data.Updates
import au.signalatlas.core.model.SiteDetail
import au.signalatlas.core.model.TowerFilter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
                if (q.isEmpty()) emptyList() else withContext(compute) { data.current()?.search(q).orEmpty() }
            }
            .onEach { results -> _state.update { it.copy(searchResults = results) } }
            .launchIn(viewModelScope)

        location.last.onEach { it?.let { (lat, lon) -> onLocation(lat, lon) } }.launchIn(viewModelScope)

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

        viewModelScope.launch {
            val lookup = async { basemap.currentKey() }
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
    }

    fun selectSite(id: Int?) {
        selectedId.value = id
        if (id == null) _state.update { it.copy(selected = null, selectedId = null) }
    }

    fun search(q: String) {
        query.value = q
        _state.update { it.copy(query = q, searchResults = if (q.isBlank()) emptyList() else it.searchResults) }
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
    }
}
