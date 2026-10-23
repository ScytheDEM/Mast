package au.signalatlas.core.geo

import au.signalatlas.core.model.GeoBounds
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random

class DemChunkTest {
    private fun chunk(id: ChunkId = ChunkId(217, 1314), seed: Long = 1): DemChunk {
        val rnd = Random(seed)
        return DemChunk(id, ShortArray(301 * 301) { (rnd.nextInt(3000) - 100).toShort() })
    }

    @Test fun `encode then decode round-trips`() {
        val c = chunk()
        val bytes = c.encode()
        assertThat(String(bytes, 0, 6, Charsets.US_ASCII)).isEqualTo("SADEM1")
        assertThat(DemChunk.decode(bytes)).isEqualTo(c)
    }

    @Test fun `an ocean chunk is tiny`() {
        val bytes = DemChunk(ChunkId(1, 2), ShortArray(301 * 301)).encode()
        assertThat(bytes.size).isLessThan(1000)
    }

    @Test fun `any damage is a miss`() {
        val bytes = chunk().encode()
        // A flipped payload byte (CRC), a bad magic, a truncated file, trailing junk, a wrong version.
        assertThat(DemChunk.decode(bytes.copyOf().also { it[bytes.size / 2] = (it[bytes.size / 2] + 1).toByte() })).isNull()
        assertThat(DemChunk.decode(bytes.copyOf().also { it[0] = 'X'.code.toByte() })).isNull()
        assertThat(DemChunk.decode(bytes.copyOf(bytes.size - 1))).isNull()
        assertThat(DemChunk.decode(bytes + 0)).isNull()
        assertThat(DemChunk.decode(bytes.copyOf().also { it[6] = 2 })).isNull()
        assertThat(DemChunk.decode(ByteArray(10))).isNull()
    }

    @Test fun `ids follow the floor formulas`() {
        assertThat(ChunkMath.idFor(-35.3, 149.1)).isEqualTo(ChunkId(218, 1316))
        assertThat(ChunkMath.idFor(-35.25, 149.0)).isEqualTo(ChunkId(219, 1316))
        val id = ChunkId(218, 1316)
        assertThat(id.southDeg).isEqualTo(-35.5)
        assertThat(id.westDeg).isEqualTo(149.0)
        assertThat(id.key).isEqualTo("218_1316")
    }

    @Test fun `chunksFor covers the bounds and the margin`() {
        val bounds = GeoBounds(south = -35.30, west = 149.05, north = -35.28, east = 149.10)
        assertThat(ChunkMath.chunksFor(bounds, 0.0)).containsExactly(ChunkId(218, 1316))
        val withMargin = ChunkMath.chunksFor(bounds, 40.0)
        // 40 km is 0.36 degrees of latitude and 0.44 of longitude here: 0.25-degree chunks 2 around in each direction.
        assertThat(withMargin).contains(ChunkId(218, 1316))
        assertThat(withMargin.map { it.latIdx }.distinct().sorted()).containsExactly(217, 218, 219, 220).inOrder()
        assertThat(withMargin.map { it.lonIdx }.distinct().sorted()).containsExactly(1314, 1315, 1316, 1317, 1318).inOrder()
        assertThat(withMargin).hasSize(4 * 5)
    }
}
