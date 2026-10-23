package au.signalatlas.core.coverage

import au.signalatlas.engine.TerrainTable
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class TerrainTableStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun key(site: Int = 1, stamp: String = "s1", dem: Long = 7) = TableKey(stamp, site, -site, 3500, 30, dem)

    private fun table(seed: Int): TerrainTable {
        val t = ByteBuffer.allocateDirect(TerrainTable.CELLS)
        val m = ByteBuffer.allocateDirect(TerrainTable.MASK_BYTES)
        for (i in 0 until TerrainTable.CELLS) t.put(i, ((i * 31 + seed) and 0xFF).toByte())
        for (i in 0 until TerrainTable.MASK_BYTES) m.put(i, ((i + seed) and 0xFF).toByte())
        return TerrainTable(t, m)
    }

    private fun same(a: TerrainTable, b: TerrainTable) {
        assertThat(a.table.duplicate().apply { clear() }).isEqualTo(b.table.duplicate().apply { clear() })
        assertThat(a.mask.duplicate().apply { clear() }).isEqualTo(b.mask.duplicate().apply { clear() })
    }

    private fun tbl(dir: File) = dir.walkTopDown().filter { it.name.endsWith(".tbl") }.toList()

    @Test fun roundTripsTableAndMaskThroughDiskAcrossInstances() {
        val dir = tmp.newFolder()
        TerrainTableStore(dir).put(key(), table(1))
        val file = tbl(dir).single()
        assertThat(file.name).isEqualTo("1x-1_3500_30.tbl")
        assertThat(file.parentFile.name).isEqualTo("s1")
        assertThat(file.length()).isEqualTo(TerrainTableStore.FILE_BYTES.toLong())
        same(TerrainTableStore(dir).get(key())!!, table(1))
    }

    @Test fun aDifferentDemRectangleIsAMissButKeepsTheFileUntilRebuilt() {
        val dir = tmp.newFolder()
        TerrainTableStore(dir).put(key(dem = 7), table(1))
        val fresh = TerrainTableStore(dir)
        assertThat(fresh.get(key(dem = 8))).isNull()
        assertThat(tbl(dir)).hasSize(1)
        fresh.put(key(dem = 8), table(2))
        assertThat(tbl(dir)).hasSize(1) // replaced, same file name
        assertThat(TerrainTableStore(dir).get(key(dem = 7))).isNull()
        same(TerrainTableStore(dir).get(key(dem = 8))!!, table(2))
    }

    @Test fun corruptTruncatedAndForeignFilesAreMissesAndDeleted() {
        val dir = tmp.newFolder()
        val s = TerrainTableStore(dir)
        for (i in 1..3) s.put(key(i), table(i))
        val files = tbl(dir).sortedBy { it.name }
        RandomAccessFile(files[0], "rw").use { it.seek(5000); it.write(0x5A); it.write(0xA5) } // flipped table bytes
        files[1].writeBytes(files[1].readBytes().copyOf(100))
        files[2].writeBytes(ByteArray(TerrainTableStore.FILE_BYTES))
        val fresh = TerrainTableStore(dir)
        for (i in 1..3) assertThat(fresh.get(key(i))).isNull()
        assertThat(tbl(dir)).isEmpty()
        assertThat(fresh.diskUsage).isEqualTo(0)
    }

    @Test fun diskIsLruByBytes() {
        val dir = tmp.newFolder()
        val s = TerrainTableStore(dir, diskBytes = TerrainTableStore.FILE_BYTES * 2L + 10, memoryTables = 1)
        s.put(key(1), table(1))
        s.put(key(2), table(2))
        assertThat(s.get(key(1))).isNotNull() // touch 1: 2 is now the eldest on disk
        s.put(key(3), table(3))
        assertThat(tbl(dir)).hasSize(2)
        assertThat(s.diskUsage).isEqualTo(TerrainTableStore.FILE_BYTES * 2L)
        val fresh = TerrainTableStore(dir, diskBytes = TerrainTableStore.FILE_BYTES * 2L + 10)
        assertThat(fresh.get(key(2))).isNull()
        assertThat(fresh.get(key(1))).isNotNull()
        assertThat(fresh.get(key(3))).isNotNull()
    }

    @Test fun memoryIsLruByCountAndServesWithoutTheFile() {
        val dir = tmp.newFolder()
        val s = TerrainTableStore(dir, memoryTables = 2)
        val a = table(1)
        s.put(key(1), a)
        s.put(key(2), table(2))
        s.put(key(3), table(3))
        assertThat(s.memorySize).isEqualTo(2)
        tbl(dir).forEach { it.delete() }
        assertThat(s.get(key(3))).isNotNull() // memory hit, no file needed
        assertThat(s.get(key(1))).isNull() // evicted from memory, file gone
    }

    @Test fun aNewTowerStampDeletesTheOldStampsTablesAndLeftoverTempFilesAreRemoved() {
        val dir = tmp.newFolder()
        TerrainTableStore(dir).put(key(1, stamp = "old"), table(1))
        File(dir, "old/x.tbl.9.tmp").writeBytes(ByteArray(10))
        val s = TerrainTableStore(dir)
        s.put(key(2, stamp = "new"), table(2))
        assertThat(File(dir, "old").exists()).isFalse()
        assertThat(tbl(dir).map { it.parentFile.name }).containsExactly("new")
        assertThat(s.diskUsage).isEqualTo(TerrainTableStore.FILE_BYTES.toLong())
    }

    @Test fun writesAreAtomicNoPartialFilesAreVisible() {
        val dir = tmp.newFolder()
        val s = TerrainTableStore(dir)
        s.put(key(1), table(1))
        assertThat(dir.walkTopDown().filter { it.name.endsWith(".tmp") }.toList()).isEmpty()
    }
}
