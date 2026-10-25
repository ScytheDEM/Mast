package au.signalatlas.core.roads

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RoadSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fixture = javaClass.getResourceAsStream("/gundagai_z14_14932_9897.mvt")!!.readBytes()
    private val archive = TestArchive(runTile = fixture).bytes

    private class CountingBytes(val b: ByteArray) : RangeSource {
        var reads = 0
        override fun read(offset: Long, length: Int): ByteArray {
            reads++
            return if (offset >= b.size) ByteArray(0) else b.copyOfRange(offset.toInt(), minOf(b.size.toLong(), offset + length).toInt())
        }
    }

    @Test fun fetchedTilesAreCachedOnDiskAndServedOffline() = runTest {
        val src = CountingBytes(archive)
        var opens = 0
        val dir = File(tmp.root, "roads")
        val s = PmtilesRoadSource({ opens++; PmtilesReader.open(src) }, dir)
        val first = s.roads(14932, 9897)!!
        assertThat(first).isNotEmpty()
        assertThat(dir.listFiles()!!.map { it.name }).contains("14_14932_9897.mvt")
        val reads = src.reads
        // A new process with no network: the cached tile still comes back.
        val offline = PmtilesRoadSource({ throw IOException("offline") }, dir)
        assertThat(offline.roads(14932, 9897)!!.size).isEqualTo(first.size)
        assertThat(src.reads).isEqualTo(reads)
        assertThat(opens).isEqualTo(1)
    }

    @Test fun tilesAbsentFromTheArchiveAreCachedAsEmpty() = runTest {
        val dir = File(tmp.root, "roads")
        val s = PmtilesRoadSource({ PmtilesReader.open(CountingBytes(archive)) }, dir)
        assertThat(s.roads(0, 0)).isEmpty()
        assertThat(File(dir, "14_0_0.mvt").length()).isEqualTo(0)
        assertThat(PmtilesRoadSource({ throw IOException("offline") }, dir).roads(0, 0)).isEmpty()
    }

    @Test fun unreachableArchiveIsNullAndRetriedAfterAWhile() = runTest {
        var now = 1_000_000L
        var online = false
        var opens = 0
        val s = PmtilesRoadSource(
            { opens++; if (!online) throw IOException("offline") else PmtilesReader.open(CountingBytes(archive)) },
            File(tmp.root, "roads"), clockMs = { now },
        )
        assertThat(s.roads(14932, 9897)).isNull()
        online = true
        assertThat(s.roads(14932, 9897)).isNull() // backing off
        assertThat(opens).isEqualTo(1)
        now += PmtilesRoadSource.RETRY_MS + 1
        assertThat(s.roads(14932, 9897)).isNotEmpty()
    }

    @Test fun expiredCacheEntriesAreRefetched() = runTest {
        var now = 0L
        val src = CountingBytes(archive)
        val dir = File(tmp.root, "roads")
        val s = PmtilesRoadSource({ PmtilesReader.open(src) }, dir, ttlMs = 1_000, clockMs = { now })
        s.roads(14932, 9897)
        now = System.currentTimeMillis() + 10_000 // file mtime is real time
        val before = src.reads
        s.roads(14932, 9897)
        assertThat(src.reads).isGreaterThan(before)
    }

    @Test fun graphProviderLoadsInTheBackgroundAndNeverBlocks() = runTest {
        val seen = ArrayList<Pair<Int, Int>>()
        val roads = RoadTileDecoder.decode(fixture, 14932, 9897)
        val source = object : RoadSource {
            override suspend fun roads(x: Int, y: Int): List<RoadWay>? { seen += x to y; return if (x == 14932 && y == 9897) roads else emptyList() }
        }
        val centre = roads.first().points.first()
        val g = RoadGraphProvider.load(source, centre.lat, centre.lon, 3_000.0)!!
        assertThat(g.edgeCount).isGreaterThan(0)
        assertThat(seen.size).isAtLeast(4) // several z14 tiles within 3 km
        assertThat(seen.toSet().size).isEqualTo(seen.size)
        val none = object : RoadSource { override suspend fun roads(x: Int, y: Int): List<RoadWay>? = null }
        assertThat(RoadGraphProvider.load(none, centre.lat, centre.lon, 3_000.0)).isNull()
    }
}
