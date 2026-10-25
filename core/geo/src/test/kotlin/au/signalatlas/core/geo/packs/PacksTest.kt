package au.signalatlas.core.geo.packs

import au.signalatlas.core.geo.ChunkId
import au.signalatlas.core.geo.DemChunk
import au.signalatlas.core.geo.DemChunkStore
import au.signalatlas.core.model.GeoBounds
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PacksTest {
    @get:Rule val tmp = TemporaryFolder()

    private val australia = GeoBounds(-44.0, 112.0, -9.0, 154.0)

    @Test fun `the catalogue has the states, eight metros and five corridors with unique ids`() {
        assertThat(PackCatalogue.states).hasSize(8)
        assertThat(PackCatalogue.metros.map { it.name }).containsExactly(
            "Sydney", "Melbourne", "Brisbane", "Perth", "Adelaide", "Canberra", "Hobart", "Darwin",
        )
        assertThat(PackCatalogue.corridors.map { it.id }).containsExactly(
            "road-hume", "road-pacific", "road-bruce", "road-stuart", "road-princes",
        )
        assertThat(PackCatalogue.all.map { it.id }.toSet()).hasSize(PackCatalogue.all.size)
    }

    @Test fun `every pack lies in Australia and its boxes are well formed`() {
        for (def in PackCatalogue.all) {
            for (b in def.area.basemapBoxes() + def.area.bounds) {
                assertThat(b.south).isLessThan(b.north)
                assertThat(b.west).isLessThan(b.east)
                assertThat(b.south).isAtLeast(australia.south - 1)
                assertThat(b.north).isAtMost(australia.north + 1)
                assertThat(b.west).isAtLeast(australia.west - 1)
                assertThat(b.east).isAtMost(australia.east + 1)
            }
            assertThat(def.area.demChunks()).isNotEmpty()
        }
    }

    @Test fun `each metro lies inside its state`() {
        val pairs = mapOf(
            "metro-sydney" to "state-nsw", "metro-melbourne" to "state-vic", "metro-brisbane" to "state-qld",
            "metro-perth" to "state-wa", "metro-adelaide" to "state-sa", "metro-canberra" to "state-act",
            "metro-hobart" to "state-tas", "metro-darwin" to "state-nt",
        )
        for ((m, s) in pairs) {
            val a = PackCatalogue.byId(m)!!.area.bounds
            val st = PackCatalogue.byId(s)!!.area.bounds
            assertThat(st.contains(a.south, a.west)).isTrue()
            assertThat(st.contains(a.north, a.east)).isTrue()
        }
    }

    @Test fun `a corridor is buffered by 10 km and follows the road rather than its bounding box`() {
        val hume = PackCatalogue.byId("road-hume")!!.area as PackArea.Corridor
        val raw = PackArea.boundsOf(hume.points)
        assertThat(hume.bounds.north - raw.north).isWithin(0.01).of(10.0 / 111.195)
        assertThat(hume.bounds.west).isLessThan(raw.west)
        val corridorChunks = hume.demChunks().size
        val boxChunks = PackArea.Box(hume.bounds).demChunks().size
        assertThat(corridorChunks).isLessThan(boxChunks)
        // Every sample of the road is covered by a stored chunk, with margin.
        val chunks = hume.demChunks()
        for (p in hume.samples()) {
            assertThat(chunks).contains(au.signalatlas.core.geo.ChunkMath.idFor(p.lat, p.lon))
        }
        // The boxes chain along the road with no gap: together they cover every sample.
        val boxes = hume.basemapBoxes()
        assertThat(boxes.size).isGreaterThan(5)
        assertThat(hume.samples().all { p -> boxes.any { it.contains(p.lat, p.lon) } }).isTrue()
    }

    @Test fun `tile counts and the size estimate`() {
        // Whole world: 1 + 4 + ... + 4^14 tiles.
        assertThat(TileCounts.total(listOf(GeoBounds(-85.06, -180.0, 85.06, 179.999)))).isEqualTo((0..14).sumOf { 1L shl (2 * it) })
        val sydney = PackCatalogue.byId("metro-sydney")!!
        val tiles = TileCounts.total(sydney.area.basemapBoxes())
        assertThat(tiles).isGreaterThan(1000)
        assertThat(PackSizes.basemapBytes(sydney.area)).isEqualTo(tiles * PackSizes.AVG_TILE_BYTES)
        // A state scales with its area and is discounted by its fill factor.
        val nsw = PackCatalogue.byId("state-nsw")!!
        assertThat(PackSizes.basemapBytes(nsw.area)).isGreaterThan(PackSizes.basemapBytes(sydney.area) * 10)
        val unfilled = TileCounts.total(nsw.area.basemapBoxes()) * PackSizes.AVG_TILE_BYTES
        assertThat(PackSizes.basemapBytes(nsw.area).toDouble()).isWithin(2.0).of(unfilled * nsw.area.basemapFill)
    }

    @Test fun `areas round trip through JSON`() {
        for (def in PackCatalogue.all + PackDef("x", "Box", PackKind.CUSTOM, PackArea.Box(GeoBounds(-34.0, 150.0, -33.0, 151.0)))) {
            assertThat(PackArea.fromJson(def.area.toJson())).isEqualTo(def.area)
        }
    }

    private fun chunk(id: ChunkId) = DemChunk(id, ShortArray(301 * 301) { (it % 97).toShort() })

    @Test fun `pinning protects a pack's chunks from eviction until it is removed`() = runBlocking<Unit> {
        val store = DemChunkStore(tmp.newFolder("dem"), { 0L }) // a budget of zero evicts anything unprotected
        val area = PackArea.Box(GeoBounds(-33.9, 151.0, -33.8, 151.1))
        val ids = area.demChunks()
        val log = ArrayList<String>()
        val content = DemPackContent(
            store,
            estimate = { it.size * 1000L },
            fetch = { chunks, _, progress ->
                log += "fetch ${chunks.size}"
                chunks.forEach { store.put(chunk(it)) }
                progress(1f)
            },
        )
        assertThat(content.estimateBytes(area)).isEqualTo(ids.size * 1000L)
        content.download("p1", 1L, area, allowMetered = false) { }
        assertThat(ids.all { store.isPinned(it) && store.has(it) }).isTrue()
        store.trim()
        assertThat(ids.all { store.has(it) }).isTrue()
        assertThat(content.sizeBytes("p1", area)).isGreaterThan(0L)

        // A second pack sharing the chunks keeps them when the first is removed.
        content.download("p2", 1L, area, false) { }
        content.remove("p1", area)
        store.trim()
        assertThat(ids.all { store.has(it) }).isTrue()
        content.remove("p2", area)
        store.trim()
        assertThat(ids.none { store.has(it) }).isTrue()
    }
}
