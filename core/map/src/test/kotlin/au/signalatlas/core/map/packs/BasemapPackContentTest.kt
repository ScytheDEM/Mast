package au.signalatlas.core.map.packs

import au.signalatlas.core.geo.packs.PackArea
import au.signalatlas.core.model.GeoBounds
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class BasemapPackContentTest {
    private class Fake : OfflineBackend {
        val regions = LinkedHashMap<Long, BasemapRegion>()
        var next = 1L
        var failDownloadsAfter = Int.MAX_VALUE
        val downloaded = ArrayList<Long>()
        val created = ArrayList<GeoBounds>()

        override suspend fun list() = regions.values.toList()
        override suspend fun create(styleUrl: String, bounds: GeoBounds, maxZoom: Int, metadata: String): BasemapRegion {
            assertThat(maxZoom).isEqualTo(14)
            created += bounds
            return BasemapRegion(next++, metadata, false, 0).also { regions[it.id] = it }
        }
        override suspend fun download(id: Long, progress: (Float) -> Unit) {
            if (downloaded.size >= failDownloadsAfter) throw IOException("offline")
            downloaded += id
            progress(0.5f)
            regions[id] = regions.getValue(id).copy(complete = true, bytes = 1000)
        }
        override suspend fun status(id: Long) = regions[id]
        override suspend fun delete(id: Long) { regions.remove(id) }
    }

    private val corridor = PackArea.Corridor(
        listOf(
            au.signalatlas.core.geo.packs.LatLon(-33.87, 151.21),
            au.signalatlas.core.geo.packs.LatLon(-34.75, 149.72),
            au.signalatlas.core.geo.packs.LatLon(-36.08, 146.92),
        ),
    )

    @Test fun `downloads one region per box and reports progress`() = runBlocking<Unit> {
        val backend = Fake()
        val content = BasemapPackContent(backend) { "file:///style.json" }
        val seen = ArrayList<Float>()
        content.download("p", 1L, corridor, false) { seen += it }
        val n = corridor.basemapBoxes().size
        assertThat(n).isGreaterThan(1)
        assertThat(backend.regions).hasSize(n)
        assertThat(backend.regions.values.all { it.complete }).isTrue()
        assertThat(seen).isInOrder()
        assertThat(seen.last()).isEqualTo(1f)
        assertThat(content.sizeBytes("p", corridor)).isEqualTo(1000L * n)
    }

    @Test fun `an interrupted download resumes without recreating or refetching finished regions`() = runBlocking<Unit> {
        val backend = Fake().apply { failDownloadsAfter = 1 }
        val content = BasemapPackContent(backend) { "file:///style.json" }
        val n = corridor.basemapBoxes().size
        try {
            content.download("p", 1L, corridor, false) { }
            error("should fail")
        } catch (_: IOException) { }
        assertThat(backend.regions.values.count { it.complete }).isEqualTo(1)
        backend.failDownloadsAfter = Int.MAX_VALUE
        content.download("p", 1L, corridor, false) { }
        assertThat(backend.created).hasSize(n) // none created twice
        assertThat(backend.downloaded).hasSize(n) // none downloaded twice
        assertThat(backend.regions.values.all { it.complete }).isTrue()
    }

    @Test fun `an update replaces the old generation only once the new one is complete`() = runBlocking<Unit> {
        val backend = Fake()
        val content = BasemapPackContent(backend) { "file:///style.json" }
        val n = corridor.basemapBoxes().size
        content.download("p", 1L, corridor, false) { }
        backend.failDownloadsAfter = backend.downloaded.size // the update fails at once
        try { content.download("p", 2L, corridor, false) { } } catch (_: IOException) { }
        assertThat(backend.regions.values.count { it.complete }).isEqualTo(n) // the old map is intact
        backend.failDownloadsAfter = Int.MAX_VALUE
        content.download("p", 2L, corridor, false) { }
        assertThat(backend.regions).hasSize(n)
        assertThat(backend.regions.values.all { BasemapPackContent.Meta.parse(it.metadata)!!.gen == 2L }).isTrue()
    }

    @Test fun `remove deletes only that pack's regions`() = runBlocking<Unit> {
        val backend = Fake()
        val content = BasemapPackContent(backend) { "file:///style.json" }
        val box = PackArea.Box(GeoBounds(-34.0, 150.0, -33.0, 151.0))
        content.download("a", 1L, box, false) { }
        content.download("b", 1L, box, false) { }
        content.remove("a", box)
        assertThat(backend.regions.values.map { BasemapPackContent.Meta.parse(it.metadata)!!.pack }).containsExactly("b")
    }

    @Test fun `a pack that is complete needs no style`() = runBlocking<Unit> {
        val backend = Fake()
        val box = PackArea.Box(GeoBounds(-34.0, 150.0, -33.0, 151.0))
        BasemapPackContent(backend) { "file:///style.json" }.download("a", 1L, box, false) { }
        BasemapPackContent(backend) { throw IOException("offline") }.download("a", 1L, box, false) { }
    }
}
