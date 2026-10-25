package au.signalatlas.core.geo

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import kotlin.math.cos

@RunWith(RobolectricTestRunner::class)
class BuildingDataTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val files = HashMap<String, ByteArray>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @After fun tearDown() = server.close()

    private fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val body = files[request.url.encodedPath] ?: return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().code(200).body(Buffer().write(body)).build()
            }
        }
        server.start()
    }

    /** A footprint line in the dataset's own spacing: a [sideM] square with its south-west corner at (lat, lon). */
    private fun line(lat: Double, lon: Double, sideM: Double, height: Double): String {
        val dLat = sideM / 111195.08
        val dLon = sideM / (111195.08 * cos(Math.toRadians(lat)))
        val ring = listOf(lon to lat, lon + dLon to lat, lon + dLon to lat + dLat, lon to lat + dLat, lon to lat)
            .joinToString(", ") { "[${it.first}, ${it.second}]" }
        return """{"type": "Feature", "properties": { "height": $height, "confidence": -1.0},"geometry": {"type": "Polygon","coordinates": [[$ring]]}}"""
    }

    private fun gz(lines: List<String>): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(lines.joinToString("\n").toByteArray()) }
        return bos.toByteArray()
    }

    @Test fun chunkFilesRoundTripAndCorruptionIsAMiss() {
        val id = ChunkId(219, 1316)
        val c = BuildingChunk(id, ByteArray(625) { (it % 251).toByte() }, ByteArray(625) { (it % 7).toByte() })
        assertThat(BuildingChunk.decode(c.encode())).isEqualTo(c)
        val bad = c.encode().also { it[40] = (it[40] + 1).toByte() }
        assertThat(BuildingChunk.decode(bad)).isNull()
        val store = BuildingChunkStore(tmp.newFolder("bld"), { Long.MAX_VALUE })
        store.put(c)
        assertThat(store.get(id)).isEqualTo(c)
        assertThat(store.dir.list()!!.toList()).containsExactly("219_1316.bld")
        assertThat(BuildingChunkStore(store.dir, { Long.MAX_VALUE }).has(id)).isTrue()
    }

    @Test fun theStoreSharesTheDemLruAndPinRules() {
        val size = BuildingChunk.empty(ChunkId(0, 0)).encode().size.toLong()
        val store = BuildingChunkStore(tmp.newFolder("bld"), { size * 2 })
        store.pin("pack", setOf(ChunkId(1, 1)))
        store.put(BuildingChunk.empty(ChunkId(1, 1)))
        store.put(BuildingChunk.empty(ChunkId(1, 2)))
        store.put(BuildingChunk.empty(ChunkId(1, 3)))
        assertThat(store.has(ChunkId(1, 1))).isTrue() // pinned
        assertThat(store.has(ChunkId(1, 2))).isFalse() // least recently used
        assertThat(store.has(ChunkId(1, 3))).isTrue()
    }

    @Test fun parserReadsTheDatasetLineFormat() {
        // Verbatim shape of a 2026-02 line from quadkey 311230302 (Canberra).
        val f = FootprintParser.parse(
            """{"type": "Feature", "properties": { "height": 6.715297698974609, "confidence": -1.0},"geometry": {"type": "Polygon","coordinates": [[[149.0611259448785, -35.23917282938881], [149.0611466660028, -35.23925477105536], [149.0615736966344, -35.23918273564828], [149.06155297551, -35.23910079390893], [149.0611259448785, -35.23917282938881]]]}}""",
        )!!
        assertThat(f.ring.size).isEqualTo(10)
        assertThat(f.ring[0]).isEqualTo(149.0611259448785)
        assertThat(f.ring[1]).isEqualTo(-35.23917282938881)
        assertThat(f.effectiveHeightM).isWithin(1e-9).of(6.715297698974609)
        assertThat(FootprintParser.parse("""{"type": "Feature", "properties": { "height": -1.0}, "geometry": {"type": "Polygon","coordinates": [[[1, 2], [3, 4], [5, 6]]]}}""")!!.effectiveHeightM).isEqualTo(6.0)
        assertThat(FootprintParser.parse("""{"properties": {"height": -1, "building:levels": "4"}, "geometry": {"coordinates": [[[1, 2], [3, 4], [5, 6]]]}}""")!!.effectiveHeightM).isEqualTo(12.0)
        assertThat(FootprintParser.parse("not json")).isNull()
        assertThat(FootprintParser.parse("")).isNull()
    }

    @Test fun rasterisingGivesCoverageFractionAndAreaWeightedMeanHeight() {
        val r = BuildingRasterizer()
        val lat = -35.2895
        val lon = 149.1305 // cell (row of -35.29..-35.28, col of 149.13..149.14)
        for (line in listOf(line(lat, lon, 100.0, 20.0), line(lat + 0.001, lon + 0.001, 100.0, -1.0), line(lat + 0.003, lon, 200.0, 10.0))) {
            r.add(FootprintParser.parse(line)!!)
        }
        val id = ChunkMath.idFor(lat, lon)
        val c = r.chunk(id)
        val row = ((lat + 90.0) * 100).toInt() - id.latIdx * 25
        val col = ((lon + 180.0) * 100).toInt() - id.lonIdx * 25
        val cellM2 = 1111.9508 * 1111.9508 * cos(Math.toRadians(-35.285))
        assertThat(c.coverAt(row, col)!!.toDouble()).isWithin(0.004).of(60_000.0 / cellM2)
        assertThat(c.heightAt(row, col).toDouble()).isWithin(0.26).of((10_000 * 20.0 + 10_000 * 6.0 + 40_000 * 10.0) / 60_000)
        assertThat(c.coverAt(row, col + 1)).isEqualTo(0f) // data, no buildings
        assertThat(c.heightAt(row, col + 1)).isEqualTo(0f)
    }

    @Test fun quadkeysFollowTheBingTileSystem() {
        assertThat(Quadkey.of(Quadkey.tileX(149.13), Quadkey.tileY(-35.28))).isEqualTo("311230302")
        // A chunk straddling a z9 tile edge touches two quadkeys.
        assertThat(Quadkey.forChunk(ChunkMath.idFor(-35.28, 149.13))).contains("311230302")
        assertThat(BuildingFetcher.parseSize("13.0MB")).isEqualTo(13L * 1024 * 1024)
        assertThat(BuildingFetcher.parseSize("74.7KB")).isEqualTo((74.7 * 1024).toLong())
        assertThat(BuildingFetcher.parseSize("??")).isEqualTo(0L)
    }

    @Test fun fetchRasterisesTheQuadkeysOfTheMissingChunksAndUnlistedOnesAreEmpty(): Unit = runBlocking {
        start()
        val id = ChunkMath.idFor(-35.28, 149.13)
        val quads = Quadkey.forChunk(id)
        val q = "311230302"
        files["/q/$q.csv.gz"] = gz(listOf(line(-35.2895, 149.1305, 300.0, 30.0), "", "garbage"))
        files["/links.csv"] = ("Location,QuadKey,Url,Size,UploadDate\n" +
            "Australia,$q,${server.url("/q/$q.csv.gz")},2.0KB,2026-02-23\n").toByteArray()
        val store = BuildingChunkStore(tmp.newFolder("bld"), { Long.MAX_VALUE })
        val fetcher = BuildingFetcher(OkHttpClient(), store, server.url("/links.csv").toString(), File(tmp.root, "links.csv"))
        assertThat(fetcher.estimateBytes(setOf(id))).isEqualTo(2048L)
        val progress = ArrayList<Float>()
        fetcher.fetch(setOf(id), allowMetered = false) { progress += it }
        assertThat(progress.last()).isEqualTo(1f)
        val c = store.get(id)!!
        val row = ((-35.2895 + 90.0) * 100).toInt() - id.latIdx * 25
        val col = ((149.1305 + 180.0) * 100).toInt() - id.lonIdx * 25
        assertThat(c.coverAt(row, col)!!).isGreaterThan(0.08f)
        assertThat(c.heightAt(row, col)).isEqualTo(30f)
        assertThat((0 until 625).count { (c.cover[it].toInt() and 0xFF) == BuildingChunk.NO_DATA }).isEqualTo(0)
        // Another chunk whose quadkeys are not listed: stored, empty, nothing but the links file was downloaded for it.
        val far = ChunkMath.idFor(-30.0, 135.0)
        val before = requests.size
        fetcher.fetch(setOf(far), allowMetered = false) {}
        assertThat(store.get(far)).isEqualTo(BuildingChunk.empty(far))
        assertThat(requests.size).isEqualTo(before)
        assertThat(quads).contains(q)
    }

    @Test fun meteredStorageAndNetworkFailuresLeaveNoChunk(): Unit = runBlocking {
        start()
        val id = ChunkMath.idFor(-35.28, 149.13)
        files["/links.csv"] = ("Location,QuadKey,Url,Size,UploadDate\n" +
            "Australia,311230302,${server.url("/q/missing.csv.gz")},2.0KB,2026-02-23\n").toByteArray()
        val store = BuildingChunkStore(tmp.newFolder("bld"), { Long.MAX_VALUE })
        val linksFile = File(tmp.root, "links.csv")
        val metered = BuildingFetcher(OkHttpClient(), store, server.url("/links.csv").toString(), linksFile, isMetered = { true })
        assertThrows(MeteredNetworkException::class.java) { runBlocking { metered.fetch(setOf(id), allowMetered = false) {} } }
        val full = BuildingFetcher(OkHttpClient(), store, server.url("/links.csv").toString(), linksFile, freeBytes = { 10L })
        assertThrows(InsufficientStorageException::class.java) { runBlocking { full.fetch(setOf(id), allowMetered = true) {} } }
        val gone = BuildingFetcher(OkHttpClient(), store, server.url("/links.csv").toString(), linksFile)
        assertThrows(java.io.IOException::class.java) { runBlocking { gone.fetch(setOf(id), allowMetered = true) {} } }
        assertThat(store.has(id)).isFalse()
        assertThat(linksFile.exists()).isFalse() // a listed file that is gone makes the listing stale
    }
}
