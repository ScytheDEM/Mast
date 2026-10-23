package au.signalatlas.core.geo

import au.signalatlas.core.model.GeoBounds
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A 0.25 degree elevation chunk: `latIdx = floor((lat + 90) * 4)`, `lonIdx = floor((lon + 180) * 4)`. */
data class ChunkId(val latIdx: Int, val lonIdx: Int) {
    /** The chunk's file stem, `<latIdx>_<lonIdx>`. */
    val key: String get() = "${latIdx}_$lonIdx"

    /** Latitude of the chunk's south edge (the first row of nodes), degrees. */
    val southDeg: Double get() = latIdx / 4.0 - 90.0

    /** Longitude of the chunk's west edge (the first column of nodes), degrees. */
    val westDeg: Double get() = lonIdx / 4.0 - 180.0
}

object ChunkMath {
    /** Nodes per degree: one every 3 arcseconds. */
    const val NODES_PER_DEGREE = 1200

    /** Node steps per chunk side (0.25 degree). */
    const val STEPS = 300

    /** Nodes per chunk side: [STEPS] plus the shared node on the north / east edge. */
    const val NODES = STEPS + 1

    const val MAX_LAT_IDX = 719
    const val MAX_LON_IDX = 1439

    private const val KM_PER_DEGREE = 6371.0 * Math.PI / 180.0

    fun idFor(lat: Double, lon: Double): ChunkId = ChunkId(
        floor((lat + 90.0) * 4.0).toInt().coerceIn(0, MAX_LAT_IDX),
        floor((lon + 180.0) * 4.0).toInt().coerceIn(0, MAX_LON_IDX),
    )

    /** Every chunk intersecting [bounds] expanded by [marginKm] on all sides. */
    fun chunksFor(bounds: GeoBounds, marginKm: Double): Set<ChunkId> {
        val dLat = marginKm / KM_PER_DEGREE
        val south = bounds.south - dLat
        val north = bounds.north + dLat
        // Longitude degrees shrink with latitude: use the edge farthest from the equator.
        val dLon = dLat / cos(Math.toRadians(min(max(abs(south), abs(north)), 89.0)))
        val lo = idFor(south, bounds.west - dLon)
        val hi = idFor(north, bounds.east + dLon)
        val ids = LinkedHashSet<ChunkId>()
        for (la in lo.latIdx..hi.latIdx) for (lo2 in lo.lonIdx..hi.lonIdx) ids += ChunkId(la, lo2)
        return ids
    }
}

/**
 * One elevation chunk: [ChunkMath.NODES] x [ChunkMath.NODES] int16 metres on the 3 arcsecond grid, row-major, row 0 the
 * south edge, column 0 the west edge. Node (row, col) lies at latitude `southDeg + row / 1200` and longitude
 * `westDeg + col / 1200`.
 *
 * File format (little-endian): magic `SADEM1`, version (u8), reserved (u8), latIdx (i32), lonIdx (i32), payload
 * length (u32), CRC32 of the payload (u32), then the payload: DEFLATE of the samples as int16.
 */
class DemChunk(val id: ChunkId, val samples: ShortArray) {
    init {
        require(samples.size == ChunkMath.NODES * ChunkMath.NODES) { "A chunk has ${ChunkMath.NODES}^2 samples" }
    }

    /** Elevation in metres at [row] (south to north) and [col] (west to east). */
    fun at(row: Int, col: Int): Int = samples[row * ChunkMath.NODES + col].toInt()

    fun encode(): ByteArray {
        val raw = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        raw.asShortBuffer().put(samples)
        val deflater = Deflater(6)
        val payload = try {
            deflater.setInput(raw.array())
            deflater.finish()
            val out = ByteArrayOutputStream(raw.capacity() / 2)
            val buf = ByteArray(16 * 1024)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            out.toByteArray()
        } finally {
            deflater.end()
        }
        val crc = CRC32().apply { update(payload) }.value
        return ByteBuffer.allocate(HEADER_BYTES + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(MAGIC).put(VERSION).put(0)
            .putInt(id.latIdx).putInt(id.lonIdx).putInt(payload.size).putInt(crc.toInt())
            .put(payload).array()
    }

    override fun equals(other: Any?) = other is DemChunk && other.id == id && other.samples.contentEquals(samples)

    override fun hashCode() = 31 * id.hashCode() + samples.contentHashCode()

    override fun toString() = "DemChunk($id)"

    companion object {
        const val HEADER_BYTES = 24
        private val MAGIC = "SADEM1".toByteArray(Charsets.US_ASCII)
        private const val VERSION: Byte = 1

        /** The chunk in [bytes], or null if anything is wrong: magic, version, length, CRC or payload. */
        fun decode(bytes: ByteArray): DemChunk? {
            if (bytes.size < HEADER_BYTES) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(MAGIC.size).also { buf.get(it) }
            if (!magic.contentEquals(MAGIC) || buf.get() != VERSION) return null
            buf.get() // reserved
            val id = ChunkId(buf.getInt(), buf.getInt())
            val length = buf.getInt()
            val crc = buf.getInt().toLong() and 0xFFFFFFFFL
            if (length < 0 || bytes.size != HEADER_BYTES + length) return null
            if (CRC32().apply { update(bytes, HEADER_BYTES, length) }.value != crc) return null
            val raw = ByteArray(ChunkMath.NODES * ChunkMath.NODES * 2)
            val inflater = Inflater()
            try {
                inflater.setInput(bytes, HEADER_BYTES, length)
                var n = 0
                while (n < raw.size) {
                    val got = inflater.inflate(raw, n, raw.size - n)
                    if (got == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                    n += got
                }
                // The stream must end exactly here: one more call consumes the end marker and yields nothing.
                if (n == raw.size && !inflater.finished() && inflater.inflate(ByteArray(1)) != 0) return null
                if (n != raw.size || !inflater.finished()) return null
            } catch (_: java.util.zip.DataFormatException) {
                return null
            } finally {
                inflater.end()
            }
            val samples = ShortArray(raw.size / 2)
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
            return DemChunk(id, samples)
        }
    }
}
