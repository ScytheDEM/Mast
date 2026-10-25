package au.signalatlas.core.coverage

import au.signalatlas.core.data.CarrierBatch
import au.signalatlas.core.geo.ChunkId
import au.signalatlas.core.geo.ChunkMath
import au.signalatlas.core.geo.DemChunk
import au.signalatlas.core.geo.DemChunkStore
import au.signalatlas.core.geo.MeteredNetworkException
import au.signalatlas.core.model.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

internal class FakeNetwork(online: Boolean = true, metered: Boolean = false) : NetworkMonitor {
    private val _unmetered = MutableStateFlow(online && !metered)
    @Volatile private var online = online
    @Volatile private var metered = metered
    override val unmetered: StateFlow<Boolean> get() = _unmetered
    override fun isOnline() = online
    override fun isMetered() = metered
    fun set(online: Boolean, metered: Boolean) {
        this.online = online
        this.metered = metered
        _unmetered.value = online && !metered
    }
}

/** Stores synthetic flat chunks (100 m) when asked to fetch. */
internal class FakeSource(private val store: DemChunkStore, private val network: FakeNetwork) : ElevationSource {
    val fetches = CopyOnWriteArrayList<Set<ChunkId>>()
    val estimates = CopyOnWriteArrayList<Set<ChunkId>>()
    @Volatile var failWith: Exception? = null
    @Volatile var perChunkBytes = 1000L

    override suspend fun estimateBytes(chunks: Set<ChunkId>): Long {
        estimates += chunks
        return chunks.count { !store.has(it) } * perChunkBytes
    }

    override suspend fun fetch(chunks: Set<ChunkId>, allowMetered: Boolean, progress: (Float) -> Unit) {
        fetches += chunks
        if (!allowMetered && network.isMetered()) throw MeteredNetworkException()
        failWith?.let { throw it }
        val todo = chunks.filter { !store.has(it) }
        todo.forEachIndexed { i, id ->
            store.put(flatChunk(id))
            progress((i + 1f) / todo.size)
        }
    }

    companion object {
        private val FLAT = ShortArray(ChunkMath.NODES * ChunkMath.NODES) { 100 }
        fun flatChunk(id: ChunkId) = DemChunk(id, FLAT)
    }
}

/** Stores empty building chunks when asked to fetch. */
internal class FakeBuildingSource(private val store: au.signalatlas.core.geo.BuildingChunkStore) : BuildingSource {
    val fetches = CopyOnWriteArrayList<Set<ChunkId>>()
    override suspend fun estimateBytes(chunks: Set<ChunkId>) = chunks.count { !store.has(it) } * 10L
    override suspend fun fetch(chunks: Set<ChunkId>, allowMetered: Boolean, progress: (Float) -> Unit) {
        fetches += chunks
        chunks.filter { !store.has(it) }.forEach { store.put(au.signalatlas.core.geo.BuildingChunk.empty(it)) }
        progress(1f)
    }
}

/** A [Rig] with terrain support: real chunk store and table store in temp folders, fake source, network and renderer tables. */
internal class TerrainRig(
    tmp: TemporaryFolder,
    chunksPresent: Boolean = true,
    threads: Int = 2,
    online: Boolean = true,
    metered: Boolean = false,
    val chunkDir: File = tmp.newFolder(),
    val tableDir: File = tmp.newFolder(),
    towers: FakeTowers = FakeTowers(),
    renderer: FakeRenderer = FakeRenderer(),
    settleMs: Long = 20,
    minBumpMs: Long = 0,
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
    withBuildings: Boolean = false,
) {
    val chunkStore = DemChunkStore(chunkDir, { Long.MAX_VALUE })
    val network = FakeNetwork(online, metered)
    val source = FakeSource(chunkStore, network)
    val tableStore = TerrainTableStore(tableDir)
    val buildingStore = au.signalatlas.core.geo.BuildingChunkStore(tmp.newFolder(), { Long.MAX_VALUE })
    val buildingSource = FakeBuildingSource(buildingStore)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val rig = Rig(tmp.newFolder(), threads, towers = towers, renderer = renderer,
        terrain = TerrainSupport(chunkStore, source, tableStore, network, scope, settleMs, minBumpMs, clock,
            if (withBuildings) BuildingSupport(buildingStore, buildingSource) else null))
    val service get() = rig.service
    val renderer get() = rig.renderer
    val cache get() = rig.cache

    /** The z10 tile around Sydney the fake carriers sit in. */
    val at = TileMath.locate(-33.87, 151.21, 10)!!
    val tile = Triple(10, at.x, at.y)
    val regionChunks: Set<ChunkId> = ChunkMath.chunksFor(TileMath.bounds(10, at.x, at.y), 40.0)

    init {
        if (chunksPresent) putChunks(regionChunks)
        renderer.spread = true // every carrier serves a share of each tile, so each is a contender
    }

    fun putChunks(ids: Collection<ChunkId>) = ids.forEach { chunkStore.put(FakeSource.flatChunk(it)) }

    fun key(z: Int = tile.first, x: Int = tile.second, y: Int = tile.third) = service.currentKey(z, x, y)!!

    /**
     * Renders the tile, asking again when the terrain data moved on while it rendered (the render is then cancelled
     * and the URL stale, exactly what makes MapLibre refetch).
     */
    fun render(z: Int = tile.first, x: Int = tile.second, y: Int = tile.third): ByteArray {
        repeat(10) {
            try {
                return rig.tile(z, x, y).get(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.ExecutionException) {
                if (e.cause !is TileCancelledException && e.cause !is StaleTileException) throw e
            }
        }
        error("tile $z/$x/$y kept going stale")
    }

    /** Waits until the terrain epoch passes [than]. */
    fun awaitEpochAbove(than: Long) = waitUntil(what = "terrain epoch > $than") { service.terrainEpoch.value > than }

    fun close() {
        rig.close()
        scope.cancel()
    }
}

/** [n] carriers around the Sydney tile, one site each, with the given frequencies and heights. */
fun sydneyBatch(freqs: FloatArray, heights: FloatArray, sameSite: Boolean = false): CarrierBatch {
    val n = freqs.size
    return CarrierBatch(
        n, DoubleArray(n) { -33.87 + (if (sameSite) 0 else it) * 0.01 }, DoubleArray(n) { 151.21 + (if (sameSite) 0 else it) * 0.01 },
        heights, FloatArray(n) { 0f }, FloatArray(n) { 0f }, FloatArray(n) { 60f }, freqs, IntArray(n) { 10_000_000 },
        ByteArray(n) { 1 }, ByteArray(n) { Network.TELSTRA.ordinal.toByte() }, FloatArray(n) { 65f }, FloatArray(n) { 25f },
        FloatArray(n) { 15f }, IntArray(n) { it }, LongArray(n) { it.toLong() }, ByteArray(n), listOf("B28"),
    )
}

/**
 * A dense city-centre carrier set around the Sydney tile (the Canberra CBD shape, Ruling UU): [sites] sites within about
 * 1.5 km, three sectors each, every sector carrying the same 3 operators x 7 band carriers at different frequencies inside
 * their bands; sector heights are the site's base height (18-45 m) plus 0, 0 and 3 m.
 */
fun cbdBatch(sites: Int = 8): CarrierBatch {
    val bands = listOf(
        listOf(763f, 773f, 788f), listOf(875f, 880f, 890f), listOf(1815f, 1840f, 1865f), listOf(2125f, 2140f, 2160f),
        listOf(2310f, 2350f, 2380f), listOf(2630f, 2650f, 2680f), listOf(3425f, 3510f, 3690f),
    )
    class Row(val site: Int, val sector: Int, val f: Float, val h: Float, val op: Int)
    val rows = ArrayList<Row>()
    for (s in 0 until sites) for (sec in 0 until 3) for ((b, fs) in bands.withIndex()) for ((op, f) in fs.withIndex()) {
        rows += Row(s, sec, f, 18f + (s * 7) % 28 + floatArrayOf(0f, 0f, 3f)[sec] + 0.3f * b, op)
    }
    val n = rows.size
    return CarrierBatch(
        n, DoubleArray(n) { -33.87 + 0.004 * ((rows[it].site % 5) - 2) }, DoubleArray(n) { 151.21 + 0.004 * ((rows[it].site / 5) - 1) },
        FloatArray(n) { rows[it].h }, FloatArray(n) { rows[it].sector * 120f }, FloatArray(n) { 0f }, FloatArray(n) { 60f },
        FloatArray(n) { rows[it].f }, IntArray(n) { 10_000_000 }, ByteArray(n) { 1 }, ByteArray(n) { rows[it].op.toByte() },
        FloatArray(n) { 65f }, FloatArray(n) { 25f }, FloatArray(n) { 15f }, IntArray(n) { it }, LongArray(n) { it.toLong() },
        ByteArray(n), listOf("B28"),
    )
}
