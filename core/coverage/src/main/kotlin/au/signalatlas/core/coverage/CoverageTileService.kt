package au.signalatlas.core.coverage

import au.signalatlas.core.data.CarrierBatch
import au.signalatlas.core.data.PatternStore
import au.signalatlas.core.data.TowerRepository
import au.signalatlas.core.model.Network
import au.signalatlas.engine.CancelToken
import au.signalatlas.engine.CoverageEngine
import au.signalatlas.engine.RenderedTile
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

    private class Region(val prepared: PreparedRegion, val batch: CarrierBatch)
    private data class RegionKey(val selectionId: String, val stamp: String, val etag: String, val rx: Int, val ry: Int)

    private val inflight = HashMap<TileKey, CompletableFuture<RenderedTile>>()
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
    fun setVisible(tiles: Set<Triple<Int, Int, Int>>) = pool.setVisible(tiles)

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
                probability = (tile.prob[i].toInt() and 0xFF) / 255f,
                best = if (covered) bestServer(key, tile, i, lat, lon) else null,
            )
        }
        return null
    }

    private fun bestServer(key: TileKey, tile: RenderedTile, pixel: Int, lat: Double, lon: Double): BestServer? {
        val carrier = tile.bestCarrier(pixel).takeIf { it >= 0 } ?: return null
        val region = synchronized(regions) { regions[regionKey(key)] }
            ?.takeIf { it.isDone && !it.isCompletedExceptionally }?.getNow(null) ?: return null
        val row = region.prepared.batchIndex(carrier)
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
            val tile = cache.getDisk(key) ?: render(key, sel, cancel)?.also { cache.put(key, it) }
            if (tile == null) future.completeExceptionally(TileCancelledException()) else future.complete(tile)
        } catch (t: Throwable) {
            future.completeExceptionally(t)
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
        return renderer.render(region.prepared, key.z, key.x, key.y, area, THRESHOLD_DBM, cancel)
    }

    private fun regionKey(k: TileKey) =
        RegionKey(k.selectionId, k.towerStamp, k.patternsEtag, k.x shr (k.z - MIN_ZOOM), k.y shr (k.z - MIN_ZOOM))

    /** The prepared region for [key]'s tile, built by the first caller while others wait. */
    private fun region(key: TileKey, sel: CoverageSelection): Region {
        val rk = regionKey(key)
        val mine = CompletableFuture<Region>()
        val existing = synchronized(regions) { regions[rk] ?: run { regions[rk] = mine; null } }
        if (existing == null) {
            try {
                mine.complete(buildRegion(rk, sel))
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

    private fun buildRegion(rk: RegionKey, sel: CoverageSelection): Region {
        val bounds = TileMath.expand(TileMath.bounds(MIN_ZOOM, rk.rx, rk.ry), REGION_MARGIN_M)
        val batch = towers.withTowers { t ->
            if (t.stamp != rk.stamp) throw StaleTileException()
            t.carriersIn(bounds, setOf(sel.network), sel.techs, sel.bands)
        } ?: throw StaleTileException()
        val patternStore = patterns.value
        if ((patternStore?.etag ?: "none") != rk.etag) throw StaleTileException()
        return Region(renderer.prepare(batch, patternStore, sel.indoor), batch)
    }

    private fun <T> failed(e: Throwable): CompletableFuture<T> = CompletableFuture<T>().also { it.completeExceptionally(e) }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        const val HOST = "coverage.signalatlas.invalid"
        const val MIN_ZOOM = 8
        const val MAX_ZOOM = 16
        private const val MAX_REGIONS = 8
        private const val REGION_MARGIN_M = 80_000.0
        private const val THRESHOLD_DBM = -118f
    }
}
