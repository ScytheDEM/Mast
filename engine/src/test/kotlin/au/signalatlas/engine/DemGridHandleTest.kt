package au.signalatlas.engine

import au.signalatlas.core.geo.ChunkId
import au.signalatlas.core.geo.ChunkMath
import au.signalatlas.core.geo.DemChunk
import au.signalatlas.core.geo.DemChunkStore
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** JVM-only: the mosaic assembly of [DemGridHandle] over a real [DemChunkStore] in a temp directory. */
class DemGridHandleTest {
    private lateinit var dir: File
    private var budget = Long.MAX_VALUE
    private lateinit var store: DemChunkStore

    @Before fun setUp() {
        dir = File.createTempFile("demgrid", "").also { it.delete(); it.mkdirs() }
        store = DemChunkStore(dir, { budget })
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    /** A smooth-ish field of the global node index, so neighbouring chunks agree on their shared edge. */
    private fun height(globalRow: Int, globalCol: Int): Int = (globalRow * 7 + globalCol * 3) % 3000 - 1000

    private fun chunk(id: ChunkId): DemChunk {
        val row0 = id.latIdx * ChunkMath.STEPS
        val col0 = id.lonIdx * ChunkMath.STEPS
        val s = ShortArray(ChunkMath.NODES * ChunkMath.NODES) { k ->
            height(row0 + k / ChunkMath.NODES, col0 + k % ChunkMath.NODES).toShort()
        }
        return DemChunk(id, s)
    }

    private fun putAll(ids: List<ChunkId>) = ids.forEach { store.put(chunk(it)) }

    private val grid = listOf(ChunkId(217, 1314), ChunkId(217, 1315), ChunkId(218, 1314), ChunkId(218, 1315))

    @Test fun stitchesChunksDroppingTheOverlapExceptAtTheNorthAndEastEdge() {
        putAll(grid)
        DemGridHandle.open(store, grid).use { h ->
            assertThat(h.isComplete).isTrue()
            assertThat(h.rows).isEqualTo(601)
            assertThat(h.cols).isEqualTo(601)
            // Node (r, c) of the mosaic is global node (217 * 300 + r, 1314 * 300 + c) of the chunk lattice.
            for ((r, c) in listOf(0 to 0, 0 to 299, 0 to 300, 0 to 301, 299 to 299, 300 to 300, 301 to 300, 300 to 301, 599 to 599,
                600 to 600, 600 to 0, 0 to 600, 450 to 123)) {
                assertThat(h.heightAt(r, c)).isEqualTo(height(217 * 300 + r, 1314 * 300 + c))
            }
            assertThat(h.samples.order()).isEqualTo(ByteOrder.nativeOrder())
            assertThat(h.samples.isDirect).isTrue()
            assertThat(h.samples.capacity()).isEqualTo(601 * 601 * 2)
            // Every node checked against the lattice, not just a sample of them.
            var wrong = 0
            for (r in 0 until h.rows) for (c in 0 until h.cols) {
                if (h.heightAt(r, c) != height(217 * 300 + r, 1314 * 300 + c)) wrong++
            }
            assertThat(wrong).isEqualTo(0)
        }
    }

    @Test fun georeferenceIsTheChunkSouthWestCornerInNodeIndices() {
        putAll(listOf(ChunkId(217, 1314)))
        DemGridHandle.open(store, listOf(ChunkId(217, 1314))).use { h ->
            // latIdx 217 -> -35.75 degrees, lonIdx 1314 -> 148.5 degrees; 1200 nodes per degree.
            assertThat(h.southIdx).isEqualTo(-35.75.times(1200).toInt())
            assertThat(h.westIdx).isEqualTo(148.5.times(1200).toInt())
            assertThat(h.southDeg).isEqualTo(-35.75)
            assertThat(h.westDeg).isEqualTo(148.5)
            assertThat(h.rows).isEqualTo(301)
            assertThat(h.cols).isEqualTo(301)
            val id = ChunkId(217, 1314)
            assertThat(h.southDeg).isEqualTo(id.southDeg)
            assertThat(h.westDeg).isEqualTo(id.westDeg)
        }
    }

    @Test fun aMissingChunkIsReportedAndLeftZeroNotFabricated() {
        putAll(listOf(ChunkId(217, 1314), ChunkId(218, 1314), ChunkId(218, 1315)))
        DemGridHandle.open(store, grid).use { h ->
            assertThat(h.isComplete).isFalse()
            assertThat(h.missing).containsExactly(ChunkId(217, 1315))
            assertThat(h.heightAt(10, 10)).isEqualTo(height(217 * 300 + 10, 1314 * 300 + 10))
            assertThat(h.heightAt(10, 400)).isEqualTo(0)  // inside the missing chunk
            assertThat(h.heightAt(400, 400)).isEqualTo(height(218 * 300 + 100, 1315 * 300 + 100))
        }
    }

    @Test fun rejectsEmptyAndNonRectangularSets() {
        assertThrows(IllegalArgumentException::class.java) { DemGridHandle.open(store, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { DemGridHandle.open(store, grid.dropLast(1)) }
        assertThrows(IllegalArgumentException::class.java) {
            DemGridHandle.open(store, listOf(ChunkId(217, 1314), ChunkId(217, 1316)))
        }
        // Duplicates collapse; a single repeated chunk is a 1 x 1 rectangle.
        putAll(listOf(ChunkId(217, 1314)))
        DemGridHandle.open(store, listOf(ChunkId(217, 1314), ChunkId(217, 1314))).use { assertThat(it.rows).isEqualTo(301) }
    }

    @Test fun refusesAMosaicBeyondTheMemoryCap() {
        val wide = (0 until 120).map { ChunkId(217, 1000 + it) } + (0 until 120).map { ChunkId(218, 1000 + it) }
        assertThrows(IllegalArgumentException::class.java) {
            DemGridHandle.open(store, (217..340).flatMap { la -> (1000..1200).map { lo -> ChunkId(la, lo) } })
        }
        assertThat(wide).hasSize(240)
    }

    @Test fun chunksStayPinnedWhileOpenAndAreReleasedOnCloseIdempotently() {
        putAll(grid)
        val h = DemGridHandle.open(store, grid)
        budget = 0
        store.trim()
        for (id in grid) assertThat(store.has(id)).isTrue()  // acquired: not evicted though the budget is zero
        assertThat(h.isClosed).isFalse()
        h.close()
        h.close()  // a second close must not release twice (counted acquire / release)
        assertThat(h.isClosed).isTrue()
        store.trim()
        for (id in grid) assertThat(store.has(id)).isFalse()
    }

    @Test fun nestedHandlesNeedBothToCloseBeforeEviction() {
        putAll(grid)
        val a = DemGridHandle.open(store, grid)
        val b = DemGridHandle.open(store, grid)
        budget = 0
        a.close()
        store.trim()
        assertThat(store.has(grid[0])).isTrue()
        b.close()
        store.trim()
        assertThat(store.has(grid[0])).isFalse()
    }
}
