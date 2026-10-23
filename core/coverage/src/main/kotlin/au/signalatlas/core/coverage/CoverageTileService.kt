package au.signalatlas.core.coverage

import au.signalatlas.core.data.CarrierBatch
import au.signalatlas.core.data.PatternStore
import au.signalatlas.core.data.TowerRepository
import au.signalatlas.core.model.Network
import au.signalatlas.engine.AreaClass
import au.signalatlas.engine.AreaClassifier
import au.signalatlas.engine.CancelToken
import au.signalatlas.engine.CoverageEngine
import au.signalatlas.engine.RenderedTile
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.future.await
import java.net.URLEncoder
import java.util.concurrent.CompletableFuture
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The request names data (or a selection) that is no longer current. */
class StaleTileException : Exception("tile URL does not match the current selection or data")

/** Coverage is switched off (no selection). */
class CoverageOffException : Exception("coverage is off")

/** The render was dropped because its tile left the viewport or its data went stale. */
class TileCancelledException : Exception("tile render cancelled")

/** The best-server carrier of a [CoverageSample]. */
data class BestServer(val network: Network, val bandLabel: String, val distanceM: Double)

/** Values read back from the cached tiles at a point; null fields where there is no coverage. */
data class CoverageSample(
    val rsrpDbm: Float?,
    val sinrDb: Float?,
    val probability: Float?,
    val best: BestServer?,
)

/**
 * Turns tile requests into engine renders, caches them and serves Terrarium PNGs.
 *
 * Requests for the same [TileKey] share one render. A render needs carriers prepared once per region (the z8 tile
 * containing the tile = 4x4 z10 tiles, expanded by 80 km) and data version; the 8 most recently used regions are
 * kept, with their [CarrierBatch] so [valueAt] can name the best server.
 *
 * With a [TerrainSupport] the service is terrain-aware (see [TerrainCoordinator]): [TileKey.terrainVersion] and the URL's
 * terrain segment ([terrainEpoch]) change as elevation chunks and terrain tables arrive, tiles carry an [Accuracy]
 * ([accuracyFor]), camera rests at z >= 10 auto-cache missing chunks on unmetered networks, and [increaseResolution]
 * downloads them on request. Without it every tile is quick-model and the version is always 0.
 */
class CoverageTileService internal constructor(
    private val towers: TowerData,
    private val patterns: StateFlow<PatternStore?>,
    private val renderer: TileRenderer,
    private val cache: TileCache,
    private val pool: RenderPool,
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
    terrainSupport: TerrainSupport? = null,
) {
    constructor(
        repo: TowerRepository, patterns: StateFlow<PatternStore?>, engine: CoverageEngine, cache: TileCache,
        pool: RenderPool, terrain: TerrainSupport? = null,
    ) : this(RepositoryTowerData(repo), patterns, EngineTileRenderer(engine), cache, pool, { Log.w(TAG, it) }, terrain)

    constructor(
        repo: TowerRepository, patterns: StateFlow<PatternStore?>, renderer: TileRenderer, cache: TileCache,
        pool: RenderPool, terrain: TerrainSupport? = null,
    ) : this(RepositoryTowerData(repo), patterns, renderer, cache, pool, { Log.w(TAG, it) }, terrain)

    /** Null = coverage off. */
    val selection: MutableStateFlow<CoverageSelection?> = MutableStateFlow(null)

    /** [prepared] is null when the batch exceeded the engine's carrier cap (it is then never cached). */
    private class Region(val key: RegionKey, val prepared: PreparedRegion?, val batch: CarrierBatch)

    /** A region prepared with the terrain tables of one [TerrainSnapshot] version. */
    private data class TerrainPrepKey(val region: RegionKey, val terrain: TerrainKey, val version: Long)

    /**
     * [level] is the fallback level used (ruling JJ): 0 = the z8 tile + 80 km, 1 = the z10 tile + 80 km,
     * 2 = level 1 culled by range (and capped). [zf]/[rx]/[ry] address the tile the bounds come from.
     */
    private data class RegionKey(
        val selectionId: String, val stamp: String, val etag: String, val level: Int, val zf: Int, val rx: Int, val ry: Int,
    )

    private val inflight = HashMap<TileKey, CompletableFuture<RenderedTile>>()
    /** Active renders' tokens, so renders whose key went stale can be cancelled. */
    private val active = java.util.concurrent.ConcurrentHashMap<TileKey, CancelToken>()

    /**
     * Region keys whose batch exceeded the engine's carrier cap (ruling JJ): the next level is used directly, without
     * repeating the oversized query. Small, bounded, and separate from the [regions] LRU (which holds only real
     * prepared regions). Guarded by `synchronized(regions)`.
     */
    private val overflowed = object : LinkedHashMap<RegionKey, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RegionKey, Boolean>) = size > MAX_OVERFLOW_HINTS
    }

    private val regions = object : LinkedHashMap<RegionKey, CompletableFuture<Region>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RegionKey, CompletableFuture<Region>>) =
            size > MAX_REGIONS
    }

    private val terrainPrepared = object : LinkedHashMap<TerrainPrepKey, CompletableFuture<PreparedRegion>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TerrainPrepKey, CompletableFuture<PreparedRegion>>) =
            size > MAX_TERRAIN_PREPARED
    }

    private class Tracker(val flow: WeakReference<MutableStateFlow<Accuracy>>, val tiles: Set<TileAddress>)
    private val trackers = CopyOnWriteArrayList<Tracker>()

    private val terrain: TerrainCoordinator? =
        terrainSupport?.let { TerrainCoordinator(it, renderer, pool, ::onTerrainChanged, warn) }

    /** Changes whenever terrain data of any region changes (chunks arrived, tables published); part of the tile URL. */
    val terrainEpoch: StateFlow<Long> = terrain?.epoch ?: MutableStateFlow(0L)

    private fun patternsEtag(): String = patterns.value?.etag ?: "none"

    private fun terrainSegment(): String = "t${terrainEpoch.value}"

    /**
     * A tile served from the cache never renders, so a terrain region recreated after an eviction would never ask for its
     * tables: a one-off low-priority job computes the demand from the region's carriers and queues the builds.
     */
    private fun ensureDemand(key: TileKey, sel: CoverageSelection) {
        val tc = terrain ?: return
        if (key.terrainVersion == 0L) return
        val tk = tc.keyFor(key.selectionId, key.towerStamp, key.patternsEtag, key.z, key.x, key.y)
        if (tk == null || !tc.claimDemand(tk)) return
        val tile = Triple(key.z, key.x, key.y)
        pool.submit(
            tile, RenderPool.PRIORITY_TABLE,
            work = { cancel ->
                try {
                    // Not isCurrent: an evicted region peeks as version 0, exactly the case to recover. The region is
                    // recreated (at most once per data key) if the LRU or a trim dropped it meanwhile.
                    val tr = if (isDataCurrent(key)) tc.regionForDemand(tk) else null
                    if (tr != null && recordDemand(tc, tr, key, region(key, sel), cancel)) tc.ensureTables(tr, tile)
                } catch (e: Exception) {
                    // Stale data or a failed region query: the next request asks again.
                } finally {
                    tc.demandDone(tk)
                }
            },
            onCancelled = { tc.demandDone(tk) },
        )
    }

    /** Memory pressure ([android.content.ComponentCallbacks2.onTrimMemory]): sheds terrain memory, see [TerrainCoordinator.trimMemory]. */
    fun onTrimMemory(level: Int) {
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        terrain?.trimMemory()
        synchronized(terrainPrepared) { terrainPrepared.clear() }
    }

    /** The terrain version a tile of this selection and data has now (0 = quick only); [create] adds its region if new. */
    private fun terrainVersion(
        selectionId: String, stamp: String, etag: String, z: Int, x: Int, y: Int, create: Boolean = false, recheck: Boolean = false,
    ): Long {
        val tc = terrain ?: return 0L
        val k = tc.keyFor(selectionId, stamp, etag, z, x, y)
        return if (create) tc.versionOf(k, recheck) else tc.peekVersion(k)
    }

    private fun onTerrainChanged() {
        cancelStale()
        refreshAccuracy()
    }

    /**
     * The URL template for the current selection and data versions, or null when coverage is off or no tower DB is
     * installed. `view` is a segment so a view switch changes the URL (MapLibre refetches) while reusing cached tiles.
     */
    fun tileUrlTemplate(): String? {
        val sel = selection.value ?: return null
        val stamp = towers.stamp() ?: return null
        return "https://$HOST/v1/${sel.cacheId()}/${sel.view.segment}/${encode(stamp)}/${encode(patternsEtag())}/${terrainSegment()}/{z}/{x}/{y}.png"
    }

    /**
     * Terrarium PNG of tile (z, x, y) for the selection, data versions and view named by [url] (the template with
     * its placeholders filled). Fails with [StaleTileException], [CoverageOffException] or [TileCancelledException].
     */
    suspend fun tilePng(z: Int, x: Int, y: Int, url: String): ByteArray {
        val parsed = url.toHttpUrlOrNull()?.let { TileUrl.parse(it) }
            ?: throw IllegalArgumentException("not a tile URL: $url")
        return tileFuture(parsed.copy(z = z, x = x, y = y)).await()
    }

    /** Blocking-friendly form of [tilePng], used by the interceptor. */
    internal fun tileFuture(tile: TileUrl): CompletableFuture<ByteArray> {
        cancelStale()
        val sel = selection.value ?: return failed(CoverageOffException())
        val stamp = towers.stamp()
        if (stamp == null || tile.selectionId != sel.cacheId() || tile.towerStamp != stamp ||
            tile.patternsEtag != patternsEtag() || tile.terrainSegment != terrainSegment()
        ) {
            return failed(StaleTileException())
        }
        val version = terrainVersion(tile.selectionId, stamp, tile.patternsEtag, tile.z, tile.x, tile.y, create = true, recheck = true)
        val key = TileKey(tile.z, tile.x, tile.y, tile.selectionId, stamp, tile.patternsEtag, version)
        ensureDemand(key, sel)
        return rawTile(key, sel).thenApply { TerrariumEncoder.encode(it.rsrp, tile.view, it.prob) }
    }

    /** The key a tile of the current selection and data has now (creating its terrain region); null when coverage is off. */
    internal fun currentKey(z: Int, x: Int, y: Int): TileKey? {
        val sel = selection.value ?: return null
        val stamp = towers.stamp() ?: return null
        val etag = patternsEtag()
        return TileKey(z, x, y, sel.cacheId(), stamp, etag, terrainVersion(sel.cacheId(), stamp, etag, z, x, y, create = true))
    }

    /** Tiles the viewport needs; other queued or running renders are cancelled. */
    fun setVisible(tiles: Set<Triple<Int, Int, Int>>) {
        cancelStale()
        val sel = selection.value
        val stamp = towers.stamp()
        // The coordinator learns the new view first, so jobs the pool cancels below are seen as dropped by a pan.
        terrain?.onVisible(tiles, if (sel == null || stamp == null) null else TerrainCoordinator.Context(sel.cacheId(), stamp, patternsEtag()))
        pool.setVisible(tiles)
    }

    /**
     * The accuracy of the tiles of [visible] (the ones at its highest zoom; lower ones are parents and prefetch):
     * [Accuracy.TERRAIN] only if every one is a terrain tile, [Accuracy.PARTIAL] if some are, else [Accuracy.QUICK]
     * (also for tiles not rendered yet, and without terrain support). Follows tiles as they render and terrain data
     * changes; hold the returned flow for as long as it is shown.
     */
    fun accuracyFor(visible: Set<Triple<Int, Int, Int>>): StateFlow<Accuracy> {
        val flow = MutableStateFlow(accuracyOf(visible))
        trackers += Tracker(WeakReference(flow), visible.toSet())
        return flow
    }

    private fun accuracyOf(visible: Set<TileAddress>): Accuracy {
        val sel = selection.value ?: return Accuracy.QUICK
        val stamp = towers.stamp() ?: return Accuracy.QUICK
        val top = visible.maxOfOrNull { it.first } ?: return Accuracy.QUICK
        val etag = patternsEtag()
        return Accuracy.summary(
            visible.filter { it.first == top }.mapNotNull { (z, x, y) ->
                val key = TileKey(z, x, y, sel.cacheId(), stamp, etag, terrainVersion(sel.cacheId(), stamp, etag, z, x, y))
                val tile = cache.getMemory(key) ?: return@mapNotNull Accuracy.QUICK
                // A tile with no coverage has no prediction to label: it neither makes a terrain view partial nor proves it.
                if (key.terrainVersion != 0L && tile.coveredPixels == 0) null else Accuracy.of(key, tile)
            },
        )
    }

    private fun refreshAccuracy() {
        for (t in trackers) {
            val flow = t.flow.get()
            if (flow == null) trackers.remove(t) else flow.value = accuracyOf(t.tiles)
        }
    }

    /** Number of terrain regions kept (test hook). */
    internal val terrainRegionCount: Int get() = terrain?.regionCount ?: 0

    /** Whether the terrain region of tile (z, x, y) for the current selection and data is kept (test hook). */
    internal fun hasTerrainRegion(z: Int, x: Int, y: Int): Boolean {
        val tc = terrain ?: return false
        val sel = selection.value ?: return false
        val stamp = towers.stamp() ?: return false
        val k = tc.keyFor(sel.cacheId(), stamp, patternsEtag(), z, x, y) ?: return false
        return tc.existingRegion(k) != null
    }

    /** True while the device has a validated network (what [estimateIncreaseBytes] needs); false without terrain support. */
    fun isOnline(): Boolean = terrain?.isOnline() ?: false

    /** Bytes [increaseResolution] would download for the current view; null while offline, without a view or below z10. */
    suspend fun estimateIncreaseBytes(): Long? = terrain?.estimateBytes()

    /**
     * Downloads the elevation chunks the current view (viewport + 40 km) is missing: [FetchProgress.Estimating] first,
     * then [FetchProgress.Fetching] by bytes, then [FetchProgress.Done], or [FetchProgress.Failed] ("offline", "metered",
     * "storage", "no view", "zoom in" or the I/O error). Metered networks need [allowMetered] (the UI confirms the
     * size first). Cancel by cancelling the collection. Tiles re-request once the chunks are in.
     */
    fun increaseResolution(allowMetered: Boolean): Flow<FetchProgress> =
        terrain?.increaseResolution(allowMetered) ?: flowOf(FetchProgress.Failed("terrain unavailable"))

    /** Cancels running renders whose selection or data version is no longer current, so dead keys are never written. */
    private fun cancelStale() {
        for ((key, token) in active) if (!isCurrent(key)) token.cancel()
    }

    /**
     * The sample at the point from the highest-zoom tile already in the memory cache for the current selection and
     * data, or null when there is none. [CoverageSample.best] is also null when the tile's region is no longer
     * prepared.
     */
    fun valueAt(lat: Double, lon: Double): CoverageSample? {
        val sel = selection.value ?: return null
        val stamp = towers.stamp() ?: return null
        val etag = patternsEtag()
        for (z in MAX_ZOOM downTo MIN_ZOOM) {
            val at = TileMath.locate(lat, lon, z) ?: return null
            val key = TileKey(z, at.x, at.y, sel.cacheId(), stamp, etag, terrainVersion(sel.cacheId(), stamp, etag, z, at.x, at.y))
            val tile = cache.getMemory(key) ?: continue
            val i = at.py * 256 + at.px
            val rsrpCode = tile.rsrp[i].toInt() and 0xFF
            val covered = rsrpCode != 0
            return CoverageSample(
                rsrpDbm = if (covered) rsrpCode / 2f - 140f else null,
                sinrDb = if (covered) (tile.sinr[i].toInt() and 0xFF) / 4f - 20f else null,
                probability = if (covered) (tile.prob[i].toInt() and 0xFF) / 255f else null,
                best = if (covered) bestServer(key, tile, i, lat, lon) else null,
            )
        }
        return null
    }

    private fun bestServer(key: TileKey, tile: RenderedTile, pixel: Int, lat: Double, lon: Double): BestServer? {
        val carrier = tile.bestCarrier(pixel).takeIf { it >= 0 } ?: return null
        val region = (0..MAX_LEVEL).firstNotNullOfOrNull { level ->
            synchronized(regions) { regions[regionKey(key, level)] }
                ?.takeIf { it.isDone && !it.isCompletedExceptionally }?.getNow(null)?.takeIf { it.prepared != null }
        } ?: return null
        val row = region.prepared!!.batchIndex(carrier)
        val b = region.batch
        return BestServer(
            Network.entries[b.network[row].toInt()],
            b.bandLabels[b.bandIdx[row].toInt() and 0xFF],
            TileMath.distanceM(lat, lon, b.siteLat[row], b.siteLon[row]),
        )
    }

    private fun rawTile(key: TileKey, sel: CoverageSelection): CompletableFuture<RenderedTile> {
        cache.getMemory(key)?.let { return CompletableFuture.completedFuture(it) }
        val future: CompletableFuture<RenderedTile>
        synchronized(inflight) {
            inflight[key]?.let { return it }
            // A render may have finished between the memory lookup and taking the lock.
            cache.getMemory(key)?.let { return CompletableFuture.completedFuture(it) }
            future = CompletableFuture()
            inflight[key] = future
        }
        future.whenComplete { _, _ -> synchronized(inflight) { inflight.remove(key, future) } }
        pool.submit(
            Triple(key.z, key.x, key.y),
            work = { cancel -> run(key, sel, cancel, future) },
            onCancelled = { future.completeExceptionally(TileCancelledException()) },
        )
        return future
    }

    /** Runs on a pool thread. */
    private fun run(key: TileKey, sel: CoverageSelection, cancel: CancelToken, future: CompletableFuture<RenderedTile>) {
        try {
            if (cancel.isCancelled || !isCurrent(key)) {
                future.completeExceptionally(TileCancelledException())
                return
            }
            active[key] = cancel
            val tile = cache.getDisk(key)
                ?: render(key, sel, cancel)?.takeIf { !cancel.isCancelled && isCurrent(key) }?.also { cache.put(key, it) }
            if (tile == null) future.completeExceptionally(TileCancelledException()) else future.complete(tile)
            if (tile != null) refreshAccuracy()
        } catch (t: Throwable) {
            future.completeExceptionally(t)
        } finally {
            active.remove(key, cancel)
        }
    }

    /** Selection, tower stamp and patterns etag are still the current ones (the terrain version is not checked). */
    private fun isDataCurrent(key: TileKey): Boolean =
        selection.value?.cacheId() == key.selectionId && towers.stamp() == key.towerStamp && patternsEtag() == key.patternsEtag

    private fun isCurrent(key: TileKey): Boolean =
        isDataCurrent(key) &&
            terrainVersion(key.selectionId, key.towerStamp, key.patternsEtag, key.z, key.x, key.y) == key.terrainVersion

    private fun render(key: TileKey, sel: CoverageSelection, cancel: CancelToken): RenderedTile? {
        val region = region(key, sel)
        val area = areaOf(key.towerStamp, key.z, key.x, key.y)
        return renderer.render(preparedFor(key, sel, region, cancel), key.z, key.x, key.y, area, THRESHOLD_DBM, cancel)
    }

    private fun areaOf(stamp: String, z: Int, x: Int, y: Int): AreaClass = towers.withTowers { t ->
        if (t.stamp != stamp) throw StaleTileException()
        AreaClassifier.classify(t.siteCountWithin(TileMath.centreLat(z, y), TileMath.centreLon(z, x), AreaClassifier.RADIUS_M))
    } ?: throw StaleTileException()

    /**
     * Records [tr]'s table demand once. The tables that matter are those of the carriers that serve its pixels, so the
     * region's z10 tile is rendered with the quick model first and its best servers lead the demand (most pixels
     * first). False if [cancel] fired meanwhile (nothing recorded; the next render or demand job asks again).
     */
    private fun recordDemand(tc: TerrainCoordinator, tr: TerrainRegion, key: TileKey, region: Region, cancel: CancelToken): Boolean {
        if (!tc.needsDemand(tr)) return true
        val prepared = region.prepared ?: return true.also { tc.setDemand(tr, region.batch, IntArray(0)) }
        val k = tr.key
        val sample = renderer.render(prepared, k.zf, k.rx, k.ry, areaOf(key.towerStamp, k.zf, k.rx, k.ry), THRESHOLD_DBM, cancel)
        if (sample == null || cancel.isCancelled) return false
        val pixels = HashMap<Int, Int>()
        for (i in 0 until RenderedTile.PIXELS) {
            val c = sample.bestCarrier(i)
            if (c >= 0) pixels.merge(prepared.batchIndex(c), 1, Int::plus)
        }
        val servers = pixels.entries.sortedByDescending { it.value }.map { it.key }.toIntArray()
        tc.setDemand(tr, region.batch, servers)
        return true
    }

    /**
     * The region prepared for [key]'s terrain version: the plain quick-model one while its region has no tables, else
     * one built from exactly the tables of that version's snapshot (no mixing). Asks for the region's missing tables.
     * Fails with [TileCancelledException] if the region moved on to another version meanwhile.
     */
    private fun preparedFor(key: TileKey, sel: CoverageSelection, region: Region, cancel: CancelToken): PreparedRegion {
        val tc = terrain
        if (tc == null || key.terrainVersion == 0L) return region.prepared!!
        val tk = tc.keyFor(key.selectionId, key.towerStamp, key.patternsEtag, key.z, key.x, key.y)
            ?: return region.prepared!!
        // Dropped meanwhile (LRU or trim): no tile may be rendered quick under this key's terrain version.
        val tr = tc.existingRegion(tk) ?: throw TileCancelledException()
        if (!recordDemand(tc, tr, key, region, cancel)) throw TileCancelledException()
        val snap = tc.snapshot(tr)
        if (snap.version != key.terrainVersion) throw TileCancelledException()
        tc.ensureTables(tr, Triple(key.z, key.x, key.y))
        if (snap.tables.isEmpty()) return region.prepared!!
        val pk = TerrainPrepKey(region.key, tr.key, snap.version)
        val mine = CompletableFuture<PreparedRegion>()
        val existing = synchronized(terrainPrepared) { terrainPrepared[pk] ?: run { terrainPrepared[pk] = mine; null } }
        if (existing == null) {
            try {
                val store = patterns.value
                if ((store?.etag ?: "none") != key.patternsEtag) throw StaleTileException()
                val provider = { row: Int -> tc.tableKey(tr, region.batch, row)?.let { snap.tables[it] } }
                mine.complete(renderer.prepare(region.batch, store, sel.indoor, provider))
            } catch (t: Throwable) {
                synchronized(terrainPrepared) { terrainPrepared.remove(pk, mine) }
                mine.completeExceptionally(t)
            }
        }
        try {
            return (existing ?: mine).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun regionKey(k: TileKey, level: Int): RegionKey {
        if (level == 0) {
            val d = k.z - MIN_ZOOM
            return RegionKey(k.selectionId, k.towerStamp, k.patternsEtag, 0, MIN_ZOOM, k.x shr d, k.y shr d)
        }
        // z10 tile containing the tile; tiles above z10 (z8, z9) are their own area.
        val zf = if (k.z >= FALLBACK_ZOOM) FALLBACK_ZOOM else k.z
        val d = k.z - zf
        return RegionKey(k.selectionId, k.towerStamp, k.patternsEtag, level, zf, k.x shr d, k.y shr d)
    }

    /** The prepared region for [key]'s tile at the first fallback level whose batch fits the engine. */
    private fun region(key: TileKey, sel: CoverageSelection): Region {
        var reuse: CarrierBatch? = null
        for (level in 0 until MAX_LEVEL) {
            val rk = regionKey(key, level)
            if (synchronized(regions) { rk in overflowed }) continue
            val r = regionAt(rk, sel, null)
            if (r.prepared != null) return r
            if (level == MAX_LEVEL - 1) reuse = r.batch // level 2 works on the level-1 batch
        }
        return regionAt(regionKey(key, MAX_LEVEL), sel, reuse)
    }

    /**
     * Get-or-build of one cache entry: built by the first caller while others wait. An over-cap result is returned
     * (carrying its batch) but not cached; its key goes to [overflowed] instead.
     */
    private fun regionAt(rk: RegionKey, sel: CoverageSelection, reuse: CarrierBatch?): Region {
        val mine = CompletableFuture<Region>()
        val existing = synchronized(regions) { regions[rk] ?: run { regions[rk] = mine; null } }
        if (existing == null) {
            try {
                val built = buildRegion(rk, sel, reuse)
                if (built.prepared == null) synchronized(regions) { regions.remove(rk, mine); overflowed[rk] = true }
                mine.complete(built)
            } catch (t: Throwable) {
                synchronized(regions) { regions.remove(rk, mine) }
                mine.completeExceptionally(t)
            }
        }
        try {
            return (existing ?: mine).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * Ruling JJ. Level 0 queries the z8 tile + 80 km, level 1 the z10 tile + 80 km; a batch over the engine's cap is
     * reported as unprepared so the next level is tried. Level 2 culls the level-1 batch (passed in as [reuse], or
     * queried again if it is not at hand) to carriers whose range reaches the z10 tile and, if still over the cap,
     * keeps the nearest ones, with a warning.
     */
    private fun buildRegion(rk: RegionKey, sel: CoverageSelection, reuse: CarrierBatch?): Region {
        val area = TileMath.bounds(rk.zf, rk.rx, rk.ry)
        var batch = reuse ?: towers.withTowers { t ->
            if (t.stamp != rk.stamp) throw StaleTileException()
            t.carriersIn(TileMath.expand(area, REGION_MARGIN_M), sel.networks(), sel.techs, sel.bands)
        } ?: throw StaleTileException()
        val patternStore = patterns.value
        if ((patternStore?.etag ?: "none") != rk.etag) throw StaleTileException()
        if (rk.level < MAX_LEVEL) {
            if (batch.size > MAX_CARRIERS) return Region(rk, null, batch)
        } else if (batch.size > MAX_CARRIERS) {
            val before = batch.size
            batch = batch.subset(CarrierCulling.reaching(batch, area))
            if (batch.size > MAX_CARRIERS) {
                batch = batch.subset(CarrierCulling.nearest(batch, TileMath.centreLat(rk.zf, rk.ry), TileMath.centreLon(rk.zf, rk.rx), MAX_CARRIERS))
                warn("coverage region ${rk.zf}/${rk.rx}/${rk.ry}: $before carriers, kept the $MAX_CARRIERS nearest the tile centre")
            } else {
                warn("coverage region ${rk.zf}/${rk.rx}/${rk.ry}: $before carriers, culled to ${batch.size} by range")
            }
        }
        return Region(rk, renderer.prepare(batch, patternStore, sel.indoor), batch)
    }

    private fun <T> failed(e: Throwable): CompletableFuture<T> = CompletableFuture<T>().also { it.completeExceptionally(e) }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        const val HOST = "coverage.signalatlas.invalid"
        const val MIN_ZOOM = 8
        const val MAX_ZOOM = 16
        private const val MAX_REGIONS = 16
        private const val MAX_OVERFLOW_HINTS = 512
        private const val MAX_TERRAIN_PREPARED = 8
        private const val MAX_CARRIERS = 65535 // PreparedCarriers.MAX_CARRIERS
        private const val MAX_LEVEL = 2
        private const val FALLBACK_ZOOM = 10
        private const val TAG = "CoverageTileService"
        private const val REGION_MARGIN_M = 80_000.0
        private const val THRESHOLD_DBM = -118f
    }
}
