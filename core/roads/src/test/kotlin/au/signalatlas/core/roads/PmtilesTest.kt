package au.signalatlas.core.roads

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okio.Buffer
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

/** Builds a tiny PMTiles v3 archive: root dir (run + leaf pointer), one leaf dir, three tile payloads. */
internal class TestArchive(private val compressed: Boolean = true, val runTile: ByteArray = "run-tile".toByteArray()) {
    val id14 = PmtilesIds.tileId(14, 14932, 9897)
    val z0Tile = "z0-tile".toByteArray()

    val bytes: ByteArray

    private fun gz(b: ByteArray) = if (!compressed) b else ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private fun varint(out: ByteArrayOutputStream, v: Long) {
        var x = v
        while (x >= 0x80) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
        out.write(x.toInt())
    }

    fun dir(entries: List<PmtilesEntry>, zeroOffsetWhenContiguous: Boolean = false): ByteArray {
        val o = ByteArrayOutputStream()
        varint(o, entries.size.toLong())
        var last = 0L
        for (e in entries) { varint(o, e.tileId - last); last = e.tileId }
        for (e in entries) varint(o, e.runLength.toLong())
        for (e in entries) varint(o, e.length.toLong())
        for ((i, e) in entries.withIndex()) {
            val contiguous = i > 0 && e.offset == entries[i - 1].offset + entries[i - 1].length
            varint(o, if (zeroOffsetWhenContiguous && contiguous) 0 else e.offset + 1)
        }
        return gz(o.toByteArray())
    }

    init {
        // Tile data: z0 tile then the run tile (shared by 3 ids), both gzip-wrapped like the real tile payloads.
        val d0 = gz(z0Tile)
        val d1 = gz(runTile)
        val data = d0 + d1
        val leaf = dir(listOf(PmtilesEntry(id14, 3, d1.size, d0.size.toLong())))
        val root = dir(
            listOf(
                PmtilesEntry(0, 1, d0.size, 0),
                PmtilesEntry(id14 - 100, 0, leaf.size, 0), // leaf pointer: offset into the leaf section
            ),
        )
        val h = ByteArray(127)
        "PMTiles".toByteArray().copyInto(h)
        h[7] = 3
        fun u64(o: Int, v: Long) { for (i in 0 until 8) h[o + i] = (v ushr (8 * i)).toByte() }
        val rootOff = 127L
        val leafOff = rootOff + root.size
        val dataOff = leafOff + leaf.size
        u64(8, rootOff); u64(16, root.size.toLong()); u64(40, leafOff); u64(48, leaf.size.toLong())
        u64(56, dataOff); u64(64, data.size.toLong())
        h[97] = (if (compressed) 2 else 1).toByte(); h[98] = (if (compressed) 2 else 1).toByte(); h[99] = 1
        h[100] = 0; h[101] = 14
        bytes = h + root + leaf + data
    }
}

class PmtilesTest {
    private val server = MockWebServer()

    @After fun stop() = server.close()

    private class Counting(private val b: ByteArray) : RangeSource {
        val reads = ArrayList<Pair<Long, Int>>()
        override fun read(offset: Long, length: Int): ByteArray {
            reads += offset to length
            if (offset >= b.size) return ByteArray(0)
            return b.copyOfRange(offset.toInt(), minOf(b.size.toLong(), offset + length).toInt())
        }
    }

    @Test fun hilbertTileIdsFollowTheSpecOrder() {
        assertThat(PmtilesIds.tileId(0, 0, 0)).isEqualTo(0)
        assertThat(PmtilesIds.tileId(1, 0, 0)).isEqualTo(1)
        assertThat(PmtilesIds.tileId(1, 0, 1)).isEqualTo(2)
        assertThat(PmtilesIds.tileId(1, 1, 1)).isEqualTo(3)
        assertThat(PmtilesIds.tileId(1, 1, 0)).isEqualTo(4)
        assertThat(PmtilesIds.tileId(2, 0, 0)).isEqualTo(5)
        // The tile fetched for the Gundagai fixture from the real 20261024 archive.
        assertThat(PmtilesIds.tileId(14, 14932, 9897)).isEqualTo(288240432L)
        assertThrows(IllegalArgumentException::class.java) { PmtilesIds.tileId(1, 2, 0) }
    }

    @Test fun readsRunsAndLeafDirectoriesWithFewRangeReads() {
        val a = TestArchive()
        val src = Counting(a.bytes)
        val r = PmtilesReader.open(src)
        assertThat(String(r.tile(0, 0, 0)!!)).isEqualTo("z0-tile")
        assertThat(src.reads).hasSize(2) // opening (header + root) and the tile itself
        // Tile ids id14 .. id14+2 share the run; the leaf directory is read once and cached.
        assertThat(String(r.tile(14, 14932, 9897)!!)).isEqualTo("run-tile")
        val readsAfterFirstLeaf = src.reads.size
        assertThat(String(r.tile(14, 14932, 9897)!!)).isEqualTo("run-tile")
        assertThat(src.reads.size).isEqualTo(readsAfterFirstLeaf + 1) // only the payload again
    }

    @Test fun missingTilesAndZoomsAreNull() {
        val r = PmtilesReader.open(Counting(TestArchive().bytes))
        assertThat(r.tile(14, 0, 0)).isNull() // not in any run
        assertThat(r.tile(1, 0, 0)).isNull()
        assertThat(r.tile(15, 0, 0)).isNull() // beyond maxZoom
    }

    @Test fun uncompressedArchivesWork() {
        val a = TestArchive(compressed = false)
        val r = PmtilesReader.open(Counting(a.bytes))
        assertThat(String(r.tile(14, 14932, 9897)!!)).isEqualTo("run-tile")
    }

    @Test fun contiguousOffsetsEncodedAsZeroAreResolved() {
        val e = listOf(PmtilesEntry(0, 1, 10, 0), PmtilesEntry(5, 1, 20, 10), PmtilesEntry(9, 2, 5, 30))
        val dir = TestArchive().dir(e, true)
        val parsed = PmtilesReader.parseDirectory(gunzip(dir))
        assertThat(parsed.map { it.offset }).containsExactly(0L, 10L, 30L).inOrder()
        assertThat(PmtilesReader.find(parsed, 10)!!.tileId).isEqualTo(9)
        assertThat(PmtilesReader.find(parsed, 11)).isNull()
        assertThat(PmtilesReader.find(parsed, 5)!!.tileId).isEqualTo(5)
    }

    @Test fun notAPmtilesFileIsRejected() {
        assertThrows(PmtilesException::class.java) { PmtilesReader.open(Counting(ByteArray(300) { 1 })) }
    }

    private fun serve(archive: () -> ByteArray, etag: () -> String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val b = archive()
                val tag = etag()
                val ifMatch = request.headers["If-Match"]
                if (ifMatch != null && ifMatch != tag) return MockResponse.Builder().code(412).build()
                val m = Regex("bytes=(\\d+)-(\\d+)").find(request.headers["Range"].orEmpty()) ?: return MockResponse.Builder().code(200).build()
                val from = m.groupValues[1].toInt()
                val to = minOf(m.groupValues[2].toInt(), b.size - 1)
                return MockResponse.Builder().code(206).addHeader("ETag", tag)
                    .addHeader("Content-Range", "bytes $from-$to/${b.size}")
                    .body(Buffer().write(b, from, to - from + 1)).build()
            }
        }
        server.start()
    }

    @Test fun httpRangeReadsPinTheEtagAndSendIfMatch() {
        val a = TestArchive().bytes
        var tag = "\"v1\""
        serve({ a }, { tag })
        val src = HttpRangeSource(OkHttpClient(), server.url("/20261024.pmtiles").toString())
        val r = PmtilesReader.open(src)
        assertThat(src.etag).isEqualTo("\"v1\"")
        assertThat(String(r.tile(14, 14932, 9897)!!)).isEqualTo("run-tile")
        assertThat(server.takeRequest().headers["If-Match"]).isNull() // the first read pins
        assertThat(server.takeRequest().headers["If-Match"]).isEqualTo("\"v1\"")
        // The server replaces the file: the pinned reads fail instead of mixing two builds.
        tag = "\"v2\""
        assertThrows(PmtilesException::class.java) { r.tile(0, 0, 0) }
    }

    @Test fun httpErrorsAreIoExceptions() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(403).build()
        }
        server.start()
        val src = HttpRangeSource(OkHttpClient(), server.url("/x.pmtiles").toString())
        assertThrows(IOException::class.java) { src.read(0, 10) }
    }
}
