package au.signalatlas.app.data

import au.signalatlas.core.coverage.Accuracy
import au.signalatlas.core.coverage.CoverageSample
import au.signalatlas.core.coverage.CoverageSelection
import au.signalatlas.core.coverage.CoverageTileService
import au.signalatlas.core.coverage.FetchProgress
import au.signalatlas.core.data.DataMeta
import au.signalatlas.core.data.PatternRepository
import au.signalatlas.core.data.TowerRepository
import au.signalatlas.core.data.TowerStore
import au.signalatlas.core.map.BasemapLocator
import au.signalatlas.core.model.SiteDetail
import au.signalatlas.core.model.SiteSummary
import au.signalatlas.core.model.TowerFilter
import au.signalatlas.core.update.HrpStatus
import au.signalatlas.core.update.UpdateManager
import au.signalatlas.core.update.UpdateStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/*
 * The seams MapViewModel depends on. Production adapters wrap the core classes (which need a real
 * DB, WorkManager or network); tests supply fakes.
 */

/** Read access to one loaded tower DB. */
interface TowerSnapshot {
    val meta: DataMeta
    val allBands: List<String>
    fun allSites(f: TowerFilter): List<SiteSummary>
    fun nearest(lat: Double, lon: Double, f: TowerFilter, limit: Int = 50): List<Pair<SiteSummary, Double>>
    fun search(query: String, limit: Int = 20): List<SiteSummary>
    /** Null if the site does not exist or this snapshot was retired by a reload. */
    fun siteDetail(siteId: Int): SiteDetail?
}

/** The live tower data, which a reload can swap at any time. */
interface TowerData {
    /** False until the repository has opened (or found no) DB at startup. */
    val started: StateFlow<Boolean>
    /** Emits whenever the live DB changes (null = none installed). Use only as a trigger. */
    val changes: Flow<TowerSnapshot?>
    /** The live DB right now; re-read for every query instead of keeping a snapshot. */
    fun current(): TowerSnapshot?
}

interface Updates {
    val status: StateFlow<UpdateStatus>
    suspend fun check()
    fun updateNow(allowMetered: Boolean)
    /** Cancels a queued or running manual update. */
    fun cancel()
    /** The antenna-pattern (`patterns.db`) import: when it last ran and whether one is running. */
    val patternStatus: StateFlow<HrpStatus>
    fun downloadPatternsNow()
}

/** The coverage overlay as the map screen drives it. */
interface Coverage {
    /** What the overlay shows; null = off. Setting it is cheap (no tower query). */
    var selection: CoverageSelection?
    /** Tile URL template for the current selection and data versions; null when off or without data. */
    val urlTemplate: Flow<String?>
    /** The tiles the viewport needs (main-thread cheap). */
    fun setVisible(tiles: Set<Triple<Int, Int, Int>>)
    /** The prediction at a point from tiles already rendered, or null while there are none. */
    fun valueAt(lat: Double, lon: Double): CoverageSample?

    /** Changes whenever terrain data changes. The URL template follows it, so the map swaps tiles in place. */
    val terrainEpoch: StateFlow<Long>
    /** How the tiles of [visible] were predicted; follows tiles as they render. Hold the flow while it is shown. */
    fun accuracyFor(visible: Set<Triple<Int, Int, Int>>): StateFlow<Accuracy>
    /** True while the device is online (terrain can be downloaded). */
    fun isOnline(): Boolean
    /** Bytes "Increase resolution" would download for the view; null while offline, without a view or zoomed out. */
    suspend fun estimateIncreaseBytes(): Long?
    /** Downloads the elevation the current view is missing. */
    fun increaseResolution(allowMetered: Boolean): Flow<FetchProgress>
}

fun interface BasemapKeys {
    suspend fun currentKey(): String?
}

/** The device's last known position as (lat, lon). */
interface LocationSource {
    val last: StateFlow<Pair<Double, Double>?>
    /** False while the device's location services are switched off. */
    val enabled: StateFlow<Boolean>
}

/** Equality is the wrapped store's identity, so a reload is always a change. */
data class StoreSnapshot(private val store: TowerStore) : TowerSnapshot {
    override val meta: DataMeta get() = store.meta
    override val allBands: List<String> get() = store.allBands
    override fun allSites(f: TowerFilter) = store.allSites(f)
    override fun nearest(lat: Double, lon: Double, f: TowerFilter, limit: Int) = store.nearest(lat, lon, f, limit)
    override fun search(query: String, limit: Int) = store.search(query, limit)
    override fun siteDetail(siteId: Int) = store.siteDetail(siteId)
}

class RepositoryTowerData(private val repo: TowerRepository, override val started: StateFlow<Boolean>) : TowerData {
    override val changes: Flow<TowerSnapshot?> = repo.store.map { it?.let(::StoreSnapshot) }
    override fun current(): TowerSnapshot? = repo.store.value?.let(::StoreSnapshot)
}

class ManagerUpdates(private val manager: UpdateManager) : Updates {
    override val status: StateFlow<UpdateStatus> get() = manager.status
    override suspend fun check() = manager.checkOnAppOpen()
    override fun updateNow(allowMetered: Boolean) = manager.updateNow(allowMetered)
    override fun cancel() = manager.cancelManual()
    override val patternStatus: StateFlow<HrpStatus> get() = manager.hrpStatus
    override fun downloadPatternsNow() = manager.downloadPatternsNow()
}

/**
 * The tile service as a [Coverage]. The template depends on the selection, on the tower and pattern data versions and on
 * the terrain epoch, so it is re-read whenever any of them changes.
 */
class ServiceCoverage(
    private val service: CoverageTileService,
    towers: TowerRepository,
    patterns: PatternRepository,
) : Coverage {
    override var selection: CoverageSelection?
        get() = service.selection.value
        set(value) { service.selection.value = value }

    override val urlTemplate: Flow<String?> =
        combine(service.selection, towers.store, patterns.patterns, service.terrainEpoch) { _, _, _, _ -> service.tileUrlTemplate() }
            .distinctUntilChanged()

    override fun setVisible(tiles: Set<Triple<Int, Int, Int>>) = service.setVisible(tiles)
    override fun valueAt(lat: Double, lon: Double) = service.valueAt(lat, lon)
    override val terrainEpoch: StateFlow<Long> get() = service.terrainEpoch
    override fun accuracyFor(visible: Set<Triple<Int, Int, Int>>) = service.accuracyFor(visible)
    override fun isOnline() = service.isOnline()
    override suspend fun estimateIncreaseBytes() = service.estimateIncreaseBytes()
    override fun increaseResolution(allowMetered: Boolean) = service.increaseResolution(allowMetered)
}

class LocatorBasemapKeys(private val locator: BasemapLocator) : BasemapKeys {
    override suspend fun currentKey(): String? = locator.currentKey()
}
