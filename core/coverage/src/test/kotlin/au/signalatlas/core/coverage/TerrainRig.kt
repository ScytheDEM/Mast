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
) {
    val chunkStore = DemChunkStore(chunkDir, { Long.MAX_VALUE })
    val network = FakeNetwork(online, metered)
    val source = FakeSource(chunkStore, network)
    val tableStore = TerrainTableStore(tableDir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val rig = Rig(tmp.newFolder(), threads, towers = towers, renderer = renderer,
        terrain = TerrainSupport(chunkStore, source, tableStore, network, scope, settleMs))
    val service get() = rig.service
    val renderer get() = rig.renderer
    val cache get() = rig.cache

    /** The z10 tile around Sydney the fake carriers sit in. */
    val at = TileMath.locate(-33.87, 151.21, 10)!!
    val tile = Triple(10, at.x, at.y)
    val regionChunks: Set<ChunkId> = ChunkMath.chunksFor(TileMath.bounds(10, at.x, at.y), 40.0)

    init {
        if (chunksPresent) putChunks(regionChunks)
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
