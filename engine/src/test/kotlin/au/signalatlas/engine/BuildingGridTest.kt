package au.signalatlas.engine

import au.signalatlas.core.geo.BuildingChunk
import au.signalatlas.core.geo.BuildingChunkStore
import au.signalatlas.core.geo.ChunkId
import au.signalatlas.core.model.GeoBounds
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.After
import org.junit.Test

/** JVM-only: [BuildingGrid] assembly from building chunks (M2 rule 5a). */
class BuildingGridTest {
    private val dir = File.createTempFile("bldgrid", "").also { it.delete(); it.mkdirs() }
    private val store = BuildingChunkStore(dir, { Long.MAX_VALUE })

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun cellsComeFromTheirChunksAcrossChunkEdgesAndMissingChunksAreNoData() {
        // Bounds straddling the chunk edge at 149.0 E: chunk (219, 1316) stored, its west neighbour not.
        val east = ChunkId(219, 1316)
        val cover = ByteArray(625) { (it % 25 * 10).toByte() } // column c -> 10 c
        store.put(BuildingChunk(east, cover, ByteArray(625) { 40 }))
        val b = GeoBounds(-35.2, 148.98, -35.15, 149.03)
        val grid = BuildingGrid.open(store, b)
        assertThat(BuildingGrid.chunksFor(b)).containsAtLeast(east, ChunkId(219, 1315))
        assertThat(grid.stored).containsExactly(east)
        assertThat(grid.southDeg).isWithin(1e-9).of(-35.21)
        assertThat(grid.westDeg).isWithin(1e-9).of(148.97)
        val col149 = 3 // 148.97 + 0.03
        assertThat(grid.coverCode(2, col149)).isEqualTo(0)  // first column of the east chunk
        assertThat(grid.coverCode(2, col149 + 2)).isEqualTo(20)
        assertThat(grid.coverCode(2, col149 - 1)).isEqualTo(BuildingChunk.NO_DATA) // west chunk missing
        assertThat(grid.rows).isEqualTo(7)
        assertThat(grid.cols).isEqualTo(7)
    }
}
