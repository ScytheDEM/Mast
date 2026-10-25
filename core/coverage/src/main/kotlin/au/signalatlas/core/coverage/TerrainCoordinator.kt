package au.signalatlas.core.coverage

import au.signalatlas.core.model.DataSettings

import au.signalatlas.core.model.CoverageQuality

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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import kotlin.math.roundToInt

/** The terrain data of one tile area: the z10 tile containing the tile (or the tile itself above z10), for one selection and data version. */
internal data class TerrainKey(
    val selectionId: String, val stamp: String, val etag: String, val zf: Int, val rx: Int, val ry: Int,
)

/** The terrain tables a region had ready at [version]: an immutable set, so a render never sees a half-published one. */
internal class TerrainSnapshot(val version: Long, val tables: Map<TableKey, TerrainTable>)

/**
 * A table a tile wants: [key] plus the site position the build needs. [contender]: the carrier is the best server of
 * >= [TerrainCoordinator.CONTENDER_SHARE] of the tile's covered pixels, so its table is built first.
 */
internal class Want(val key: TableKey, val lat: Double, val lon: Double, val contender: Boolean)

/** One terrain region (a z10 tile): elevation availability and the tables published for it; guarded by the coordinator's lock. */
internal class TerrainRegion(val key: TerrainKey, val bounds: GeoBounds, val chunks: Set<ChunkId>) {
    /** Names the region's DEM rectangle (its chunk set); part of the version. */
    val demId: Long = TerrainCoordinator.chunkSetId(chunks)

    /** Sites whose tables can serve the region's pixels: R_max is capped at 80 km (M2 rule 9). */
    val reach: GeoBounds = TileMath.expand(bounds, CoverageTileService.REGION_MARGIN_M)

    var available = false
    var snapshot = TerrainSnapshot(0L, emptyMap())
    var retired = false
}

/**
 * The terrain half of [CoverageTileService] (M3a Task 5, redesigned in M3b batch 1 to remove the CBD terrain seams).
 *
 * - **Regions** (the z10 tile containing a tile at z >= 10) decide *whether* a tile uses terrain: its elevation chunks
 *   (`ChunkMath.chunksFor(bounds, 40 km)`) are all stored (else it is quick-only, version 0). Newly stored chunks
 *   ([onChunksChanged]) make it available.
 * - **Tables are global**, owned per [TableKey] across regions. A table is always built from its site's *home* DEM
 *   (the z10 tile containing the site, + 40 km), so it is the same whichever region asked for it, and every region
 *   within 80 km of the site uses the same object. A table whose home chunks are missing waits for them (they are part
 *   of the view's fetch).
 * - **Demand is contender-aware** ([demand]): a rendered (or cached) tile asks for the tables of its best servers;
 *   carriers serving >= 1% of its covered pixels are built first, the rest after.
 *   Builds are [RenderPool] jobs below visible renders, deduplicated per key and read from / written to
 *   [TerrainTableStore]. A job dropped with a panned-away tile is requeued under another visible tile that wants it.
 * - **Versions.** A region's version is a hash of its chunk set and of the keys of the tables in its snapshot: every
 *   published table whose site lies within 80 km of the region. Finished tables are *staged* and published together
 *   (when nothing is pending, or [PUBLISH_BATCH] are staged); a publish adds the tables to every kept region within
 *   reach, so neighbours across a z10 border change version together (no border where one side has a carrier's table
 *   and the other not). The epoch (tile URL) follows, at most once per [TerrainSupport.minBumpMs].
 * - **Labels.** A carrier without a table pays no terrain loss; its pixels count as terrain-incomplete in any tile
 *   rendered with terrain (version != 0), so such a tile is [Accuracy.PARTIAL] rather than [Accuracy.TERRAIN]; only a
 *   tile with no terrain applied at all is [Accuracy.QUICK] (see [Accuracy.of]). Only serving carriers count.
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

    private class View(val tiles: Set<TileAddress>, val z: Int)

    /** A site's home DEM: the z10 tile containing it + 40 km. The mosaic is opened by the first build and shared. */
    private class Home(val rx: Int, val ry: Int, val chunks: Set<ChunkId>) {
        val openLock = Any()
        var handle: DemGridHandle? = null
        var users = 0
        var lastUse = 0L
    }

    private val lock = Any()
    private val regions = object : LinkedHashMap<TerrainKey, TerrainRegion>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TerrainKey, TerrainRegion>): Boolean {
            if (size <= MAX_REGIONS) return false
            eldest.value.retired = true
            return true
        }
    }

    // ---- global table state, guarded by [lock] ----
    /** Published tables, least recently used first; capped at [MAX_READY_TABLES] by [trimReadyLocked]. */
    private val ready = LinkedHashMap<TableKey, TerrainTable>(64, 0.75f, true)
    private val staged = HashMap<TableKey, TerrainTable>()
    private val failed = HashSet<TableKey>()
    /** Queued or running builds and the visible tiles that want each. */
    private val pending = HashMap<TableKey, Pending>()
    /** Tables whose home chunks are missing, with the tiles that wanted them. */
    private val waiting = HashMap<TableKey, Pending>()
    private var useTick = 0L

    private class Pending(val want: Want, val tiles: MutableSet<TileAddress>)

    private val homes = HashMap<Pair<Int, Int>, Home>()
    /** Home chunk-set ids by home tile (computing a chunk set per carrier would be slow). */
    private val homeIds = ConcurrentHashMap<Pair<Int, Int>, Long>()

    private val _epoch = MutableStateFlow(0L)

    /** Changes whenever any region's version does; the tile URL carries it. */
    val epoch: StateFlow<Long> get() = _epoch

    @Volatile private var context: Context? = null
    @Volatile private var view: View? = null
    private var settleJob: Job? = null
    private var autoJob: Job? = null
    private val bumpLock = Any()
    private var lastBump = Long.MIN_VALUE
    private var bumpJob: Job? = null
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

    /** The region if it is still kept (a render in flight must not resurrect one that trim or the LRU dropped). */
    fun existingRegion(k: TerrainKey): TerrainRegion? = synchronized(lock) { regions[k] }

    val regionCount: Int get() = synchronized(lock) { regions.size }

    /** Tables published and still in the global LRU (test hook). */
    internal val readyCount: Int get() = synchronized(lock) { ready.size }

    private fun regionLocked(k: TerrainKey): TerrainRegion = regions.getOrPut(k) {
        val bounds = TileMath.bounds(k.zf, k.rx, k.ry)
        val tr = TerrainRegion(k, bounds, ChunkMath.chunksFor(bounds, MARGIN_KM))
        if (tr.chunks.all(support.chunks::has)) markAvailableLocked(tr)
        tr
    }

    /** Caller holds [lock]. True if the version changed. A region becoming available starts with every published table in reach. */
    private fun markAvailableLocked(tr: TerrainRegion): Boolean {
        tr.available = true
        val tables = HashMap(tr.snapshot.tables)
        for ((key, t) in ready) if (key.stamp == tr.key.stamp && inReach(tr, key)) tables.putIfAbsent(key, t)
        return setSnapshotLocked(tr, tables)
    }

    private fun inReach(tr: TerrainRegion, key: TableKey) = tr.reach.contains(key.latE5 / E5, key.lonE5 / E5)

    private fun setSnapshotLocked(tr: TerrainRegion, tables: Map<TableKey, TerrainTable>): Boolean {
        val v = if (!tr.available) 0L else versionHash(tr, tables.keys)
        val changed = v != tr.snapshot.version
        tr.snapshot = TerrainSnapshot(v, if (tr.available) tables else emptyMap())
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
        val now: Boolean = synchronized(bumpLock) {
            val t = support.clock()
            if (lastBump == Long.MIN_VALUE || t - lastBump >= support.minBumpMs) {
                lastBump = t
                // This bump covers every change so far, including the one a waiting deferred bump was for.
                bumpJob?.cancel()
                bumpJob = null
                _epoch.update { it + 1 }
                true
            } else {
                if (bumpJob == null) bumpJob = scheduleBump(lastBump + support.minBumpMs - t)
                false
            }
        }
        if (now) onChange()
    }

    /** Caller holds [bumpLock]. The deferred bump of the window that ends in [waitMs]; [bumpJob] is it until it fires or is absorbed. */
    private fun scheduleBump(waitMs: Long): Job {
        val job = support.scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            delay(waitMs)
            var fire = false
            synchronized(bumpLock) {
                if (bumpJob === self) {
                    bumpJob = null
                    lastBump = support.clock()
                    _epoch.update { it + 1 }
                    fire = true
                }
            }
            if (fire) onChange()
        }
        job.invokeOnCompletion { synchronized(bumpLock) { if (bumpJob === job) bumpJob = null } }
        job.start()
        return job
    }

    fun snapshot(tr: TerrainRegion): TerrainSnapshot = synchronized(lock) { tr.snapshot }

    /**
     * The terrain tables the kept region [k] has published, as a lookup by (carrier batch, row); null when the region is
     * not kept (never created here) or has no tables. Creates and loads nothing.
     */
    fun tableLookup(k: TerrainKey?): ((CarrierBatch, Int) -> TerrainTable?)? {
        if (k == null) return null
        val snap = synchronized(lock) { regions[k]?.snapshot } ?: return null
        if (snap.tables.isEmpty()) return null
        return { b, row -> tableKey(k.stamp, b, row)?.let { snap.tables[it] } }
    }

    /** The table key of batch row [row] for tower extract [stamp], or null for a carrier without a usable frequency or position. */
    fun tableKey(stamp: String, b: CarrierBatch, row: Int): TableKey? {
        val f = b.freqMhz[row]
        val lat = b.siteLat[row]
        val lon = b.siteLon[row]
        if (!f.isFinite() || f <= 0f || !lat.isFinite() || !lon.isFinite()) return null
        val home = TileMath.locate(lat, lon, TERRAIN_ZOOM) ?: return null
        val demId = homeIds.getOrPut(home.x to home.y) { chunkSetId(homeChunks(home.x, home.y)) }
        return TableKey(
            stamp, (lat * E5).roundToInt(), (lon * E5).roundToInt(), TerrainKeys.frequencyKhz(f), TerrainKeys.heightM(b.heightM[row]), demId,
        )
    }

    /**
     * Batch row [row]'s terrain table now: published, on disk, or built here (blocking, about 20 ms). Null while its
     * home elevation is not stored. For one-off point work (a site's footprint), outside the map's demand queue.
     */
    fun tableNow(stamp: String, b: CarrierBatch, row: Int): TerrainTable? {
        val key = tableKey(stamp, b, row) ?: return null
        synchronized(lock) { ready[key] ?: staged[key] }?.let { return it }
        support.tables.get(key)?.let { return it }
        if (!homeStored(b.siteLat[row].toDouble(), b.siteLon[row].toDouble())) return null
        return build(Want(key, b.siteLat[row].toDouble(), b.siteLon[row].toDouble(), true), CancelToken())
    }

    /** The elevation chunks a site at [lat], [lon] needs for its tables that are not stored yet. */
    fun missingHomeChunks(lat: Double, lon: Double): Set<ChunkId> {
        val at = TileMath.locate(lat, lon, TERRAIN_ZOOM) ?: return emptySet()
        return homeChunks(at.x, at.y).filterTo(HashSet()) { !support.chunks.has(it) }
    }

    private fun homeStored(lat: Double, lon: Double) = missingHomeChunks(lat, lon).isEmpty()

    /** Downloads [chunks] when the network rules allow it without asking; false if not allowed or failed. */
    suspend fun fetchQuietly(chunks: Set<ChunkId>): Boolean {
        if (chunks.isEmpty()) return true
        if (!support.network.isOnline() || support.network.isMetered()) return false
        return try {
            support.source.fetch(chunks, false) {}
            onChunksChanged()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun homeChunks(rx: Int, ry: Int) = ChunkMath.chunksFor(TileMath.bounds(TERRAIN_ZOOM, rx, ry), MARGIN_KM)

    /** Caller holds [lock]. */
    private fun homeOfLocked(key: TableKey): Home {
        val at = TileMath.locate(key.latE5 / E5, key.lonE5 / E5, TERRAIN_ZOOM)!!
        return homes.getOrPut(at.x to at.y) { Home(at.x, at.y, homeChunks(at.x, at.y)) }
    }

    // ---- demand and table builds ----------------------------------------------------------------------------------

    /**
     * [tile] (visible, z >= 10, rendered with terrain) wants [wants], most pixels first. Queues a build for every key
     * that is not published, staged, pending or failed (contenders at [RenderPool.PRIORITY_TABLE], the rest one lower);
     * a key whose home chunks are not stored waits for them. Idempotent: the service calls it for every request of a tile.
     */
    fun demand(tile: TileAddress, tr: TerrainRegion, wants: List<Want>) {
        val todo = ArrayList<Want>()
        var published = false
        var fetch = false
        var lost = false
        synchronized(lock) {
            if (tr.retired || !tr.available) return
            if (!tr.chunks.all(support.chunks::has)) {
                // Evicted since the region was checked: quick-only again (version 0) rather than half terrain.
                tr.available = false
                lost = setSnapshotLocked(tr, emptyMap())
            } else {
                val snap = tr.snapshot.tables
                for (w in wants) {
                    val k = w.key
                    if (k in snap || k in staged || k in failed) continue
                    if (ready.containsKey(k)) continue // published after this tile's snapshot: the next version has it
                    pending[k]?.let { it.tiles += tile; continue }
                    val homeStored = homeOfLocked(k).chunks.all(support.chunks::has)
                    waiting[k]?.let { if (homeStored) waiting.remove(k) else { it.tiles += tile; continue } }
                    if (!homeStored) {
                        waiting[k] = Pending(w, hashSetOf(tile))
                        fetch = true
                        continue
                    }
                    pending[k] = Pending(w, hashSetOf(tile))
                    todo += w
                }
            }
            // Tables staged when a pan cancelled the rest are published once a tile asks again and nothing is in flight.
            if (todo.isEmpty() && pending.isEmpty() && staged.isNotEmpty()) published = publishLocked()
        }
        if (published || lost) bumped()
        for (w in todo) submit(w, tile)
        if (fetch) autoCache()
    }

    private fun submit(w: Want, tile: TileAddress) {
        pool.submit(
            tile, if (w.contender) RenderPool.PRIORITY_TABLE else RenderPool.PRIORITY_TABLE_LOW,
            work = { cancel -> runJob(w, tile, cancel) },
            onCancelled = { finishJob(w, tile, null, failed = false, cancelled = true) },
        )
    }

    /** Runs on a pool thread. */
    private fun runJob(w: Want, tile: TileAddress, cancel: CancelToken) {
        var table: TerrainTable? = null
        var failed = true // until the body completes: an Error must not look like a cancellation
        var cancelled = false
        try {
            if (!cancel.isCancelled) {
                table = support.tables.get(w.key)
                if (table == null) table = build(w, cancel)
            }
            cancelled = table == null && cancel.isCancelled
            failed = false
        } catch (e: CancellationException) {
            failed = false // a cancellation is not a failure of the table: it is asked for again
            cancelled = true
        } catch (e: InterruptedException) {
            failed = false
            cancelled = true
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            warn("terrain table ${w.key.fileName} failed: $e")
        } finally {
            finishJob(w, tile, table, failed, cancelled)
        }
    }

    /** Builds [w]'s table from its home mosaic; null when cancelled or when the home lost chunks (then it waits for them). */
    private fun build(w: Want, cancel: CancelToken): TerrainTable? {
        val home = synchronized(lock) { homeOfLocked(w.key).also { it.users++ } }
        try {
            val dem = synchronized(home.openLock) {
                home.handle ?: run {
                    val h = DemGridHandle.open(support.chunks, home.chunks)
                    if (!h.isComplete) {
                        h.close()
                        null
                    } else {
                        home.handle = h
                        h
                    }
                }
            }
            if (dem == null) {
                lostChunks(w)
                return null
            }
            if (cancel.isCancelled) return null
            val t = renderer.buildTerrainTable(dem, w.lat, w.lon, w.key.hkey.toFloat(), w.key.fkey / 1000f, cancel)
            if (t != null) support.tables.put(w.key, t)
            return t
        } finally {
            synchronized(lock) {
                home.users--
                home.lastUse = ++useTick
                closeIdleHomesLocked(if (pending.size <= 1) 0 else MAX_IDLE_HOMES)
            }
        }
    }

    /** [w]'s home lost chunks since the check (evicted): the key waits for them, and regions that lost theirs go quick. */
    private fun lostChunks(w: Want) {
        var changed = false
        synchronized(lock) {
            pending[w.key]?.let { waiting[w.key] = Pending(w, it.tiles) }
            for (tr in regions.values) {
                if (tr.available && !tr.chunks.all(support.chunks::has)) {
                    tr.available = false
                    changed = setSnapshotLocked(tr, emptyMap()) || changed
                }
            }
        }
        if (changed) bumped()
    }

    private fun finishJob(w: Want, tile: TileAddress, table: TerrainTable?, failed: Boolean, cancelled: Boolean) {
        var published = false
        var requeue: TileAddress? = null
        synchronized(lock) {
            val p = pending.remove(w.key)
            if (failed) this.failed += w.key
            if (table != null) staged[w.key] = table
            if (cancelled && p != null) {
                // Dropped with a panned-away tile: another visible tile that wants it takes the build over.
                val shown = view?.tiles ?: emptySet()
                requeue = p.tiles.firstOrNull { it in shown }
                if (requeue != null) pending[w.key] = Pending(w, p.tiles.filterTo(HashSet()) { it in shown })
            }
            // A cancellation never publishes: a pan must not change versions.
            if (!cancelled && staged.isNotEmpty() && (pending.isEmpty() || staged.size >= PUBLISH_BATCH)) published = publishLocked()
            if (pending.isEmpty()) closeIdleHomesLocked(0)
        }
        if (published) bumped()
        requeue?.let { submit(w, it) }
    }

    /** Caller holds [lock]: staged tables become published and join the snapshot of every kept region within reach. */
    private fun publishLocked(): Boolean {
        val fresh = HashMap(staged)
        staged.clear()
        ready.putAll(fresh)
        trimReadyLocked()
        var changed = false
        for (tr in regions.values) {
            if (tr.retired || !tr.available) continue
            val add = fresh.filterKeys { it.stamp == tr.key.stamp && it !in tr.snapshot.tables && inReach(tr, it) }
            if (add.isNotEmpty()) changed = setSnapshotLocked(tr, tr.snapshot.tables + add) || changed
        }
        return changed
    }

    /**
     * Caller holds [lock]: evicts down to [MAX_READY_TABLES], least recently used first but tables of on-screen regions
     * last. An evicted table also leaves the snapshots of off-screen regions (their versions change, so their tiles
     * re-render when shown again), so the memory is really freed; on-screen snapshots keep theirs until they leave.
     */
    private fun trimReadyLocked() {
        if (ready.size <= MAX_READY_TABLES) return
        val ctx = context
        val shownKeys = if (ctx == null) emptySet() else view?.tiles?.mapNotNull {
            keyFor(ctx.selectionId, ctx.stamp, ctx.etag, it.first, it.second, it.third)
        }?.toSet() ?: emptySet()
        val onScreen = HashSet<TableKey>()
        for (k in shownKeys) regions[k]?.let { onScreen += it.snapshot.tables.keys }
        val evict = ArrayList<TableKey>()
        val excess = ready.size - MAX_READY_TABLES
        for (k in ready.keys) { if (evict.size >= excess) break; if (k !in onScreen) evict += k }
        for (k in ready.keys) { if (evict.size >= excess) break; if (k in onScreen) evict += k }
        val gone = evict.toHashSet()
        for (k in evict) ready.remove(k)
        for ((rk, tr) in regions) {
            if (rk in shownKeys || tr.snapshot.tables.keys.none { it in gone }) continue
            setSnapshotLocked(tr, tr.snapshot.tables.filterKeys { it !in gone })
        }
    }

    /** Caller holds [lock]: closes the mosaics nobody uses, keeping the [keep] most recently used. */
    private fun closeIdleHomesLocked(keep: Int) {
        val idle = homes.values.filter { it.users == 0 && it.handle != null }.sortedByDescending { it.lastUse }
        for (h in idle.drop(keep)) {
            h.handle?.close()
            h.handle = null
        }
    }

    // ---- viewport: auto-cache ---------------------------------------------------------------------------------------

    /** The viewport changed (main-thread cheap). */
    fun onVisible(tiles: Set<TileAddress>, ctx: Context?) {
        context = ctx
        val old = view
        if (tiles.isEmpty()) {
            view = null
            return
        }
        val z = tiles.maxOf { it.first }
        view = View(tiles.toSet(), z)
        if (old == null || old.tiles != view!!.tiles) {
            settleJob?.cancel()
            settleJob = support.scope.launch {
                delay(support.settleMs)
                autoCache()
            }
        }
    }

    /**
     * Every chunk the view needs that is not stored: the terrain regions of its tiles (a region, the z10 tile + 40 km,
     * only becomes available once *all* its chunks are in, and a z >= 11 viewport is smaller than its z10 tiles), plus
     * the home chunks of the tables its tiles are waiting for.
     */
    private fun missingFor(v: View): Set<ChunkId> {
        val need = HashSet<ChunkId>()
        for ((rx, ry) in v.tiles.filter { it.first == v.z }.map { (z, x, y) -> (x shr (z - TERRAIN_ZOOM)) to (y shr (z - TERRAIN_ZOOM)) }.toSet()) {
            need += ChunkMath.chunksFor(TileMath.bounds(TERRAIN_ZOOM, rx, ry), MARGIN_KM)
        }
        synchronized(lock) {
            for (p in waiting.values) if (p.tiles.any { it in v.tiles }) need += homeOfLocked(p.want.key).chunks
        }
        return need.filterTo(HashSet()) { !support.chunks.has(it) }
    }

    /** Building chunks (rule 5a) the view's z10 regions need that are not stored; empty without building support. */
    private fun buildingsMissingFor(v: View): Set<ChunkId> {
        val b = support.buildings ?: return emptySet()
        val need = HashSet<ChunkId>()
        for ((rx, ry) in v.tiles.filter { it.first == v.z }.map { (z, x, y) -> (x shr (z - TERRAIN_ZOOM)) to (y shr (z - TERRAIN_ZOOM)) }.toSet()) {
            need += au.signalatlas.engine.BuildingGrid.chunksFor(TileMath.bounds(TERRAIN_ZOOM, rx, ry))
        }
        return need.filterTo(HashSet()) { !b.store.has(it) }
    }

    /** The quality setting changed: tile keys change (Quick drops terrain), and Detailed may now fetch over any network. */
    fun onQualityChanged() {
        bumped()
        autoFailed = null
        autoCache()
    }

    /** Building chunks were stored: tile keys near them changed (their buildings version), so MapLibre must refetch. */
    fun onBuildingsChanged() = bumped()

    /** Background fetch of the view's missing chunks: only z >= 10, only on an unmetered network, no prompts. */
    private fun autoCache() {
        val v = view ?: return
        if (v.z < TERRAIN_ZOOM || !support.network.unmetered.value) return
        if (DataSettings.quality == CoverageQuality.QUICK) return
        val dem = missingFor(v)
        val bld = buildingsMissingFor(v)
        // Building chunks are tagged apart so a DEM chunk and a building chunk with the same id never collide.
        val need = dem + bld.map { ChunkId(it.latIdx + BUILDING_TAG, it.lonIdx) }
        if (need.isEmpty() || need == autoFailed) return
        synchronized(this) {
            // A fetch that already covers this view keeps going (a second trigger must not restart it).
            if (autoJob?.isActive == true && autoNeed.containsAll(need)) return
            autoJob?.cancel()
            autoNeed = need
            autoJob = support.scope.launch {
                try {
                    if (dem.isNotEmpty()) support.source.fetch(dem, false) {}
                    if (bld.isNotEmpty()) {
                        try {
                            support.buildings!!.source.fetch(bld, false) {}
                        } finally {
                            onBuildingsChanged()
                        }
                    }
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
     * Memory pressure: drops the table memory LRUs, every mosaic no build is using, and the regions (with their table
     * snapshots) that are not on screen; disk files stay, so they come back cheaply.
     */
    fun trimMemory() {
        support.tables.dropMemory()
        synchronized(lock) {
            ready.clear()
            val ctx = context
            val shown = if (ctx == null) emptySet() else view?.tiles?.mapNotNull {
                keyFor(ctx.selectionId, ctx.stamp, ctx.etag, it.first, it.second, it.third)
            }?.toSet() ?: emptySet()
            val it = regions.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key !in shown) { it.remove(); e.value.retired = true }
            }
            closeIdleHomesLocked(0)
        }
    }

    /** Elevation chunks were stored: regions waiting for them become available, and waiting tables of visible tiles are queued. */
    fun onChunksChanged() {
        var changed = false
        val resume = ArrayList<Pair<Want, TileAddress>>()
        synchronized(lock) {
            for (tr in regions.values) {
                if (!tr.retired && !tr.available && tr.chunks.all(support.chunks::has)) changed = markAvailableLocked(tr) || changed
            }
            val shown = view?.tiles ?: emptySet()
            val it = waiting.entries.iterator()
            while (it.hasNext()) {
                val (k, p) = it.next()
                if (!homeOfLocked(k).chunks.all(support.chunks::has)) continue
                it.remove()
                val tile = p.tiles.firstOrNull { t -> t in shown } ?: continue // asked again when a tile wants it
                pending[k] = Pending(p.want, p.tiles.filterTo(HashSet()) { t -> t in shown })
                resume += p.want to tile
            }
        }
        if (changed) bumped()
        for ((w, t) in resume) submit(w, t)
    }

    fun isOnline(): Boolean = support.network.isOnline()

    /** Bytes [increaseResolution] would download; null while offline, without a view, or below z10. */
    suspend fun estimateBytes(): Long? {
        if (!support.network.isOnline()) return null
        val v = view?.takeIf { it.z >= TERRAIN_ZOOM } ?: return null
        val need = missingFor(v)
        val bld = buildingsMissingFor(v)
        return (if (need.isEmpty()) 0L else support.source.estimateBytes(need)) +
            (if (bld.isEmpty()) 0L else support.buildings!!.source.estimateBytes(bld))
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
                val bld = buildingsMissingFor(v)
                if (need.isEmpty() && bld.isEmpty()) {
                    send(FetchProgress.Done)
                    return@channelFlow
                }
                try {
                    val demBytes = if (need.isEmpty()) 0L else support.source.estimateBytes(need)
                    val bldBytes = if (bld.isEmpty()) 0L else support.buildings!!.source.estimateBytes(bld)
                    send(FetchProgress.Estimating(demBytes + bldBytes))
                    if (!allowMetered && support.network.isMetered()) {
                        send(FetchProgress.Failed("metered"))
                        return@channelFlow
                    }
                    // Progress by bytes over both downloads (elevation first).
                    val total = (demBytes + bldBytes).coerceAtLeast(1L).toFloat()
                    if (need.isNotEmpty()) {
                        support.source.fetch(need, allowMetered) { trySend(FetchProgress.Fetching((it * demBytes / total).coerceIn(0f, 1f))) }
                        onChunksChanged()
                    }
                    if (bld.isNotEmpty()) {
                        try {
                            support.buildings!!.source.fetch(bld, allowMetered) {
                                trySend(FetchProgress.Fetching(((demBytes + it * bldBytes) / total).coerceIn(0f, 1f)))
                            }
                        } finally {
                            onBuildingsChanged()
                        }
                    }
                    if (need.isEmpty() || bld.isNotEmpty()) trySend(FetchProgress.Fetching(1f))
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
        /** Published tables kept in memory (about 100 KiB each, so about 40 MB); see [trimReadyLocked]. */
        const val MAX_READY_TABLES = 400
        const val PUBLISH_BATCH = 64
        /** A carrier serving at least this share of a tile's covered pixels is a contender (its table comes first). */
        const val CONTENDER_SHARE = 0.01
        /** Open home mosaics kept between builds while more are pending (each a few MB). */
        private const val MAX_IDLE_HOMES = 2
        private const val E5 = 100_000.0
        /** Offsets building chunk ids in the auto-cache bookkeeping (never a real latitude index). */
        private const val BUILDING_TAG = 100_000
        private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
        private const val FNV_PRIME = 1099511628211L

        /** A chunk set's id (count in the high word, CRC of the sorted ids below): names a DEM rectangle. */
        fun chunkSetId(chunks: Set<ChunkId>): Long {
            val sorted = chunks.sortedWith(compareBy({ it.latIdx }, { it.lonIdx }))
            val crc = CRC32()
            for (c in sorted) crc.update("${c.latIdx},${c.lonIdx};".toByteArray())
            return crc.value or (sorted.size.toLong() shl 32)
        }
    }
}
