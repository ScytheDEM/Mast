package au.signalatlas.core.geo

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Fetches the real S36 E148 / S36 E149 GLO-90 COGs (about 3.8 MB each) and compares every node that falls inside the
 * committed Canberra crop (engine/host/test/vectors/terrain/dem_canberra_glo90.json, made by the Python reference).
 * Run with `GEO_LIVE=1 ./gradlew :core:geo:testDebugUnitTest --tests '*DemFetcherLiveTest*'`.
 */
@RunWith(RobolectricTestRunner::class)
class DemFetcherLiveTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `real chunks around Canberra match the committed crop`() {
        assumeTrue("set GEO_LIVE=1 to run against the real bucket", System.getenv("GEO_LIVE") == "1")
        val cropFile = File("../../engine/host/test/vectors/terrain/dem_canberra_glo90.json")
        assertThat(cropFile.isFile).isTrue()
        val crop = JSONObject(cropFile.readText())
        val rows = crop.getInt("rows")
        val cols = crop.getInt("cols")
        val south = crop.getInt("southIndex")
        val west = crop.getInt("westIndex")
        val h = ByteBuffer.wrap(Base64.getDecoder().decode(crop.getString("h"))).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        // Latitude -35.5..-35.0, longitude 148.75..149.25: the chunk edges on 149.0 E and -35.0 are whole-degree nodes.
        val ids = setOf(ChunkId(218, 1315), ChunkId(218, 1316), ChunkId(219, 1315), ChunkId(219, 1316))
        val store = DemChunkStore(File(tmp.root, "geo/dem90"), { Long.MAX_VALUE })
        val fetcher = DemFetcher(OkHttpClient(), store)
        val estimate = runBlocking { fetcher.estimateBytes(ids) }
        println("GEO_LIVE estimate: $estimate bytes")
        assertThat(estimate).isGreaterThan(1_000_000L)
        runBlocking { fetcher.fetch(ids, allowMetered = true) { } }

        var compared = 0
        for (id in ids) {
            val chunk = store.get(id)!!
            for (r in 0 until ChunkMath.NODES) {
                for (c in 0 until ChunkMath.NODES) {
                    val latNode = id.latIdx * ChunkMath.STEPS - 90 * 1200 + r
                    val lonNode = id.lonIdx * ChunkMath.STEPS - 180 * 1200 + c
                    val cr = latNode - south
                    val cc = lonNode - west
                    if (cr !in 0 until rows || cc !in 0 until cols) continue
                    assertThat(chunk.at(r, c)).isEqualTo(h.get(cr * cols + cc).toInt())
                    compared++
                }
            }
        }
        println("GEO_LIVE compared $compared nodes")
        assertThat(compared).isGreaterThan(300_000)
        assertThat(store.bytesUsed.value).isGreaterThan(0L)
    }
}
