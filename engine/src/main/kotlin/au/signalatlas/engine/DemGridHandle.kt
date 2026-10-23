package au.signalatlas.engine

import au.signalatlas.core.geo.ChunkId
import au.signalatlas.core.geo.ChunkMath
import au.signalatlas.core.geo.DemChunkStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A DEM mosaic for the native terrain stage: the elevation chunks of a rectangle of [ChunkId]s stitched into one
 * contiguous int16 grid in a direct, native-order buffer, ready for [CoverageEngine.buildTerrainTable].
 *
 * Grid layout (what the native `DemGrid` expects): node (row, col) lies at latitude `(southIdx + row) / 1200` and
 * longitude `(westIdx + col) / 1200` degrees, row 0 = the south edge; `rows = chunksNorth * 300 + 1` and
 * `cols = chunksEast * 300 + 1`. A chunk's overlap row / column (its 301st) duplicates its neighbour's first, so it is
 * dropped except on the outer north and east edges of the rectangle.
 *
 * The chunks are [acquired][DemChunkStore.acquire] for the handle's lifetime (the cache cannot evict them) and
 * released by [close], which is idempotent. A chunk missing from the cache leaves its cells at zero and is listed in
 * [missing]: [isComplete] is then false and [CoverageEngine.buildTerrainTable] refuses the handle, because zeros
 * would be fabricated heights. Ocean is not missing: the fetcher stores real all-zero chunks for it.
 *
 * [open] reads and inflates every chunk (tens of ms each): call it off the main thread. This class lives in :engine,
 * which therefore depends on :core:geo (geo -> model, so no cycle); the alternative of an interface over chunk bytes
 * would only re-declare [DemChunkStore]'s acquire / release contract.
 */
class DemGridHandle private constructor(
    private val store: DemChunkStore,
    private val ids: List<ChunkId>,
    /** Node index of row 0: `southIdx / 1200` degrees is the latitude of the south edge. */
    val southIdx: Int,
    /** Node index of column 0: `westIdx / 1200` degrees is the longitude of the west edge. */
    val westIdx: Int,
    val rows: Int,
    val cols: Int,
    internal val samples: ByteBuffer,
    /** Chunks that were not in the cache (their cells are zero). */
    val missing: List<ChunkId>,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val isComplete: Boolean get() = missing.isEmpty()

    val isClosed: Boolean get() = closed.get()

    /** South edge latitude, degrees. */
    val southDeg: Double get() = southIdx / NODES_PER_DEGREE

    /** West edge longitude, degrees. */
    val westDeg: Double get() = westIdx / NODES_PER_DEGREE

    /** Elevation (m) at [row] and [col] of the mosaic. */
    fun heightAt(row: Int, col: Int): Int {
        require(row in 0 until rows && col in 0 until cols) { "node ($row, $col) outside $rows x $cols" }
        return samples.getShort((row.toLong() * cols + col).toInt() * 2).toInt()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) store.release(ids)
    }

    companion object {
        private const val NODES_PER_DEGREE = ChunkMath.NODES_PER_DEGREE.toDouble()

        /**
         * Stitches [chunks], which must be every chunk of one rectangle (distinct, no gaps), read from [store].
         * @throws IllegalArgumentException for an empty, gapped or oversized (over 1 GiB of samples) set.
         */
        fun open(store: DemChunkStore, chunks: Collection<ChunkId>): DemGridHandle {
            val set = chunks.toSet()
            require(set.isNotEmpty()) { "no chunks" }
            val minLat = set.minOf { it.latIdx }
            val maxLat = set.maxOf { it.latIdx }
            val minLon = set.minOf { it.lonIdx }
            val maxLon = set.maxOf { it.lonIdx }
            val nLat = maxLat - minLat + 1
            val nLon = maxLon - minLon + 1
            require(nLat.toLong() * nLon == set.size.toLong()) { "chunks do not form a rectangle: $nLat x $nLon needs ${nLat.toLong() * nLon}, got ${set.size}" }
            val rows = nLat * ChunkMath.STEPS + 1
            val cols = nLon * ChunkMath.STEPS + 1
            require(rows.toLong() * cols * 2 <= MAX_BYTES) { "mosaic of $rows x $cols nodes is too large" }
            // Node indices of the south-west corner: (latIdx / 4 - 90) * 1200 and (lonIdx / 4 - 180) * 1200.
            val southIdx = minLat * ChunkMath.STEPS - 90 * ChunkMath.NODES_PER_DEGREE
            val westIdx = minLon * ChunkMath.STEPS - 180 * ChunkMath.NODES_PER_DEGREE

            val ordered = ArrayList<ChunkId>(set.size)
            for (la in minLat..maxLat) for (lo in minLon..maxLon) ordered += ChunkId(la, lo)
            store.acquire(ordered)
            try {
                val buffer = ByteBuffer.allocateDirect(rows * cols * 2).order(ByteOrder.nativeOrder())
                val shorts = buffer.asShortBuffer()
                val missing = ArrayList<ChunkId>()
                for (id in ordered) {
                    val chunk = store.get(id)
                    if (chunk == null) {
                        missing += id
                        continue
                    }
                    // Overlap row / column only at the rectangle's north / east edge.
                    val rowCount = if (id.latIdx == maxLat) ChunkMath.NODES else ChunkMath.STEPS
                    val colCount = if (id.lonIdx == maxLon) ChunkMath.NODES else ChunkMath.STEPS
                    val col0 = (id.lonIdx - minLon) * ChunkMath.STEPS
                    val dst = shorts.duplicate()
                    for (r in 0 until rowCount) {
                        dst.position(((id.latIdx - minLat) * ChunkMath.STEPS + r) * cols + col0)
                        dst.put(chunk.samples, r * ChunkMath.NODES, colCount)
                    }
                }
                return DemGridHandle(store, ordered, southIdx, westIdx, rows, cols, buffer, missing)
            } catch (t: Throwable) {
                store.release(ordered)
                throw t
            }
        }

        private const val MAX_BYTES = 1L shl 30
    }
}
