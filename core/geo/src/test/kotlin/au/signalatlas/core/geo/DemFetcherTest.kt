package au.signalatlas.core.geo

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

@RunWith(RobolectricTestRunner::class)
class DemFetcherTest {
    @get:Rule val tmp = TemporaryFolder()

    private val bucket = FakeCogServer()
    private lateinit var geo: File
    private lateinit var store: DemChunkStore
    private var free = Long.MAX_VALUE
    private var metered = false

    // The four COGs around S36 / E148 and the whole-degree corner at (-36, 149).
    private val s36e148 = Pair(-35, 148)
    private val s36e149 = Pair(-35, 149)
    private val s37e148 = Pair(-36, 148)
    private val s37e149 = Pair(-36, 149)

    private val chunkA = ChunkId(217, 1314) // inside S36 E148, covers all four internal tiles
    private val chunkB = ChunkId(216, 1315) // first row on -36, last column on 149: four COGs
    private val chunkOneTile = ChunkId(219, 1313) // rows 0..300, cols 300..600 of S36 E148: internal tile 0 only

    @Before fun setUp() {
        bucket.start()
        geo = File(tmp.root, "geo")
        store = DemChunkStore(File(geo, "dem90"), { Long.MAX_VALUE })
    }

    @After fun tearDown() = bucket.close()

    private fun nameOf(cog: Pair<Int, Int>) = nameOf(cog.first, cog.second)

    private fun nameOf(latN: Int, lonW: Int): String {
        val south = latN - 1
        val ns = if (south < 0) "S%02d".format(-south) else "N%02d".format(south)
        val ew = if (lonW < 0) "W%03d".format(-lonW) else "E%03d".format(lonW)
        return "${ns}_00_${ew}_00"
    }

    private fun serveAll(bias: Int = 0, etagPrefix: String = "v1", tile: Int = 640, headerPad: Int = 0) {
        for (cog in listOf(s36e148, s36e149, s37e148, s37e149)) {
            bucket.put(
                nameOf(cog), SyntheticCog.build(cog.first, cog.second, tile = tile, bias = bias, headerPad = headerPad),
                "\"$etagPrefix-${nameOf(cog)}\"",
            )
        }
    }

    private fun fetcher(
        isMetered: () -> Boolean = { metered },
        freeBytes: () -> Long = { free },
        metaDir: File = File(geo, "cogmeta"),
        into: DemChunkStore = store,
    ) = DemFetcher(OkHttpClient(), into, bucket.baseUrl, metaDir, freeBytes, isMetered)

    private fun fetch(f: DemFetcher, vararg ids: ChunkId, allowMetered: Boolean = false, progress: (Float) -> Unit = {}) =
        runBlocking { f.fetch(ids.toSet(), allowMetered, progress) }

    /** The int16 chunk the source formula implies, independent of the fetcher's code paths. */
    private fun expected(id: ChunkId, present: Set<Pair<Int, Int>> = setOf(s36e148, s36e149, s37e148, s37e149), bias: Map<Pair<Int, Int>, Int> = emptyMap()): ShortArray {
        val out = ShortArray(301 * 301)
        for (i in 0..300) {
            val node = id.latIdx * 300 - 108000 + i
            val latN = Math.ceil(node / 1200.0).toInt()
            val row = latN * 1200 - node
            for (j in 0..300) {
                val n = id.lonIdx * 300 - 216000 + j
                val lonW = Math.floorDiv(n, 1200)
                val col = n - lonW * 1200
                val cog = Pair(latN, lonW)
                if (cog !in present) continue
                val v = SyntheticCog.sourceValue(SyntheticCog.seedOf(latN, lonW), row, col, bias[cog] ?: 0)
                out[i * 301 + j] = if (v.isNaN()) 0 else (if (v >= 0) Math.floor(v + 0.5) else -Math.floor(-v + 0.5)).toInt().toShort()
            }
        }
        return out
    }

    private fun stored(id: ChunkId): ShortArray = store.get(id)!!.samples

    private fun tileBytes(cog: Pair<Int, Int>, vararg tiles: Int): Long {
        val bytes = bucket.files.getValue(nameOf(cog)).bytes
        val m = CogReader.parseHeader(bytes.copyOf(65536), bytes.size.toLong(), "e")
        return tiles.sumOf { m.byteCounts[it] }
    }

    private fun pythonFixture(): List<ShortArray> {
        val raw = GZIPInputStream(javaClass.getResourceAsStream("expected_chunks.bin.gz")!!).readBytes()
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return List(raw.size / (301 * 301 * 2)) { ShortArray(301 * 301) { buf.getShort() } }
    }

    // ---- decode and resample ----------------------------------------------------------------------------------

    @Test fun `chunks equal the Python reference decode and resample`() {
        val fixture = pythonFixture()
        assertThat(fixture).hasSize(3)
        serveAll()
        fetch(fetcher(), chunkA, chunkB)
        assertThat(stored(chunkA)).isEqualTo(fixture[0])
        assertThat(stored(chunkB)).isEqualTo(fixture[1])

        // Chunk B again with the S37 E149 COG missing (404): its corner is 0, the rest is unchanged.
        bucket.files.remove(nameOf(s37e149))
        val other = DemChunkStore(File(tmp.root, "other"), { Long.MAX_VALUE })
        fetch(fetcher(into = other, metaDir = File(tmp.root, "other-meta")), chunkB)
        assertThat(other.get(chunkB)!!.samples).isEqualTo(fixture[2])
    }

    @Test fun `chunks equal the rounded source across COG boundaries`() {
        serveAll()
        fetch(fetcher(), chunkA, chunkB, chunkOneTile)
        for (id in listOf(chunkA, chunkB, chunkOneTile)) assertThat(stored(id)).isEqualTo(expected(id))
    }

    @Test fun `rounding is half away from zero and NaN and no-data are zero`() {
        assertThat(DemFetcher.toMetres(2.5f, null)).isEqualTo(3.toShort())
        assertThat(DemFetcher.toMetres(-2.5f, null)).isEqualTo((-3).toShort())
        assertThat(DemFetcher.toMetres(2.49f, null)).isEqualTo(2.toShort())
        assertThat(DemFetcher.toMetres(-0.4f, null)).isEqualTo(0.toShort())
        assertThat(DemFetcher.toMetres(Float.NaN, null)).isEqualTo(0.toShort())
        assertThat(DemFetcher.toMetres(-32767f, -32767f)).isEqualTo(0.toShort())
        assertThat(DemFetcher.toMetres(-32767f, null)).isEqualTo((-32767).toShort())
        assertThat(DemFetcher.toMetres(1e9f, null)).isEqualTo(Short.MAX_VALUE)
    }

    @Test fun `a chunk is the same whatever the internal tile layout`() {
        serveAll(tile = 400)
        fetch(fetcher(), chunkA, chunkB)
        assertThat(stored(chunkA)).isEqualTo(expected(chunkA))
        assertThat(stored(chunkB)).isEqualTo(expected(chunkB))
    }

    // ---- what is requested ------------------------------------------------------------------------------------

    @Test fun `only the needed internal tiles are fetched, pinned with If-Match`() {
        serveAll()
        val f = fetcher()
        fetch(f, chunkOneTile)
        val tile = bucket.tileRequests().single()
        val m = bucket.files.getValue(nameOf(s36e148)).let { CogReader.parseHeader(it.bytes.copyOf(65536), it.bytes.size.toLong(), it.etag) }
        assertThat(tile.headers["Range"]).isEqualTo("bytes=${m.offsets[0]}-${m.offsets[0] + m.byteCounts[0] - 1}")
        assertThat(tile.headers["If-Match"]).isEqualTo("\"v1-${nameOf(s36e148)}\"")
        assertThat(bucket.headerRequests()).hasSize(1)
        assertThat(bucket.headerRequests().single().headers["If-Match"]).isNull()
    }

    @Test fun `adjacent tile ranges are coalesced into one request`() {
        serveAll()
        fetch(fetcher(), chunkA) // needs all four internal tiles of one COG, stored back to back
        val tiles = bucket.tileRequests()
        assertThat(tiles).hasSize(1)
        val m = bucket.files.getValue(nameOf(s36e148)).let { CogReader.parseHeader(it.bytes.copyOf(65536), it.bytes.size.toLong(), it.etag) }
        assertThat(tiles.single().headers["Range"]).isEqualTo("bytes=${m.offsets[0]}-${m.offsets[3] + m.byteCounts[3] - 1}")
    }

    @Test fun `a chunk on a whole-degree corner reads four COGs`() {
        serveAll()
        fetch(fetcher(), chunkB)
        val urls = bucket.requests.map { it.url.encodedPath }.toSet()
        for (cog in listOf(s36e148, s36e149, s37e148, s37e149)) assertThat(urls.any { nameOf(cog) in it }).isTrue()
    }

    @Test fun `estimate counts each internal tile once`() {
        serveAll()
        val f = fetcher()
        val all = runBlocking { f.estimateBytes(setOf(chunkA, chunkOneTile)) }
        // chunkOneTile's tile 0 is also one of chunkA's four.
        assertThat(all).isEqualTo(tileBytes(s36e148, 0, 1, 2, 3))
        assertThat(runBlocking { f.estimateBytes(setOf(chunkOneTile)) }).isEqualTo(tileBytes(s36e148, 0))
        assertThat(bucket.tileRequests()).isEmpty() // an estimate reads headers only
        // Stored chunks are not counted.
        fetch(f, chunkOneTile)
        assertThat(runBlocking { f.estimateBytes(setOf(chunkA, chunkOneTile)) }).isEqualTo(tileBytes(s36e148, 0, 1, 2, 3))
        fetch(f, chunkA)
        assertThat(runBlocking { f.estimateBytes(setOf(chunkA, chunkOneTile)) }).isEqualTo(0L)
    }

    @Test fun `the header is read once per COG, in memory and on disk`() {
        serveAll()
        val f = fetcher()
        fetch(f, chunkA)
        fetch(f, chunkOneTile)
        assertThat(bucket.headerRequests()).hasSize(1)
        // A new fetcher (a new process) finds the header in geo/cogmeta.
        val other = DemChunkStore(File(tmp.root, "other"), { Long.MAX_VALUE })
        fetch(fetcher(into = other), chunkOneTile)
        assertThat(bucket.headerRequests()).hasSize(1)
        assertThat(other.get(chunkOneTile)!!.samples).isEqualTo(expected(chunkOneTile))
        assertThat(File(geo, "cogmeta").list()!!.toList()).containsExactly(nameOf(s36e148) + ".json")
    }

    @Test fun `an IFD past the first 64 KiB is read with a further pinned range`() {
        serveAll(headerPad = 80_000)
        fetch(fetcher(), chunkOneTile)
        val extra = bucket.requests.filter { (it.headers["Range"] ?: "").startsWith("bytes=65536-") }
        assertThat(extra).hasSize(1)
        assertThat(extra.single().headers["If-Match"]).isNotNull()
        assertThat(stored(chunkOneTile)).isEqualTo(expected(chunkOneTile))
    }

    @Test fun `chunks already stored are not fetched again`() {
        serveAll()
        val f = fetcher()
        fetch(f, chunkA)
        val before = bucket.requests.size
        var last = -1f
        fetch(f, chunkA) { last = it }
        assertThat(bucket.requests.size).isEqualTo(before)
        assertThat(last).isEqualTo(1f)
    }

    @Test fun `progress rises from 0 to 1`() {
        serveAll()
        val seen = ArrayList<Float>()
        fetch(fetcher(), chunkA, chunkB) { seen += it }
        assertThat(seen.first()).isEqualTo(0f)
        assertThat(seen.last()).isEqualTo(1f)
        assertThat(seen).isInOrder()
    }

    // ---- ocean ------------------------------------------------------------------------------------------------

    @Test fun `a 404 COG is ocean and its chunk is zeros`() {
        val ocean = ChunkId(100, 1000)
        val f = fetcher()
        assertThat(runBlocking { f.estimateBytes(setOf(ocean)) }).isEqualTo(0L)
        fetch(f, ocean)
        assertThat(store.has(ocean)).isTrue()
        assertThat(stored(ocean).all { it == 0.toShort() }).isTrue()
        assertThat(bucket.tileRequests()).isEmpty()
        // Remembered: no second look for the same COG.
        val n = bucket.requests.size
        fetch(f, ChunkId(101, 1000))
        assertThat(bucket.requests.size).isEqualTo(n)
    }

    // ---- failures ---------------------------------------------------------------------------------------------

    @Test fun `an interrupted fetch leaves no chunk and no temp file`() {
        serveAll()
        bucket.intercept = { req, name ->
            if ((req.headers["Range"] ?: "").startsWith("bytes=0-")) null
            else bucket.serve(req, bucket.files.getValue(name), truncateAfter = 1000)
        }
        assertThrows(IOException::class.java) { fetch(fetcher(), chunkA, chunkB) }
        assertThat(store.has(chunkA)).isFalse()
        assertThat(store.has(chunkB)).isFalse()
        assertThat(store.bytesUsed.value).isEqualTo(0L)
        assertThat(File(geo, "dem90").listFiles()!!.filter { it.name.endsWith(".dem") || it.name.endsWith(".tmp") }).isEmpty()

        // The next attempt starts clean.
        bucket.intercept = { _, _ -> null }
        fetch(fetcher(), chunkA, chunkB)
        assertThat(stored(chunkA)).isEqualTo(expected(chunkA))
    }

    @Test fun `a server error is an IOException`() {
        serveAll()
        bucket.intercept = { req, _ -> if ((req.headers["Range"] ?: "").startsWith("bytes=0-")) null else MockResponse.Builder().code(503).build() }
        assertThrows(IOException::class.java) { fetch(fetcher(), chunkA) }
        assertThat(store.has(chunkA)).isFalse()
    }

    @Test fun `a 412 mid-fetch drops the header and restarts the COG on the new version`() {
        serveAll()
        val f = fetcher()
        var swapped = false
        bucket.intercept = { req, name ->
            if (!swapped && name == nameOf(s36e148) && !(req.headers["Range"] ?: "").startsWith("bytes=0-")) {
                swapped = true // the file is replaced after its header was read
                bucket.put(name, SyntheticCog.build(-35, 148, bias = 7), "\"v2-$name\"")
            }
            null
        }
        fetch(f, chunkA)
        assertThat(swapped).isTrue()
        assertThat(stored(chunkA)).isEqualTo(expected(chunkA, bias = mapOf(s36e148 to 7)))
        // header (v1) + 412 tile request + header (v2) + tile request
        assertThat(bucket.requests.count { nameOf(s36e148) in it.url.encodedPath }).isEqualTo(4)
        assertThat(bucket.tileRequests().last().headers["If-Match"]).isEqualTo("\"v2-${nameOf(s36e148)}\"")
        assertThat(File(geo, "cogmeta/${nameOf(s36e148)}.json").readText()).contains("v2-")
    }

    @Test fun `a changed ETag on a 206 restarts the COG too`() {
        serveAll()
        var swapped = false
        bucket.intercept = { req, name ->
            if (!swapped && name == nameOf(s36e148) && !(req.headers["Range"] ?: "").startsWith("bytes=0-")) {
                swapped = true
                // A server that ignores If-Match and answers from the new version.
                val v2 = FakeCogServer.Version(SyntheticCog.build(-35, 148, bias = 3), "\"v2-$name\"")
                bucket.files[name] = v2
                bucket.serve(req, v2, ignoreIfMatch = true)
            } else {
                null
            }
        }
        fetch(fetcher(), chunkA)
        assertThat(stored(chunkA)).isEqualTo(expected(chunkA, bias = mapOf(s36e148 to 3)))
    }

    @Test fun `a COG that keeps changing fails after three attempts and stores nothing`() {
        serveAll()
        bucket.intercept = { req, _ ->
            if ((req.headers["Range"] ?: "").startsWith("bytes=0-")) null else MockResponse.Builder().code(412).build()
        }
        val e = assertThrows(IOException::class.java) { fetch(fetcher(), chunkOneTile) }
        assertThat(e).hasMessageThat().contains("3 times")
        assertThat(bucket.tileRequests()).hasSize(3)
        assertThat(store.has(chunkOneTile)).isFalse()
        assertThat(File(geo, "cogmeta").listFiles()!!.toList()).isEmpty()
    }

    @Test fun `a COG deleted after its header was read restarts and becomes ocean`() {
        serveAll()
        bucket.intercept = { req, name ->
            if (name == nameOf(s36e148) && !(req.headers["Range"] ?: "").startsWith("bytes=0-")) {
                bucket.files.remove(name)
                MockResponse.Builder().code(404).build()
            } else {
                null
            }
        }
        fetch(fetcher(), chunkOneTile)
        assertThat(stored(chunkOneTile).all { it == 0.toShort() }).isTrue()
    }

    // ---- storage and network policy ---------------------------------------------------------------------------

    @Test fun `low free space throws before any tile is fetched`() {
        serveAll()
        val f = fetcher()
        val estimate = runBlocking { f.estimateBytes(setOf(chunkA)) }
        free = DemFetcher.RESERVE_BYTES + estimate - 1
        val e = assertThrows(InsufficientStorageException::class.java) { fetch(f, chunkA) }
        assertThat(e.requiredBytes).isEqualTo(DemFetcher.RESERVE_BYTES + estimate)
        assertThat(e.availableBytes).isEqualTo(free)
        assertThat(bucket.tileRequests()).isEmpty()
        assertThat(store.has(chunkA)).isFalse()

        free = DemFetcher.RESERVE_BYTES + estimate // exactly enough
        fetch(f, chunkA)
        assertThat(store.has(chunkA)).isTrue()
    }

    @Test fun `a metered network needs allowMetered`() {
        serveAll()
        metered = true
        assertThrows(MeteredNetworkException::class.java) { fetch(fetcher(), chunkA) }
        assertThat(bucket.requests).isEmpty()
        fetch(fetcher(), chunkA, allowMetered = true)
        assertThat(store.has(chunkA)).isTrue()
    }

    @Test fun `the store budget applies to fetched chunks`() {
        serveAll()
        val small = DemChunkStore(File(tmp.root, "small"), { 1L })
        fetch(fetcher(into = small), chunkOneTile)
        assertThat(small.bytesUsed.value).isEqualTo(0L)
    }
}
