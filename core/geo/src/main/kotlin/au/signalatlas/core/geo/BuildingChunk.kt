package au.signalatlas.core.geo

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The building grid of one 0.25 degree chunk ([ChunkId], the DEM chunk layout): [CELLS] x [CELLS] cells of
 * [CELL_DEG] (about 1 km), row-major, row 0 the south edge, column 0 the west edge. Cell (row, col) covers latitudes
 * `southDeg + row * 0.01 .. + 0.01` and longitudes `westDeg + col * 0.01 .. + 0.01`.
 *
 * Per cell: [cover] = the fraction of the cell's ground covered by building footprints, as `round(fraction * 250)`
 * (0..250; [NO_DATA] = 255 means the cell has no data), and [height] = the footprint-area-weighted mean building height
 * in [HEIGHT_STEP_M] steps (0..255, saturating at 127.5 m; 0 where there are no buildings).
 *
 * File format (little-endian): magic `SABLD1`, version (u8), reserved (u8), latIdx (i32), lonIdx (i32), payload length
 * (u32), CRC32 of the payload (u32), then the payload: the [cover] bytes followed by the [height] bytes.
 */
class BuildingChunk(val id: ChunkId, val cover: ByteArray, val height: ByteArray) {
    init {
        require(cover.size == CELLS * CELLS && height.size == CELLS * CELLS) { "A building chunk has $CELLS^2 cells" }
    }

    /** Coverage fraction 0..1 of cell (row, col), or null when it has no data. */
    fun coverAt(row: Int, col: Int): Float? {
        val c = cover[row * CELLS + col].toInt() and 0xFF
        return if (c == NO_DATA) null else c / COVER_SCALE
    }

    /** Mean building height (m) of cell (row, col). */
    fun heightAt(row: Int, col: Int): Float = (height[row * CELLS + col].toInt() and 0xFF) * HEIGHT_STEP_M

    fun encode(): ByteArray {
        val payload = cover + height
        val crc = CRC32().apply { update(payload) }.value
        return ByteBuffer.allocate(HEADER_BYTES + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(MAGIC).put(VERSION).put(0)
            .putInt(id.latIdx).putInt(id.lonIdx).putInt(payload.size).putInt(crc.toInt())
            .put(payload).array()
    }

    override fun equals(other: Any?) =
        other is BuildingChunk && other.id == id && other.cover.contentEquals(cover) && other.height.contentEquals(height)

    override fun hashCode() = 31 * (31 * id.hashCode() + cover.contentHashCode()) + height.contentHashCode()

    override fun toString() = "BuildingChunk($id)"

    companion object {
        /** Cells per chunk side: 0.25 / 0.01. */
        const val CELLS = 25
        const val CELL_DEG = 0.01
        /** Cells per degree. */
        const val CELLS_PER_DEGREE = 100
        const val NO_DATA = 255
        const val COVER_SCALE = 250f
        const val HEIGHT_STEP_M = 0.5f
        const val HEADER_BYTES = 24
        private val MAGIC = "SABLD1".toByteArray(Charsets.US_ASCII)
        private const val VERSION: Byte = 1

        /** The chunk in [bytes], or null if anything is wrong: magic, version, length or CRC. */
        fun decode(bytes: ByteArray): BuildingChunk? {
            if (bytes.size != HEADER_BYTES + 2 * CELLS * CELLS) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(MAGIC.size).also { buf.get(it) }
            if (!magic.contentEquals(MAGIC) || buf.get() != VERSION) return null
            buf.get()
            val id = ChunkId(buf.getInt(), buf.getInt())
            val length = buf.getInt()
            val crc = buf.getInt().toLong() and 0xFFFFFFFFL
            if (length != 2 * CELLS * CELLS) return null
            if (CRC32().apply { update(bytes, HEADER_BYTES, length) }.value != crc) return null
            val cover = bytes.copyOfRange(HEADER_BYTES, HEADER_BYTES + CELLS * CELLS)
            val height = bytes.copyOfRange(HEADER_BYTES + CELLS * CELLS, HEADER_BYTES + length)
            return BuildingChunk(id, cover, height)
        }

        /** A chunk whose every cell has data and no buildings (a quadkey the dataset does not list). */
        fun empty(id: ChunkId) = BuildingChunk(id, ByteArray(CELLS * CELLS), ByteArray(CELLS * CELLS))
    }
}

/** The building-grid cache: `geo/bld/<latIdx>_<lonIdx>.bld`, with the DEM cache's LRU, budget and pin rules. */
class BuildingChunkStore(
    dir: File,
    budgetBytes: () -> Long,
    clock: () -> Long = System::currentTimeMillis,
) : GeoChunkStore<BuildingChunk>(dir, budgetBytes, clock, "bld") {
    override fun encode(chunk: BuildingChunk): ByteArray = chunk.encode()
    override fun decode(bytes: ByteArray, id: ChunkId): BuildingChunk? = BuildingChunk.decode(bytes)?.takeIf { it.id == id }
    override fun idOf(chunk: BuildingChunk): ChunkId = chunk.id
}

/** One building footprint: the outer ring (lon, lat pairs) and its height in metres (`<= 0` = unknown), or levels. */
class Footprint(val ring: DoubleArray, val heightM: Double, val levels: Double? = null) {
    /** Buildings rule: a known height, else 3 m per level if tagged, else 6 m. */
    val effectiveHeightM: Double
        get() = when {
            heightM > 0.0 && heightM.isFinite() -> heightM
            levels != null && levels > 0.0 && levels.isFinite() -> 3.0 * levels
            else -> UNKNOWN_HEIGHT_M
        }

    companion object {
        const val UNKNOWN_HEIGHT_M = 6.0
    }
}

/**
 * A minimal reader of the Microsoft Global ML Building Footprints GeoJSON lines (one Feature per line, verified
 * 2026-10-25 on quadkey 311230302): `{"type": "Feature", "properties": { "height": 6.7, "confidence": -1.0},
 * "geometry": {"type": "Polygon","coordinates": [[[lon, lat], ...]]}}`. Only the first (outer) ring is read; holes
 * are ignored (they are rare and small). Lines that are not a polygon feature give null.
 */
object FootprintParser {
    private val HEIGHT = Regex(""""height"\s*:\s*(-?[0-9.eE+-]+)""")
    private val LEVELS = Regex(""""(?:building:)?levels"\s*:\s*"?(-?[0-9.eE+-]+)""")

    fun parse(line: String): Footprint? {
        val at = line.indexOf("\"coordinates\"")
        if (at < 0) return null
        val height = HEIGHT.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: -1.0
        val levels = LEVELS.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
        val start = line.indexOf("[[", at)
        if (start < 0) return null
        val end = line.indexOf("]]", start)
        if (end < 0) return null
        val nums = ArrayList<Double>()
        var i = start
        while (i < end) {
            val ch = line[i]
            if (ch == '-' || ch == '.' || ch.isDigit()) {
                var j = i + 1
                while (j < end && (line[j].isDigit() || line[j] == '.' || line[j] == 'e' || line[j] == 'E' || line[j] == '-' || line[j] == '+')) j++
                nums += line.substring(i, j).toDoubleOrNull() ?: return null
                i = j
            } else {
                i++
            }
        }
        if (nums.size < 6 || nums.size % 2 != 0) return null
        return Footprint(nums.toDoubleArray(), height, levels)
    }
}

/**
 * Accumulates footprints into 0.01 degree cells (Buildings rule): each footprint's area (shoelace in local metres)
 * and area x height go to the cell holding its vertex centroid (footprints are far smaller than a cell). [chunk] then
 * gives the coverage fraction (area / cell area, at most 1) and the area-weighted mean height of a chunk's cells; every
 * cell of a chunk built here has data.
 */
class BuildingRasterizer {
    private val sums = HashMap<Long, DoubleArray>() // cell -> (area m2, area * height)

    val footprints: Int get() = count
    private var count = 0

    fun add(f: Footprint) {
        val r = f.ring
        val n = r.size / 2
        var cx = 0.0
        var cy = 0.0
        for (k in 0 until n) { cx += r[2 * k]; cy += r[2 * k + 1] }
        cx /= n
        cy /= n
        if (!cx.isFinite() || !cy.isFinite() || abs(cy) > 90.0) return
        val mx = M_PER_DEG * cos(Math.toRadians(cy))
        var twice = 0.0
        for (k in 0 until n) {
            val a = (k + 1) % n
            val x0 = (r[2 * k] - cx) * mx
            val y0 = (r[2 * k + 1] - cy) * M_PER_DEG
            val x1 = (r[2 * a] - cx) * mx
            val y1 = (r[2 * a + 1] - cy) * M_PER_DEG
            twice += x0 * y1 - x1 * y0
        }
        val area = abs(twice) / 2.0
        if (!area.isFinite() || area <= 0.0 || area > MAX_FOOTPRINT_M2) return
        val s = sums.getOrPut(cellKey(cy, cx)) { DoubleArray(2) }
        s[0] += area
        s[1] += area * f.effectiveHeightM
        count++
    }

    /** Chunk [id]'s cells from what was added. */
    fun chunk(id: ChunkId): BuildingChunk {
        val cells = BuildingChunk.CELLS
        val cover = ByteArray(cells * cells)
        val height = ByteArray(cells * cells)
        val row0 = id.latIdx * cells
        val col0 = id.lonIdx * cells
        for (r in 0 until cells) for (c in 0 until cells) {
            val s = sums[((row0 + r).toLong() shl 32) or (col0 + c).toLong()] ?: continue
            val lat = (row0 + r + 0.5) / BuildingChunk.CELLS_PER_DEGREE - 90.0
            val cellArea = (BuildingChunk.CELL_DEG * M_PER_DEG).let { it * it * cos(Math.toRadians(lat)) }
            cover[r * cells + c] = (min(1.0, s[0] / cellArea) * BuildingChunk.COVER_SCALE).roundToInt().toByte()
            height[r * cells + c] = min(255, max(0, (s[1] / s[0] / BuildingChunk.HEIGHT_STEP_M).roundToInt())).toByte()
        }
        return BuildingChunk(id, cover, height)
    }

    private fun cellKey(lat: Double, lon: Double): Long {
        val row = floor((lat + 90.0) * BuildingChunk.CELLS_PER_DEGREE).toLong()
        val col = floor((lon + 180.0) * BuildingChunk.CELLS_PER_DEGREE).toLong()
        return (row shl 32) or col
    }

    private companion object {
        const val M_PER_DEG = 6371008.8 * Math.PI / 180.0
        /** A footprint larger than this (1 km^2) is a data error. */
        const val MAX_FOOTPRINT_M2 = 1.0e6
    }
}
