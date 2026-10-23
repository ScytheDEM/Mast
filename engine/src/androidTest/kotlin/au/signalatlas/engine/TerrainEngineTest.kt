package au.signalatlas.engine

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import au.signalatlas.core.geo.ChunkId
import au.signalatlas.core.geo.ChunkMath
import au.signalatlas.core.geo.DemChunk
import au.signalatlas.core.geo.DemChunkStore
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * The terrain stage through JNI on the real `libsaengine.so`: tables built from cached DEM chunks against the Python
 * reference's tables, and the Canberra terrain tile rendered with them. Vectors: engine/host/test/vectors/terrain
 * (packaged as androidTest assets).
 */
class TerrainEngineTest {
    private lateinit var dir: File
    private lateinit var store: DemChunkStore
    private val engine = CoverageEngine()

    @Before fun setUp() {
        dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "terrain-test-${System.nanoTime()}")
        dir.mkdirs()
        store = DemChunkStore(dir, { Long.MAX_VALUE })
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun asset(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).bufferedReader().use { it.readText() }

    private fun base64(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)

    /** The Canberra GLO-90 crop: node (row, col) at ((southIndex + row) / 1200, (westIndex + col) / 1200). */
    private class Crop(val southIndex: Int, val westIndex: Int, val rows: Int, val cols: Int, val h: ShortArray) {
        fun at(absRow: Int, absCol: Int): Short? {
            val r = absRow - southIndex
            val c = absCol - westIndex
            return if (r in 0 until rows && c in 0 until cols) h[r * cols + c] else null
        }
    }

    private fun loadCrop(): Crop {
        val j = JSONObject(asset("dem_canberra_glo90.json"))
        val rows = j.getInt("rows")
        val cols = j.getInt("cols")
        val raw = base64(j.getString("h"))
        assertThat(raw.size).isEqualTo(rows * cols * 2)
        val h = ShortArray(rows * cols)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(h)
        return Crop(j.getInt("southIndex"), j.getInt("westIndex"), rows, cols, h)
    }

    /**
     * Stores the chunks covering [crop] (lat 217..220, lon 1314..1318). Nodes outside the crop are padding zeros: tests
     * compare only cells whose reference profile lies wholly inside the crop, where the padding is never sampled.
     */
    private fun fillStore(crop: Crop): List<ChunkId> {
        val ids = (217..220).flatMap { la -> (1314..1318).map { lo -> ChunkId(la, lo) } }
        for (id in ids) {
            val row0 = id.latIdx * ChunkMath.STEPS - 90 * ChunkMath.NODES_PER_DEGREE
            val col0 = id.lonIdx * ChunkMath.STEPS - 180 * ChunkMath.NODES_PER_DEGREE
            val s = ShortArray(ChunkMath.NODES * ChunkMath.NODES) { k ->
                crop.at(row0 + k / ChunkMath.NODES, col0 + k % ChunkMath.NODES) ?: 0
            }
            store.put(DemChunk(id, s))
        }
        return ids
    }

    private class RefTable(
        val lat: Double, val lon: Double, val freqKey: Int, val hAgl: Int, val codes: ByteArray, val mask: ByteArray,
    ) {
        fun complete(k: Int) = (mask[k shr 3].toInt() shr (k and 7)) and 1 == 1
    }

    private fun refTables(tile: JSONObject): List<RefTable> {
        val arr = tile.getJSONObject("terrain").getJSONArray("tables")
        return (0 until arr.length()).map {
            val t = arr.getJSONObject(it)
            RefTable(
                t.getDouble("siteLat"), t.getDouble("siteLon"), t.getInt("freqKeyMhz"), t.getInt("hTxAgl"),
                base64(t.getString("codes")), base64(t.getString("mask")),
            )
        }
    }

    private fun toTable(r: RefTable): TerrainTable {
        val codes = ByteBuffer.allocateDirect(TerrainTable.CELLS).also { it.put(r.codes); it.rewind() }
        val mask = ByteBuffer.allocateDirect(TerrainTable.MASK_BYTES).also { it.put(r.mask); it.rewind() }
        return TerrainTable(codes, mask)
    }

    @Test fun tablesBuiltFromCachedChunksMatchTheReference() {
        val crop = loadCrop()
        val ids = fillStore(crop)
        val tile = JSONObject(asset("tile_canberra_terrain_z12.json"))
        val refs = refTables(tile)
        assertThat(refs).hasSize(32)
        DemGridHandle.open(store, ids).use { dem ->
            assertThat(dem.isComplete).isTrue()
            var cells = 0L
            var off1 = 0L
            for ((n, ref) in refs.withIndex()) {
                val t = engine.buildTerrainTable(dem, ref.lat, ref.lon, ref.hAgl.toFloat(), ref.freqKey.toFloat(), CancelToken())!!
                var complete = 0
                for (k in 0 until TerrainTable.CELLS) {
                    if (!ref.complete(k)) continue
                    complete++
                    // The reference's complete cells lie wholly inside the crop, so they are complete here too.
                    assertThat((t.mask.get(k shr 3).toInt() shr (k and 7)) and 1).isEqualTo(1)
                    val d = abs((t.table.get(k).toInt() and 0xFF) - (ref.codes[k].toInt() and 0xFF))
                    if (d == 1) off1++
                    assertThat(d).isAtMost(1)
                }
                assertThat(complete).isGreaterThan(20_000)  // the comparison is not vacuous
                cells += complete
            }
            android.util.Log.i("TerrainEngineTest", "32 tables: $cells complete cells compared, $off1 off by one")
            assertThat(off1.toDouble()).isAtMost(0.001 * cells)
        }
    }

    @Test fun terrainTileThroughJniMatchesTheReference() {
        val v = TileVectors.load("tile_canberra_terrain_z12.json")
        val refs = refTables(v.json)
        val tables = refs.map(::toTable)
        val carrierTable = v.json.getJSONObject("terrain").getJSONArray("carrierTable")
        val prepared = engine.prepare(v.batch, null, v.indoor, terrain = { row -> tables[carrierTable.getInt(row)] })
        assertThat(prepared.size).isEqualTo(v.batch.size)
        for (k in 0 until prepared.size) assertThat(prepared.terrainTableAt(k)).isSameInstanceAs(tables[carrierTable.getInt(k)])
        val tile = engine.render(prepared, v.z, v.x, v.y, v.area, v.thr, CancelToken())!!
        TileVectors.assertCodes("rsrp", tile.rsrp, v.codes("rsrpCodes"))
        TileVectors.assertCodes("sinr", tile.sinr, v.codes("sinrCodes"))
        TileVectors.assertCodes("prob", tile.prob, v.codes("probCodes"))
        val refRsrp = v.codes("rsrpCodes")
        val refBest = v.codes("best")
        var apart = 0
        for (k in refRsrp.indices) {
            if (refRsrp[k] != 0 && tile.rsrp[k].toInt() != 0 && tile.bestCarrier(k) != refBest[k]) apart++
        }
        assertThat(apart.toDouble()).isAtMost(0.001 * refRsrp.size)

        // The reference's accuracy bookkeeping: every covered pixel had a full profile -> the TERRAIN label.
        val terrain = v.json.getJSONObject("terrain")
        assertThat(tile.coveredPixels).isEqualTo(terrain.getInt("coveredPixels"))
        assertThat(tile.coveredPixels - tile.terrainIncompletePixels).isAtLeast((0.999 * terrain.getInt("terrainPixels")).toInt())
        assertThat(terrain.getString("accuracy")).isEqualTo("TERRAIN")
        assertThat(tile.isTerrainAccurate).isTrue()

        // Without the tables the same carriers render the quick-model tile, labelled incomplete everywhere.
        val quick = engine.render(engine.prepare(v.batch, null, v.indoor), v.z, v.x, v.y, v.area, v.thr, CancelToken())!!
        var changed = 0
        for (k in refRsrp.indices) if (quick.rsrp[k] != tile.rsrp[k]) changed++
        assertThat(changed).isGreaterThan(30_000)
        assertThat(quick.isTerrainAccurate).isFalse()
        assertThat(quick.terrainIncompletePixels).isEqualTo(quick.coveredPixels)
    }

    @Test fun tablesStayAliveForPreparedCarriersAfterTheCallerDropsThem() {
        val v = TileVectors.load("tile_canberra_terrain_z12.json")
        val refs = refTables(v.json)
        val carrierTable = v.json.getJSONObject("terrain").getJSONArray("carrierTable")
        var tables: List<TerrainTable>? = refs.map(::toTable)
        val prepared = engine.prepare(v.batch, null, v.indoor, terrain = { row -> tables!![carrierTable.getInt(row)] })
        val before = engine.render(prepared, v.z, v.x, v.y, v.area, v.thr, CancelToken())!!
        tables = null
        repeat(3) { System.gc(); System.runFinalization() }
        val after = engine.render(prepared, v.z, v.x, v.y, v.area, v.thr, CancelToken())!!
        assertThat(after.rsrp).isEqualTo(before.rsrp)
        assertThat(after.terrainIncompletePixels).isEqualTo(before.terrainIncompletePixels)
    }

    @Test fun buildCancelsAndRejectsIncompleteOrClosedGrids() {
        val crop = loadCrop()
        val ids = fillStore(crop)
        val cancelled = CancelToken().also { it.cancel() }
        DemGridHandle.open(store, ids).use { dem ->
            assertThat(engine.buildTerrainTable(dem, -35.298383, 149.130877, 27f, 2130f, cancelled)).isNull()
            assertThat(engine.buildTerrainTable(dem, -35.298383, 149.130877, 27f, 2130f, CancelToken())).isNotNull()
            assertThrows(IllegalArgumentException::class.java) {
                engine.buildTerrainTable(dem, Double.NaN, 149.0, 27f, 2130f, CancelToken())
            }
        }
        // A grid with a missing chunk is refused rather than read as zero-height terrain.
        store.clear()
        store.put(DemChunk(ids[0], ShortArray(ChunkMath.NODES * ChunkMath.NODES)))
        val partial = DemGridHandle.open(store, ids.take(5))  // one chunk row: lat 217, lon 1314..1318
        assertThat(partial.isComplete).isFalse()
        assertThrows(IllegalArgumentException::class.java) {
            engine.buildTerrainTable(partial, -35.7, 149.0, 27f, 2130f, CancelToken())
        }
        partial.close()
        assertThrows(IllegalStateException::class.java) {
            engine.buildTerrainTable(partial, -35.7, 149.0, 27f, 2130f, CancelToken())
        }
    }

    @Test fun anOceanChunkOfZerosBuildsAnAllCompleteFlatTable() {
        // A real all-zero chunk (what the fetcher stores for ocean) is data, not a gap: flat sea means no terrain loss.
        val id = ChunkId(300, 1400)
        store.put(DemChunk(id, ShortArray(ChunkMath.NODES * ChunkMath.NODES)))
        DemGridHandle.open(store, listOf(id)).use { dem ->
            assertThat(dem.isComplete).isTrue()
            val t = engine.buildTerrainTable(dem, id.southDeg + 0.125, id.westDeg + 0.125, 20f, 1800f, CancelToken())!!
            var nonZero = 0
            for (k in 0 until TerrainTable.CELLS) if (t.table.get(k).toInt() != 0) nonZero++
            assertThat(nonZero).isEqualTo(0)
            assertThat(t.cellDb(0, 0)).isEqualTo(0f)  // inside the chunk: complete
            assertThat(t.cellDb(0, 255)).isNull()     // 80 km out: outside the 0.25 degree chunk
        }
    }
}
