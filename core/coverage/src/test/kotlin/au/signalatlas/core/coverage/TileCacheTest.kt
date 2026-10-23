package au.signalatlas.core.coverage

import au.signalatlas.engine.RenderedTile
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class TileCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun key(x: Int, stamp: String = "s1") = TileKey(10, x, 5, "sel", stamp, "none")

    private fun tile(x: Int, fill: Int = x): RenderedTile {
        val n = RenderedTile.PIXELS
        return RenderedTile(10, x, 5, ByteArray(n) { fill.toByte() }, ByteArray(n) { 2 }, ByteArray(n) { 3 }, ShortArray(n) { (it % 7).toShort() })
    }

    private fun files(dir: File) = dir.listFiles { f -> f.name.endsWith(".tile") }!!.toList()

    @Test fun memoryIsLruByCount() {
        val cache = TileCache(tmp.newFolder(), memoryTiles = 3)
        for (x in 1..3) cache.put(key(x), tile(x))
        assertThat(cache.getMemory(key(1))).isNotNull() // touch 1: 2 is now eldest
        cache.put(key(4), tile(4))
        assertThat(cache.memorySize).isEqualTo(3)
        assertThat(cache.getMemory(key(2))).isNull()
        assertThat(cache.getMemory(key(1))).isNotNull()
        assertThat(cache.getMemory(key(4))).isNotNull()
    }

    @Test fun diskRoundTripsAllCodesAcrossInstances() {
        val dir = tmp.newFolder()
        val a = TileCache(dir)
        a.put(key(1), tile(1))
        a.flush()
        val fresh = TileCache(dir).getDisk(key(1))!!
        val orig = tile(1)
        assertThat(fresh.rsrp).isEqualTo(orig.rsrp)
        assertThat(fresh.sinr).isEqualTo(orig.sinr)
        assertThat(fresh.prob).isEqualTo(orig.prob)
        assertThat(fresh.best).isEqualTo(orig.best)
        assertThat(fresh.x).isEqualTo(1)
    }

    @Test fun diskIsLruByBytes() {
        val dir = tmp.newFolder()
        val cache = TileCache(dir, memoryTiles = 1, diskBytes = TileCache.FILE_BYTES * 2L + 10)
        cache.put(key(1), tile(1)); cache.flush()
        cache.put(key(2), tile(2)); cache.flush()
        assertThat(cache.getDisk(key(1))).isNotNull() // touch 1: 2 is now eldest
        cache.put(key(3), tile(3)); cache.flush()
        assertThat(files(dir)).hasSize(2)
        assertThat(cache.diskUsage).isEqualTo(TileCache.FILE_BYTES * 2L)
        assertThat(cache.getDisk(key(2))).isNull()
        assertThat(cache.getDisk(key(1))).isNotNull()
        assertThat(cache.getDisk(key(3))).isNotNull()
    }

    @Test fun aCorruptDiskEntryIsIgnoredAndDeleted() {
        val dir = tmp.newFolder()
        val cache = TileCache(dir)
        cache.put(key(1), tile(1)); cache.flush()
        val file = files(dir).single()
        RandomAccessFile(file, "rw").use { it.seek(1000); it.write(0x5A); it.write(0xA5) } // flip payload bytes

        val reopened = TileCache(dir)
        assertThat(reopened.getDisk(key(1))).isNull()
        assertThat(file.exists()).isFalse()
        assertThat(reopened.diskUsage).isEqualTo(0)
    }

    @Test fun truncatedAndForeignFilesAreMisses() {
        val dir = tmp.newFolder()
        val cache = TileCache(dir)
        cache.put(key(1), tile(1)); cache.flush()
        files(dir).single().let { it.writeBytes(it.readBytes().copyOf(100)) }
        assertThat(TileCache(dir).getDisk(key(1))).isNull()
        assertThat(files(dir)).isEmpty()
    }

    @Test fun anyKeyFieldChangeIsAMiss() {
        val dir = tmp.newFolder()
        val cache = TileCache(dir)
        cache.put(key(1), tile(1)); cache.flush()
        val fresh = TileCache(dir)
        assertThat(fresh.getDisk(key(1, stamp = "s2"))).isNull()
        assertThat(fresh.getDisk(key(1).copy(patternsEtag = "e"))).isNull()
        assertThat(fresh.getDisk(key(1).copy(engineVersion = 3))).isNull()
        assertThat(fresh.getDisk(key(1).copy(selectionId = "other"))).isNull()
        assertThat(fresh.getDisk(key(1).copy(terrainVersion = 7))).isNull()
        assertThat(fresh.getDisk(key(1))).isNotNull()
    }

    private fun counted(x: Int, covered: Int, incomplete: Int): RenderedTile {
        val n = RenderedTile.PIXELS
        return RenderedTile(10, x, 5, ByteArray(n) { 9 }, ByteArray(n), ByteArray(n), ShortArray(n), covered, incomplete)
    }

    @Test fun pixelCountsAndSoTheAccuracyLabelSurviveTheDiskCache() {
        val dir = tmp.newFolder()
        val terrain = key(1).copy(terrainVersion = 99)
        val cache = TileCache(dir)
        cache.put(terrain, counted(1, covered = 1000, incomplete = 40)) // 96% complete: TERRAIN
        cache.put(key(2).copy(terrainVersion = 99), counted(2, covered = 1000, incomplete = 400))
        cache.put(key(3), counted(3, covered = 1000, incomplete = 0)) // no terrain version: QUICK whatever the counts
        cache.flush()

        val fresh = TileCache(dir)
        val a = fresh.getDisk(terrain)!!
        assertThat(a.coveredPixels).isEqualTo(1000)
        assertThat(a.terrainIncompletePixels).isEqualTo(40)
        assertThat(Accuracy.of(terrain, a)).isEqualTo(Accuracy.TERRAIN)
        assertThat(Accuracy.of(key(2).copy(terrainVersion = 99), fresh.getDisk(key(2).copy(terrainVersion = 99))!!)).isEqualTo(Accuracy.QUICK)
        assertThat(Accuracy.of(key(3), fresh.getDisk(key(3))!!)).isEqualTo(Accuracy.QUICK)
    }

    @Test fun theNinetyFivePercentRuleIsInclusiveAndAnEmptyTileIsQuick() {
        val k = key(1).copy(terrainVersion = 5)
        assertThat(Accuracy.of(k, counted(1, 1000, 50))).isEqualTo(Accuracy.TERRAIN)
        assertThat(Accuracy.of(k, counted(1, 1000, 51))).isEqualTo(Accuracy.QUICK)
        assertThat(Accuracy.of(k, counted(1, 0, 0))).isEqualTo(Accuracy.QUICK) // nothing predicted with terrain
        assertThat(Accuracy.of(key(1), counted(1, 0, 0))).isEqualTo(Accuracy.QUICK)
    }

    @Test fun aVersionOneFileWithoutCountsIsAMissAndIsDeleted() {
        val dir = tmp.newFolder()
        val cache = TileCache(dir)
        cache.put(key(1), tile(1)); cache.flush()
        val file = files(dir).single()
        // Rewrite as the old layout: version 1, no counts (8 bytes shorter), valid CRC over that.
        val old = java.nio.ByteBuffer.allocate(TileCache.FILE_BYTES - 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        old.put(file.readBytes(), 0, TileCache.FILE_BYTES - 12)
        old.putInt(4, 1)
        old.putInt(java.util.zip.CRC32().apply { update(old.array(), 0, TileCache.FILE_BYTES - 12) }.value.toInt())
        file.writeBytes(old.array())
        assertThat(TileCache(dir).getDisk(key(1))).isNull()
        assertThat(file.exists()).isFalse()
    }

    @Test fun summaryIsTerrainOnlyWhenEveryTileIsAndPartialWhenSomeAre() {
        assertThat(Accuracy.summary(emptyList())).isEqualTo(Accuracy.QUICK)
        assertThat(Accuracy.summary(listOf(Accuracy.QUICK, Accuracy.QUICK))).isEqualTo(Accuracy.QUICK)
        assertThat(Accuracy.summary(listOf(Accuracy.TERRAIN, Accuracy.TERRAIN))).isEqualTo(Accuracy.TERRAIN)
        assertThat(Accuracy.summary(listOf(Accuracy.TERRAIN, Accuracy.QUICK))).isEqualTo(Accuracy.PARTIAL)
    }
}
