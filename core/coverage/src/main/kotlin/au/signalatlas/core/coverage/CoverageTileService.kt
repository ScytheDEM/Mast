package au.signalatlas.core.coverage

import au.signalatlas.core.data.CarrierBatch
import au.signalatlas.core.data.PatternStore
import au.signalatlas.core.data.TowerRepository
import au.signalatlas.core.model.Network
import au.signalatlas.engine.CancelToken
import au.signalatlas.engine.CoverageEngine
import au.signalatlas.engine.RenderedTile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.future.await
import java.net.URLEncoder
import java.util.concurrent.CompletableFuture
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
 */
class CoverageTileService internal constructor(
    private val towers: TowerData,
    private val patterns: StateFlow<PatternStore?>,
    private val renderer: TileRenderer,
    private val cache: TileCache,
    private val pool: RenderPool,
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
) {
    constructor(
        repo: TowerRepository, patterns: StateFlow<PatternStore?>, engine: CoverageEngine, cache: TileCache,
        pool: RenderPool,
    ) : this(RepositoryTowerData(repo), patterns, EngineTileRenderer(engine), cache, pool)

    constructor(
        repo: TowerRepository, patterns: StateFlow<PatternStore?>, renderer: TileRenderer, cache: TileCache,
        pool: RenderPool,
    ) : this(RepositoryTowerData(repo), patterns, renderer, cache, pool)

    /** Null = coverage off. */
    val selection: MutableStateFlow<CoverageSelection?> = MutableStateFlow(null)

    /** [prepared] is null when the batch exceeded the engine's carrier cap (it is then never cached). */
    private class Region(val prepared: PreparedRegion?, val batch: CarrierBatch)

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

    private fun patternsEtag(): String = patterns.value?.etag ?: "none"

    /**
     * The URL template for the current selection and data versions, or null when coverage is off or no tower DB is
     * installed. `view` is a segment so a view switch changes the URL (MapLibre refetches) while reusing cached tiles.
     */
    fun tileUrlTemplate(): String? {
        val sel = selection.value ?: return null
        val stamp = towers.stamp() ?: return null
        return "https://$HOST/v1/${sel.cacheId()}/${sel.view.segment}/${encode(stamp)}/${encode(patternsEtag())}/{z}/{x}/{y}.png"
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
            tile.patternsEtag != patternsEtag()
        ) {
            return failed(StaleTileException())
        }
        val key = TileKey(tile.z, tile.x, tile.y, tile.selectionId, stamp, tile.patternsEtag)
        return rawTile(key, sel).thenApply { TerrariumEncoder.encode(it.rsrp, tile.view, it.prob) }
    }

    /** Tiles the viewport needs; other queued or running renders are cancelled. */
    fun setVisible(tiles: Set<Triple<Int, Int, Int>>) {
        cancelStale()
        pool.setVisible(tiles)
    }

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
            val key = TileKey(z, at.x, at.y, sel.cacheId(), stamp, etag)
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
        } catch (t: Throwable) {
            future.completeExceptionally(t)
        } finally {
            active.remove(key, cancel)
        }
    }

    private fun isCurrent(key: TileKey): Boolean =
        selection.value?.cacheId() == key.selectionId && towers.stamp() == key.towerStamp &&
            patternsEtag() == key.patternsEtag

    private fun render(key: TileKey, sel: CoverageSelection, cancel: CancelToken): RenderedTile? {
        val region = region(key, sel)
        val area = towers.withTowers { t ->
            if (t.stamp != key.towerStamp) throw StaleTileException()
            AreaClassifier.classify(
                t.siteCountWithin(TileMath.centreLat(key.z, key.y), TileMath.centreLon(key.z, key.x), AreaClassifier.RADIUS_M),
            )
        } ?: throw StaleTileException()
        return renderer.render(region.prepared!!, key.z, key.x, key.y, area, THRESHOLD_DBM, cancel)
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
            if (batch.size > MAX_CARRIERS) return Region(null, batch)
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
        return Region(renderer.prepare(batch, patternStore, sel.indoor), batch)
    }

    private fun <T> failed(e: Throwable): CompletableFuture<T> = CompletableFuture<T>().also { it.completeExceptionally(e) }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        const val HOST = "coverage.signalatlas.invalid"
        const val MIN_ZOOM = 8
        const val MAX_ZOOM = 16
        private const val MAX_REGIONS = 16
        private const val MAX_OVERFLOW_HINTS = 512
        private const val MAX_CARRIERS = 65535 // PreparedCarriers.MAX_CARRIERS
        private const val MAX_LEVEL = 2
        private const val FALLBACK_ZOOM = 10
        private const val TAG = "CoverageTileService"
        private const val REGION_MARGIN_M = 80_000.0
        private const val THRESHOLD_DBM = -118f
    }
}
