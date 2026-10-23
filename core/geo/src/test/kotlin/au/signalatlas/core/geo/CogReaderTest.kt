package au.signalatlas.core.geo

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class CogReaderTest {
    private fun meta(bytes: ByteArray, etag: String = "\"e\"") = CogReader.parseHeader(bytes.copyOf(minOf(bytes.size, 65536)), bytes.size.toLong(), etag)

    /** Decodes every tile of [bytes] and returns the 1200 x 1200 samples. */
    private fun decodeAll(bytes: ByteArray, m: CogMeta): FloatArray {
        val out = FloatArray(1200 * 1200)
        for (ty in 0 until m.tilesDown) {
            for (tx in 0 until m.tilesAcross) {
                val t = ty * m.tilesAcross + tx
                val rows = minOf(m.tileHeight, 1200 - ty * m.tileHeight)
                val cols = minOf(m.tileWidth, 1200 - tx * m.tileWidth)
                val tile = CogReader.decodeTile(m, bytes.copyOfRange(m.offsets[t].toInt(), (m.offsets[t] + m.byteCounts[t]).toInt()), rows)
                for (r in 0 until rows) System.arraycopy(tile, r * m.tileWidth, out, (ty * m.tileHeight + r) * 1200 + tx * m.tileWidth, cols)
            }
        }
        return out
    }

    private fun assertSameBits(actual: FloatArray, expected: FloatArray) {
        assertThat(actual.size).isEqualTo(expected.size)
        for (i in expected.indices) {
            if (actual[i].toRawBits() != expected[i].toRawBits() && !(actual[i].isNaN() && expected[i].isNaN())) {
                throw AssertionError("sample $i: ${actual[i]} != ${expected[i]}")
            }
        }
    }

    @Test fun `parses the GLO-90 layout of a 2 x 2 tiled COG`() {
        val bytes = SyntheticCog.build(-35, 148)
        val m = meta(bytes)
        assertThat(m.latNorth).isEqualTo(-35)
        assertThat(m.lonWest).isEqualTo(148)
        assertThat(m.tileWidth).isEqualTo(640)
        assertThat(m.tileHeight).isEqualTo(640)
        assertThat(m.tilesAcross).isEqualTo(2)
        assertThat(m.predictor).isEqualTo(3)
        assertThat(m.noData).isNull()
        assertThat(m.offsets.size).isEqualTo(4)
        assertThat(m.etag).isEqualTo("\"e\"")
        assertThat(m.fileLength).isEqualTo(bytes.size.toLong())
        // The tiles follow one another in the file, as in a COG.
        for (i in 1 until 4) assertThat(m.offsets[i]).isEqualTo(m.offsets[i - 1] + m.byteCounts[i - 1])
    }

    @Test fun `predictor 3 decode equals the source floats exactly`() {
        val bytes = SyntheticCog.build(-35, 148)
        assertSameBits(decodeAll(bytes, meta(bytes)), SyntheticCog.surface(-35, 148))
    }

    @Test fun `no predictor decode equals the source floats exactly`() {
        val bytes = SyntheticCog.build(-36, 149, tile = 400, predictor = 1)
        val m = meta(bytes)
        assertThat(m.predictor).isEqualTo(1)
        assertThat(m.tilesAcross).isEqualTo(3)
        assertSameBits(decodeAll(bytes, m), SyntheticCog.surface(-36, 149))
    }

    @Test fun `a single 2048 tile with inline offsets as in the real files`() {
        val bytes = SyntheticCog.build(-35, 148, tile = 2048)
        val m = meta(bytes)
        assertThat(m.offsets.size).isEqualTo(1)
        assertThat(m.tilesAcross).isEqualTo(1)
        assertSameBits(decodeAll(bytes, m), SyntheticCog.surface(-35, 148))
    }

    @Test fun `declared no-data is parsed`() {
        assertThat(meta(SyntheticCog.build(-35, 148, noData = "-32767")).noData).isEqualTo(-32767f)
    }

    @Test fun `a header that reaches past the prefix asks for more bytes`() {
        val bytes = SyntheticCog.build(-35, 148, headerPad = 80_000)
        val prefix = bytes.copyOf(65536)
        val need = assertThrows(NeedMoreHeader::class.java) { CogReader.parseHeader(prefix, bytes.size.toLong(), "e") }
        assertThat(need.bytesNeeded).isGreaterThan(65536L)
        val m = CogReader.parseHeader(bytes.copyOf(need.bytesNeeded.toInt()), bytes.size.toLong(), "e")
        assertThat(m.offsets.size).isEqualTo(4)
    }

    @Test fun `unsupported files are rejected`() {
        val bytes = SyntheticCog.build(-35, 148)
        val bigEndian = bytes.copyOf().also { it[0] = 'M'.code.toByte(); it[1] = 'M'.code.toByte() }
        assertThrows(CogFormatException::class.java) { meta(bigEndian) }
        assertThrows(CogFormatException::class.java) { meta(ByteArray(64) { 7 }) }
        // A tile that lies outside the file.
        assertThrows(CogFormatException::class.java) {
            CogReader.parseHeader(bytes.copyOf(65536), bytes.size.toLong() - 10, "e")
        }
    }

    @Test fun `a corrupt tile is a format error`() {
        val bytes = SyntheticCog.build(-35, 148)
        val m = meta(bytes)
        val tile = bytes.copyOfRange(m.offsets[0].toInt(), (m.offsets[0] + m.byteCounts[0]).toInt())
        tile[tile.size / 2] = (tile[tile.size / 2] + 1).toByte()
        tile[tile.size / 3] = (tile[tile.size / 3] + 1).toByte()
        assertThrows(CogFormatException::class.java) { CogReader.decodeTile(m, tile.copyOf(tile.size / 2), 640) }
    }
}
