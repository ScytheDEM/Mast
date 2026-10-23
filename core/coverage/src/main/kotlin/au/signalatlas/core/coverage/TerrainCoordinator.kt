package au.signalatlas.core.coverage

import au.signalatlas.core.data.CarrierBatch
import au.signalatlas.core.geo.ChunkId
import au.signalatlas.core.geo.ChunkMath
import au.signalatlas.core.geo.InsufficientStorageException
import au.signalatlas.core.geo.MeteredNetworkException
import au.signalatlas.core.model.GeoBounds
import au.signalatlas.engine.CancelToken
import au.signalatlas.engine.DemGridHandle
import au.signalatlas.engine.TerrainTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.util.zip.CRC32
import kotlin.math.cos
import kotlin.math.roundToInt

/** The terrain data of one tile area: the z10 tile containing the tile (or the tile itself above z10), for one selection and data version. */
internal data class TerrainKey(
    val selectionId: String, val stamp: String, val etag: String, val zf: Int, val rx: Int, val ry: Int,
)

/** The terrain tables a region had ready at [version]: an immutable set, so a render never sees a half-published one. */
internal class TerrainSnapshot(val version: Long, val tables: Map<TableKey, TerrainTable>)

/** A table a region wants: [key] plus the site position the build needs. */
internal class Demand(val key: TableKey, val lat: Double, val lon: Double)

/** Mutable state of one terrain region; every field after [chunks] is guarded by [TerrainCoordinator]'s lock. */
internal class TerrainRegion(val key: TerrainKey, val bounds: GeoBounds, val chunks: Set<ChunkId>) {
    /** Names the DEM rectangle (the chunk set); part of every table key. */
    val demId: Long = chunks.sortedWith(compareBy({ it.latIdx }, { it.lonIdx })).let { sorted ->
        val crc = CRC32()
        for (c in sorted) crc.update("${c.latIdx},${c.lonIdx};".toByteArray())
        crc.value or (sorted.size.toLong() shl 32)
    }

    /** The DEM mosaic's extent: a site outside it cannot get a table. */
    val extent = GeoBounds(
        chunks.minOf { it.southDeg }, chunks.minOf { it.westDeg },
        chunks.maxOf { it.southDeg } + 0.25, chunks.maxOf { it.westDeg } + 0.25,
    )

    val openLock = Any()
    var available = false
    var snapshot = TerrainSnapshot(0L, emptyMap())
    var demand: List<Demand>? = null
    var demandRequested = false
    val pending = HashSet<TableKey>()
    val staged = HashMap<TableKey, TerrainTable>()
    val failed = HashSet<TableKey>()
    val tokens = HashSet<CancelToken>()
    var handle: DemGridHandle? = null
    var running = 0
    var retired = false
}

/**
 * The terrain half of [CoverageTileService] (Task 5). Per [TerrainRegion] (the z10 tile containing a tile at z >= 10, expanded by
 * 40 km for the elevation chunks):
 *
 * - **Availability.** Chunks = `ChunkMath.chunksFor(bounds, 40 km)`. All stored: the region is available and builds
 *   tables; otherwise it is quick-only and its [versionOf] is 0. Newly stored chunks ([onChunksChanged]) make it
 *   available and change its version.
 * - **Tables.** Wanted tables ([Demand]: the 128 nearest sites' (site, frequency key, height key) triples whose carriers
 *   reach the region) are built as [RenderPool] jobs one priority below visible renders, deduplicated per key, read
 *   from / written to [TerrainTableStore]. The DEM mosaic is opened by the first build and closed when the region
 *   has nothing pending (or is evicted). A mosaic with a missing chunk (evicted since the check) is dropped and the
 *   region falls back to quick; nothing relies on exceptions for that.
 * - **Versions.** A region's version is a hash of its chunk set and its ready table keys. Finished tables are *staged*
 *   and only published (new [TerrainSnapshot], new version, [epoch] + 1) when nothing is pending or [PUBLISH_BATCH]
 *   tables are staged, so a tile is rendered from exactly one snapshot and MapLibre is not told to refetch per table.
 * - **Network.** Camera-settle auto-caching on unmetered networks and [increaseResolution].
 */
internal class TerrainCoordinator(
    private val support: TerrainSupport,
    private val renderer: TileRenderer,
    private val pool: RenderPool,
    private val onChange: () -> Unit,
    private val warn: (String) -> Unit,
) {
    /** What the visible tiles belong to; null while coverage is off. */
    class Context(val selectionId: String, val stamp: String, val etag: String)

    private class View(val tiles: Set<TileAddress>, val z: Int, val bounds: GeoBounds)

    private val lock = Any()
    private val regions = object : LinkedHashMap<TerrainKey, TerrainRegion>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TerrainKey, TerrainRegion>): Boolean {
            if (size <= MAX_REGIONS) return false
            retireLocked(eldest.value)
            return true
        }
    }
    private val _epoch = MutableStateFlow(0L)

    /** Changes whenever any region's version does; the tile URL carries it. */
    val epoch: StateFlow<Long> get() = _epoch

    @Volatile private var context: Context? = null
    @Volatile private var view: View? = null
    private var settleJob: Job? = null
    private var autoJob: Job? = null
    private val bumpLock = Any()
    private var lastBump = Long.MIN_VALUE
    private var bumpScheduled = false
    private var autoNeed: Set<ChunkId> = emptySet()
    @Volatile private var autoFailed: Set<ChunkId>? = null

    init {
        support.scope.launch {
            support.network.unmetered.collect { if (it) { autoFailed = null; autoCache() } }
        }
    }

    // ---- regions and versions -------------------------------------------------------------------------------------

    /** The region of a tile; null below z10 (Ruling QQ: z8 and z9 are quick-only and never get a region). */
    fun keyFor(selectionId: String, stamp: String, etag: String, z: Int, x: Int, y: Int): TerrainKey? =
        if (z < TERRAIN_ZOOM) null
        else TerrainKey(selectionId, stamp, etag, TERRAIN_ZOOM, x shr (z - TERRAIN_ZOOM), y shr (z - TERRAIN_ZOOM))

    /**
     * The version of the region of [k] (0 = quick only), creating it on first use. [recheck] looks again for chunks
     * stored since the region last found some missing (what an incoming tile request does).
     */
    fun versionOf(k: TerrainKey?, recheck: Boolean): Long {
        if (k == null) return 0L
        var changed = false
        val v = synchronized(lock) {
            val tr = regionLocked(k)
            if (recheck && !tr.available && tr.chunks.all(support.chunks::has)) changed = markAvailableLocked(tr)
            tr.snapshot.version
        }
        if (changed) bumped()
        return v
    }

    /** The version of an existing region, or 0 without creating one. */
    fun peekVersion(k: TerrainKey?): Long = if (k == null) 0L else synchronized(lock) { regions[k]?.snapshot?.version ?: 0L }

    fun region(k: TerrainKey): TerrainRegion = synchronized(lock) { regionLocked(k) }

    private fun regionLocked(k: TerrainKey): TerrainRegion = regions.getOrPut(k) {
        val bounds = TileMath.bounds(k.zf, k.rx, k.ry)
        val tr = TerrainRegion(k, bounds, ChunkMath.chunksFor(bounds, MARGIN_KM))
        if (tr.chunks.all(support.chunks::has)) markAvailableLocked(tr)
        tr
    }

    /** Caller holds [lock]. True if the version changed. */
    private fun markAvailableLocked(tr: TerrainRegion): Boolean {
        tr.available = true
        return setSnapshotLocked(tr, tr.snapshot.tables)
    }

    private fun setSnapshotLocked(tr: TerrainRegion, tables: Map<TableKey, TerrainTable>): Boolean {
        val v = if (!tr.available) 0L else versionHash(tr, tables.keys)
        val changed = v != tr.snapshot.version
        tr.snapshot = TerrainSnapshot(v, tables)
        return changed
    }

    private fun versionHash(tr: TerrainRegion, keys: Set<TableKey>): Long {
        var h = FNV_OFFSET xor tr.demId
        h *= FNV_PRIME
        for (k in keys.sortedWith(compareBy({ it.latE5 }, { it.lonE5 }, { it.fkey }, { it.hkey }))) {
            for (n in longArrayOf(k.latE5.toLong(), k.lonE5.toLong(), k.fkey.toLong(), k.hkey.toLong())) {
                h = (h xor n) * FNV_PRIME
            }
        }
        return if (h == 0L) 1L else h
    }

    /**
     * A version changed. The epoch (which makes MapLibre refetch everything) goes up at most once per
     * [TerrainSupport.minBumpMs]: a bump inside that window is deferred and covers every change until then.
     */
    private fun bumped() {
        val delayMs = synchronized(bumpLock) {
            val now = support.clock()
            val wait = lastBump + support.minBumpMs - now
            if (lastBump == Long.MIN_VALUE || wait <= 0) {
                lastBump = now
                0L
            } else {
                if (bumpScheduled) return
                bumpScheduled = true
                wait
            }
        }
        if (delayMs == 0L) {
            _epoch.value += 1
            onChange()
        } else {
            support.scope.launch {
                delay(delayMs)
                synchronized(bumpLock) { bumpScheduled = false; lastBump = support.clock() }
                _epoch.value += 1
                onChange()
            }
        }
    }

    fun snapshot(tr: TerrainRegion): TerrainSnapshot = synchronized(lock) { tr.snapshot }

    /** The table key of batch row [row] in [tr], or null for a carrier without a usable frequency. */
    fun tableKey(tr: TerrainRegion, b: CarrierBatch, row: Int): TableKey? {
        val f = b.freqMhz[row]
        if (!f.isFinite() || f <= 0f) return null
        return TableKey(
            tr.key.stamp, (b.siteLat[row] * E5).roundToInt(), (b.siteLon[row] * E5).roundToInt(),
            TerrainTable.frequencyKeyMhz(f), TerrainTable.heightKeyM(b.heightM[row]), tr.demId,
        )
    }

    // ---- demand and table builds ----------------------------------------------------------------------------------

    /**
     * True once for a usable region of [k] that has no table demand yet and nobody is computing it: its tiles may be
     * served from the tile cache (the region was recreated after an eviction), so no render will ask for tables.
     * Pair with [demandDone].
     */
    fun claimDemand(k: TerrainKey?): Boolean {
        if (k == null) return false
        return synchronized(lock) {
            val tr = regionLocked(k)
            if (tr.retired || !tr.available || tr.demand != null || tr.demandRequested) false else { tr.demandRequested = true; true }
        }
    }

    fun demandDone(k: TerrainKey) = synchronized(lock) { regions[k]?.demandRequested = false }

    /** Records which tables [tr] wants, from [batch], the first time (the wanted set is stable for a region). */
    fun setDemand(tr: TerrainRegion, batch: CarrierBatch) {
        if (synchronized(lock) { tr.demand != null }) return
        val list = demandOf(tr, batch)
        synchronized(lock) { if (tr.demand == null) tr.demand = list }
    }

    private fun demandOf(tr: TerrainRegion, b: CarrierBatch): List<Demand> {
        val cLat = (tr.bounds.south + tr.bounds.north) / 2
        val cLon = (tr.bounds.west + tr.bounds.east) / 2
        val cos = cos(Math.toRadians(cLat))
        fun d2(row: Int): Double {
            val dy = b.siteLat[row] - cLat
            val dx = (b.siteLon[row] - cLon) * cos
            return dx * dx + dy * dy
        }
        val rows = CarrierCulling.reaching(b, tr.bounds)
            .filter { tr.extent.contains(b.siteLat[it], b.siteLon[it]) }
            .sortedBy(::d2)
        val seen = LinkedHashMap<TableKey, Demand>()
        for (row in rows) {
            val key = tableKey(tr, b, row) ?: continue
            if (key !in seen) seen[key] = Demand(key, b.siteLat[row], b.siteLon[row])
            if (seen.size >= MAX_TABLES_PER_REGION) break
        }
        return seen.values.toList()
    }

    /** Queues a build for every wanted table of [tr] that is not ready, pending, staged or failed; jobs sit under [tile]. */
    fun ensureTables(tr: TerrainRegion, tile: TileAddress) {
        val todo = synchronized(lock) {
            val demand = tr.demand
            if (tr.retired || !tr.available || demand == null) return
            demand.filter { d ->
                val k = d.key
                (k !in tr.snapshot.tables && k !in tr.staged && k !in tr.pending && k !in tr.failed).also { if (it) tr.pending += k }
            }
        }
        for (d in todo) {
            pool.submit(
                tile, RenderPool.PRIORITY_TABLE,
                work = { cancel -> runJob(tr, d, tile, cancel) },
                onCancelled = { finishJob(tr, d.key, tile, null, failed = false, counted = false) },
            )
        }
    }

    /** Runs on a pool thread. */
    private fun runJob(tr: TerrainRegion, d: Demand, tile: TileAddress, cancel: CancelToken) {
        val counted = synchronized(lock) {
            if (tr.retired) false else { tr.running++; tr.tokens += cancel; true }
        }
        var table: TerrainTable? = null
        var failed = false
        try {
            if (counted && !cancel.isCancelled) {
                table = support.tables.get(d.key)
                if (table == null) {
                    val dem = openDem(tr)
                    if (dem != null && !cancel.isCancelled) {
                        table = renderer.buildTerrainTable(dem, d.lat, d.lon, d.key.hkey.toFloat(), d.key.fkey.toFloat(), cancel)
                        if (table != null) support.tables.put(d.key, table)
                    }
                }
            }
        } catch (t: Throwable) {
            failed = true
            warn("terrain table ${d.key.fileName} failed: $t")
        }
        finishJob(tr, d.key, tile, table, failed, counted, cancel)
    }

    /**
     * The region's DEM mosaic, opened on first use. Null when the region was retired or its chunks are no longer all
     * stored: then it is quick-only again (version 0) instead of building from fabricated zeros.
     */
    private fun openDem(tr: TerrainRegion): DemGridHandle? = synchronized(tr.openLock) {
        synchronized(lock) { if (tr.retired || !tr.available) return null; tr.handle?.let { return it } }
        val h = DemGridHandle.open(support.chunks, tr.chunks)
        var changed = false
        val usable = synchronized(lock) {
            when {
                tr.retired -> false
                !h.isComplete -> { tr.available = false; tr.staged.clear(); changed = setSnapshotLocked(tr, emptyMap()); false }
                else -> { tr.handle = h; true }
            }
        }
        if (!usable) h.close()
        if (changed) bumped()
        if (usable) h else null
    }

    private fun finishJob(
        tr: TerrainRegion, key: TableKey, tile: TileAddress, table: TerrainTable?, failed: Boolean, counted: Boolean,
        cancel: CancelToken? = null,
    ) {
        var published = false
        var requeue: TileAddress? = null
        synchronized(lock) {
            tr.pending.remove(key)
            if (counted) { tr.running--; cancel?.let { tr.tokens -= it } }
            if (failed) tr.failed += key
            if (table != null && !tr.retired && tr.available) tr.staged[key] = table
            // A cancelled job (null table, no failure) never publishes: a pan must not change versions.
            val cancelled = table == null && !failed
            if (!cancelled && !tr.retired && tr.available && tr.staged.isNotEmpty() &&
                (tr.pending.isEmpty() || tr.staged.size >= PUBLISH_BATCH)
            ) {
                published = setSnapshotLocked(tr, tr.snapshot.tables + tr.staged)
                tr.staged.clear()
            }
            if (!tr.retired && tr.available && table == null && !failed && tile !in (view?.tiles ?: emptySet())) {
                requeue = visibleTileOfLocked(tr)
            }
            closeIfIdleLocked(tr)
        }
        if (published) bumped()
        requeue?.let { ensureTables(tr, it) }
    }

    /** Caller holds [lock]. */
    private fun visibleTileOfLocked(tr: TerrainRegion): TileAddress? {
        val ctx = context ?: return null
        return view?.tiles?.firstOrNull { (z, x, y) -> keyFor(ctx.selectionId, ctx.stamp, ctx.etag, z, x, y) == tr.key }
    }

    /** Caller holds [lock]: the mosaic is released once nothing builds or waits. */
    private fun closeIfIdleLocked(tr: TerrainRegion) {
        if (tr.running == 0 && (tr.retired || tr.pending.isEmpty())) {
            tr.handle?.close()
            tr.handle = null
        }
    }

    /** Caller holds [lock]. */
    private fun retireLocked(tr: TerrainRegion) {
        tr.retired = true
        tr.tokens.forEach { it.cancel() }
        tr.pending.clear()
        tr.staged.clear()
        closeIfIdleLocked(tr)
    }

    // ---- viewport: table demand, auto-cache ------------------------------------------------------------------------

    /** The viewport changed (main-thread cheap). */
    fun onVisible(tiles: Set<TileAddress>, ctx: Context?) {
        context = ctx
        val old = view
        if (tiles.isEmpty()) {
            view = null
            return
        }
        val z = tiles.maxOf { it.first }
        val top = tiles.filter { it.first == z }
        val bounds = top.map { (zz, x, y) -> TileMath.bounds(zz, x, y) }.let { bs ->
            GeoBounds(bs.minOf { it.south }, bs.minOf { it.west }, bs.maxOf { it.north }, bs.maxOf { it.east })
        }
        view = View(tiles.toSet(), z, bounds)
        if (ctx != null) {
            // Tables of a region whose tile is still on screen may have been dropped with a panned-away tile's job.
            for (t in top) {
                val k = keyFor(ctx.selectionId, ctx.stamp, ctx.etag, t.first, t.second, t.third) ?: continue
                val tr = synchronized(lock) { regions[k] }
                if (tr != null) ensureTables(tr, t)
            }
        }
        if (old == null || old.tiles != view!!.tiles) {
            settleJob?.cancel()
            settleJob = support.scope.launch {
                delay(support.settleMs)
                autoCache()
            }
        }
    }

    /** Every chunk the current view needs (viewport + 40 km) that is not stored. Null without a view. */
    private fun missingFor(v: View): Set<ChunkId> =
        ChunkMath.chunksFor(v.bounds, MARGIN_KM).filter { !support.chunks.has(it) }.toSet()

    /** Background fetch of the view's missing chunks: only z >= 10, only on an unmetered network, no prompts. */
    private fun autoCache() {
        val v = view ?: return
        if (v.z < TERRAIN_ZOOM || !support.network.unmetered.value) return
        val need = missingFor(v)
        if (need.isEmpty() || need == autoFailed) return
        synchronized(this) {
            // A fetch that already covers this view keeps going (a second trigger must not restart it).
            if (autoJob?.isActive == true && autoNeed.containsAll(need)) return
            autoJob?.cancel()
            autoNeed = need
            autoJob = support.scope.launch {
                try {
                    support.source.fetch(need, false) {}
                } catch (e: CancellationException) {
                    throw e
                } catch (e: InsufficientStorageException) {
                    autoFailed = need // not enough free space: don't hammer it at every settle
                } catch (e: MeteredNetworkException) {
                    // The network turned metered meanwhile: the unmetered collector retries when it is not.
                } catch (e: Exception) {
                    autoFailed = need
                    warn("elevation auto-cache failed: $e")
                }
                onChunksChanged()
            }
        }
    }

    /**
     * Memory pressure: drops the table memory LRU, every mosaic no build is using, and the regions (with their table
     * snapshots) that are not on screen; disk files stay, so they come back cheaply.
     */
    fun trimMemory() {
        support.tables.dropMemory()
        synchronized(lock) {
            val ctx = context
            val shown = if (ctx == null) emptySet() else view?.tiles?.mapNotNull {
                keyFor(ctx.selectionId, ctx.stamp, ctx.etag, it.first, it.second, it.third)
            }?.toSet() ?: emptySet()
            val it = regions.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key !in shown) { it.remove(); retireLocked(e.value) }
            }
            for (tr in regions.values) {
                if (tr.running == 0) { tr.handle?.close(); tr.handle = null }
            }
        }
    }

    /** Elevation chunks were stored: regions that were waiting for them become available and change version. */
    fun onChunksChanged() {
        var changed = false
        synchronized(lock) {
            for (tr in regions.values) {
                if (!tr.retired && !tr.available && tr.chunks.all(support.chunks::has)) changed = markAvailableLocked(tr) || changed
            }
        }
        if (changed) bumped()
    }

    /** Bytes [increaseResolution] would download; null while offline, without a view, or below z10. */
    suspend fun estimateBytes(): Long? {
        if (!support.network.isOnline()) return null
        val v = view?.takeIf { it.z >= TERRAIN_ZOOM } ?: return null
        val need = missingFor(v)
        return if (need.isEmpty()) 0L else support.source.estimateBytes(need)
    }

    /** Estimate, then download the view's missing chunks (see [CoverageTileService.increaseResolution]). */
    fun increaseResolution(allowMetered: Boolean): Flow<FetchProgress> = channelFlow {
        val v = view
        when {
            !support.network.isOnline() -> send(FetchProgress.Failed("offline"))
            v == null -> send(FetchProgress.Failed("no view"))
            v.z < TERRAIN_ZOOM -> send(FetchProgress.Failed("zoom in"))
            else -> {
                val need = missingFor(v)
                if (need.isEmpty()) {
                    send(FetchProgress.Done)
                    return@channelFlow
                }
                try {
                    send(FetchProgress.Estimating(support.source.estimateBytes(need)))
                    if (!allowMetered && support.network.isMetered()) {
                        send(FetchProgress.Failed("metered"))
                        return@channelFlow
                    }
                    support.source.fetch(need, allowMetered) { trySend(FetchProgress.Fetching(it.coerceIn(0f, 1f))) }
                    onChunksChanged()
                    send(FetchProgress.Done)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MeteredNetworkException) {
                    send(FetchProgress.Failed("metered"))
                } catch (e: InsufficientStorageException) {
                    send(FetchProgress.Failed("storage"))
                } catch (e: Exception) {
                    send(FetchProgress.Failed(e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    companion object {
        /** Elevation margin around a region (Global Constraints: terrain need = tile bounds + 40 km). */
        const val MARGIN_KM = 40.0
        const val TERRAIN_ZOOM = 10
        const val MAX_REGIONS = 12
        const val MAX_TABLES_PER_REGION = 128
        const val PUBLISH_BATCH = 64
        private const val E5 = 100_000.0
        private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
        private const val FNV_PRIME = 1099511628211L
    }
}
