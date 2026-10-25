package au.signalatlas.engine

import au.signalatlas.core.geo.BuildingChunk
import au.signalatlas.core.geo.BuildingChunkStore
import au.signalatlas.core.geo.ChunkId
import au.signalatlas.core.model.GeoBounds
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A mosaic of building cells (0.01 degree, see [BuildingChunk]) for the native receiver-environment lookup (M2 rule 5a):
 * cell (row, col) covers latitudes `southDeg + row * 0.01 ..` and longitudes `westDeg + col * 0.01 ..`, row 0 = south.
 * [cover] and [height] are direct buffers of `rows * cols` codes; cells of chunks that are not stored have no data
 * ([BuildingChunk.NO_DATA]), so the carriers' own classes apply there. Immutable; small (a z8 tile is about 2 x 10^4 cells).
 */
class BuildingGrid internal constructor(
    /** Cell index of row 0: `southCell / 100` degrees is the latitude of the south edge. */
    val southCell: Int,
    val westCell: Int,
    val rows: Int,
    val cols: Int,
    internal val cover: ByteBuffer,
    internal val height: ByteBuffer,
    /** The chunks the grid read and found stored (the rest are no data). */
    val stored: Set<ChunkId>,
) {
    val southDeg: Double get() = southCell / CELLS_PER_DEGREE - 90.0
    val westDeg: Double get() = westCell / CELLS_PER_DEGREE - 180.0

    /** The cover code of cell (row, col). */
    fun coverCode(row: Int, col: Int): Int = cover.get(row * cols + col).toInt() and 0xFF

    companion object {
        private const val CELLS_PER_DEGREE = BuildingChunk.CELLS_PER_DEGREE.toDouble()

        /** Every chunk a grid for [bounds] reads ([open] adds one cell all round for the interpolation). */
        fun chunksFor(bounds: GeoBounds): Set<ChunkId> {
            val (s, w, r, c) = cells(bounds)
            val out = LinkedHashSet<ChunkId>()
            val cpc = BuildingChunk.CELLS
            for (la in floorDiv(s, cpc)..floorDiv(s + r - 1, cpc)) for (lo in floorDiv(w, cpc)..floorDiv(w + c - 1, cpc)) out += ChunkId(la, lo)
            return out
        }

        /** The grid over [bounds] plus one cell all round, from the chunks [store] has. Reads small files: call off the main thread. */
        fun open(store: BuildingChunkStore, bounds: GeoBounds): BuildingGrid {
            val (s, w, rows, cols) = cells(bounds)
            val cover = ByteBuffer.allocateDirect(rows * cols)
            val height = ByteBuffer.allocateDirect(rows * cols)
            for (k in 0 until rows * cols) cover.put(k, BuildingChunk.NO_DATA.toByte())
            val stored = HashSet<ChunkId>()
            val cpc = BuildingChunk.CELLS
            for (id in chunksFor(bounds)) {
                val chunk = store.get(id) ?: continue
                stored += id
                for (r in 0 until cpc) {
                    val row = id.latIdx * cpc + r - s
                    if (row !in 0 until rows) continue
                    for (c in 0 until cpc) {
                        val col = id.lonIdx * cpc + c - w
                        if (col !in 0 until cols) continue
                        cover.put(row * cols + col, chunk.cover[r * cpc + c])
                        height.put(row * cols + col, chunk.height[r * cpc + c])
                    }
                }
            }
            return BuildingGrid(s, w, rows, cols, cover, height, stored)
        }

        private data class Cells(val south: Int, val west: Int, val rows: Int, val cols: Int)

        private fun cells(b: GeoBounds): Cells {
            val s = floor((b.south + 90.0) * CELLS_PER_DEGREE).toInt() - 1
            val n = ceil((b.north + 90.0) * CELLS_PER_DEGREE).toInt() + 1
            val w = floor((b.west + 180.0) * CELLS_PER_DEGREE).toInt() - 1
            val e = ceil((b.east + 180.0) * CELLS_PER_DEGREE).toInt() + 1
            return Cells(s, w, maxOf(2, n - s), maxOf(2, e - w))
        }

        private fun floorDiv(a: Int, b: Int) = Math.floorDiv(a, b)
    }
}
