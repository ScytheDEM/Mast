package au.signalatlas.core.coverage

import au.signalatlas.core.data.CarrierBatch
import au.signalatlas.core.data.PatternStore
import au.signalatlas.core.model.GeoBounds
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import au.signalatlas.engine.AreaClass
import au.signalatlas.engine.CancelToken
import au.signalatlas.engine.DemGridHandle
import au.signalatlas.engine.TerrainTable
import au.signalatlas.engine.RenderedTile
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

fun waitUntil(timeoutMs: Long = 5000, what: String = "condition", cond: () -> Boolean) {
    val end = System.nanoTime() + timeoutMs * 1_000_000
    while (!cond()) {
        check(System.nanoTime() < end) { "timed out waiting for $what" }
        Thread.sleep(5)
    }
}

/** Two carriers: row 0 Telstra "n78" at (-33.9, 151.2), row 1 Optus "B28" at (-33.8, 151.3). */
fun fakeBatch(): CarrierBatch = CarrierBatch(
    size = 2,
    siteLat = doubleArrayOf(-33.9, -33.8), siteLon = doubleArrayOf(151.2, 151.3),
    heightM = floatArrayOf(30f, 30f), azimuthDeg = floatArrayOf(0f, 90f), tiltDeg = floatArrayOf(0f, 0f),
    eirpDbm = floatArrayOf(60f, 60f), freqMhz = floatArrayOf(3500f, 700f), bandwidthHz = intArrayOf(100_000_000, 10_000_000),
    tech = byteArrayOf(1, 0), network = byteArrayOf(Network.TELSTRA.ordinal.toByte(), Network.OPTUS.ordinal.toByte()),
    hBeamwidthDeg = floatArrayOf(65f, 65f), frontToBackDb = floatArrayOf(25f, 25f), gainDbi = floatArrayOf(15f, 15f),
    sectorId = intArrayOf(1, 2), sddId = longArrayOf(11, 12), bandIdx = byteArrayOf(0, 1), bandLabels = listOf("n78", "B28"),
)

/** [n] carriers around (-33.87, 151.21); every [farEvery]th (0 = none) is ~550 km north. Ids make rows distinguishable. */
fun bigBatch(n: Int, farEvery: Int = 0): CarrierBatch {
    val lat = DoubleArray(n) { if (farEvery > 0 && it % farEvery == 0) -29.0 else -33.87 + (it % 100) * 1e-4 }
    return CarrierBatch(
        n, lat, DoubleArray(n) { 151.21 + (it % 97) * 1e-4 }, FloatArray(n) { 30f }, FloatArray(n) { 0f }, FloatArray(n) { 0f },
        FloatArray(n) { 60f }, FloatArray(n) { 700f }, IntArray(n) { 10_000_000 }, ByteArray(n), ByteArray(n),
        FloatArray(n) { 65f }, FloatArray(n) { 25f }, FloatArray(n) { 15f }, IntArray(n) { it }, LongArray(n) { it.toLong() },
        ByteArray(n), listOf("B28"),
    )
}

internal class FakeTowers(var stampValue: String? = "stamp1", var sites: Int = 0) : TowerData {
    /** Overrides the batch returned for a query. */
    var batchFor: (GeoBounds) -> CarrierBatch = { fakeBatch() }
    val queries = CopyOnWriteArrayList<GeoBounds>()
    val networkQueries = CopyOnWriteArrayList<Set<Network>>()
    override fun stamp() = stampValue
    override fun <T> withTowers(block: (Towers) -> T): T? {
        val s = stampValue ?: return null
        return block(object : Towers {
            override val stamp = s
            override fun carriersIn(b: GeoBounds, networks: Set<Network>, techs: Set<Tech>, bands: Set<String>?): CarrierBatch {
                queries += b
                networkQueries += networks
                return batchFor(b)
            }
            override fun siteCountWithin(lat: Double, lon: Double, radiusM: Double) = sites
        })
    }
}

class FakeRenderer : TileRenderer {
    class Rendered(val z: Int, val x: Int, val y: Int, val area: AreaClass, val cancel: CancelToken)

    val prepares = AtomicInteger()
    val preparedSizes = CopyOnWriteArrayList<Int>()
    val renders = CopyOnWriteArrayList<Rendered>()
    private val concurrent = AtomicInteger()
    @Volatile var maxConcurrent = 0
    /** While true a render spins until released or cancelled. */
    @Volatile var hold = false
    @Volatile var rsrpCode: Byte = 100
    @Volatile var bestIndex: Short = 1

    /** Tables handed to each prepared region, as (batch row, table). */
    val preparedTables = CopyOnWriteArrayList<List<Pair<Int, TerrainTable>>>()

    override fun prepare(
        batch: CarrierBatch, patterns: PatternStore?, indoor: Boolean, terrain: ((batchRow: Int) -> TerrainTable?)?,
    ): PreparedRegion {
        prepares.incrementAndGet()
        preparedSizes += batch.size
        require(batch.size <= 65535) { "too many carriers" }
        val tables = if (terrain == null) emptyList() else (0 until batch.size).mapNotNull { r -> terrain(r)?.let { r to it } }
        if (terrain != null) preparedTables += tables
        // Carrier i of the prepared region = batch row (size - 1 - i): proves batchIndex is used.
        return object : FakePrepared {
            override val tableCount = tables.size
            override val carriers = batch.size
            override fun batchIndex(i: Int) = batch.size - 1 - i
        }
    }

    interface FakePrepared : PreparedRegion {
        val tableCount: Int
        val carriers: Int
    }

    class Build(
        val dem: DemGridHandle, val lat: Double, val lon: Double, val hTx: Float, val freq: Float, val cancel: CancelToken,
        val table: TerrainTable,
    )

    val builds = CopyOnWriteArrayList<Build>()
    /** Every call, also cancelled ones: (freq, hTx). */
    val buildCalls = CopyOnWriteArrayList<Pair<Float, Float>>()
    /** While true a table build spins until released or cancelled. */
    @Volatile var holdBuilds = false
    /** The table returned for (freq, hTx). */
    val tableFor = java.util.concurrent.ConcurrentHashMap<Pair<Float, Float>, TerrainTable>()

    override fun buildTerrainTable(
        dem: DemGridHandle, siteLat: Double, siteLon: Double, hTxAgl: Float, freqMhz: Float, cancel: CancelToken,
    ): TerrainTable? {
        require(dem.isComplete) { "incomplete DEM" }
        buildCalls += freqMhz to hTxAgl
        while (holdBuilds && !cancel.isCancelled) Thread.sleep(2)
        if (cancel.isCancelled) return null
        val t = TerrainTable(java.nio.ByteBuffer.allocateDirect(TerrainTable.CELLS), java.nio.ByteBuffer.allocateDirect(TerrainTable.MASK_BYTES))
        tableFor[freqMhz to hTxAgl] = t
        builds += Build(dem, siteLat, siteLon, hTxAgl, freqMhz, cancel, t)
        return t
    }

    override fun render(
        prepared: PreparedRegion, z: Int, x: Int, y: Int, area: AreaClass, thrDbm: Float, cancel: CancelToken,
    ): RenderedTile? {
        renders += Rendered(z, x, y, area, cancel)
        val now = concurrent.incrementAndGet()
        synchronized(this) { maxConcurrent = maxOf(maxConcurrent, now) }
        try {
            while (hold && !cancel.isCancelled) Thread.sleep(2)
            if (cancel.isCancelled) return null
            val n = RenderedTile.PIXELS
            // Like the engine: pixels are "incomplete" for the carriers that have no table (carriers share pixels equally here).
            val p = prepared as? FakePrepared
            val covered = if (rsrpCode.toInt() == 0) 0 else n
            val incomplete = if (p == null || p.carriers == 0) covered else covered * (p.carriers - p.tableCount) / p.carriers
            return RenderedTile(
                z, x, y, ByteArray(n) { rsrpCode }, ByteArray(n) { 80 }, ByteArray(n) { 51 }, ShortArray(n) { bestIndex },
                covered, incomplete,
            )
        } finally {
            concurrent.decrementAndGet()
        }
    }
}

internal class Rig(
    dir: File,
    threads: Int = 2,
    memoryTiles: Int = 96,
    val towers: FakeTowers = FakeTowers(),
    val renderer: FakeRenderer = FakeRenderer(),
    val warnings: MutableList<String> = CopyOnWriteArrayList(),
    terrain: TerrainSupport? = null,
) {
    val cache = TileCache(dir, memoryTiles = memoryTiles)
    val pool = RenderPool({ threads })
    val service = CoverageTileService(towers, MutableStateFlow(null), renderer, cache, pool, warnings::add, terrain)

    val selection = CoverageSelection(Network.TELSTRA, setOf(Tech.LTE, Tech.NR), null, false, CoverageSelection.View.RSRP)

    init {
        service.selection.value = selection
    }

    fun url(z: Int, x: Int, y: Int, view: CoverageSelection.View = selection.view, stamp: String = towers.stampValue!!): String =
        "https://${CoverageTileService.HOST}/v1/${selection.cacheId()}/${view.segment}/$stamp/none/t${service.terrainEpoch.value}/$z/$x/$y.png"

    fun tile(z: Int, x: Int, y: Int, view: CoverageSelection.View = selection.view, stamp: String = towers.stampValue!!) =
        TileUrl.parse(url(z, x, y, view, stamp).toHttpUrl())!!.let(service::tileFuture)

    fun close() = pool.shutdown()
}
