package au.signalatlas.core.coverage

import au.signalatlas.core.model.Network
import au.signalatlas.engine.AreaClass
import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class CoverageTileServiceTest {
    @get:Rule val tmp = TemporaryFolder()
    private var rig: Rig? = null

    private fun rig(threads: Int = 2, memoryTiles: Int = 96) = Rig(tmp.newFolder(), threads, memoryTiles).also { rig = it }

    @After fun tearDown() { rig?.close() }

    private fun <T> java.util.concurrent.CompletableFuture<T>.await(): T = get(5, TimeUnit.SECONDS)

    private fun failure(f: java.util.concurrent.CompletableFuture<*>): Throwable =
        try { f.get(5, TimeUnit.SECONDS); error("expected failure") } catch (e: ExecutionException) { e.cause!! }

    @Test fun templateHasViewBeforeTheDataStampsAndNullWhenOff() {
        val r = rig()
        val id = r.selection.cacheId()
        assertThat(r.service.tileUrlTemplate()).isEqualTo(
            "https://coverage.signalatlas.invalid/v1/$id/rsrp/stamp1/none/{z}/{x}/{y}.png",
        )
        r.service.selection.value = r.selection.copy(view = CoverageSelection.View.PROBABILITY)
        assertThat(r.service.tileUrlTemplate()).isEqualTo(
            "https://coverage.signalatlas.invalid/v1/$id/prob/stamp1/none/{z}/{x}/{y}.png",
        )
        r.service.selection.value = null
        assertThat(r.service.tileUrlTemplate()).isNull()
        r.service.selection.value = r.selection
        r.towers.stampValue = null
        assertThat(r.service.tileUrlTemplate()).isNull()
    }

    @Test fun cacheIdIgnoresViewAndSetOrderButNotOtherFields() {
        val a = CoverageSelection(Network.TELSTRA, setOf(au.signalatlas.core.model.Tech.LTE, au.signalatlas.core.model.Tech.NR), setOf("n78", "B28"), false, CoverageSelection.View.RSRP)
        assertThat(a.copy(view = CoverageSelection.View.PROBABILITY).cacheId()).isEqualTo(a.cacheId())
        assertThat(a.copy(techs = setOf(au.signalatlas.core.model.Tech.NR, au.signalatlas.core.model.Tech.LTE), bands = setOf("B28", "n78")).cacheId())
            .isEqualTo(a.cacheId())
        for (other in listOf(a.copy(network = Network.OPTUS), a.copy(bands = null), a.copy(bands = setOf("n78")), a.copy(indoor = true),
            a.copy(techs = setOf(au.signalatlas.core.model.Tech.NR)))) {
            assertThat(other.cacheId()).isNotEqualTo(a.cacheId())
        }
        assertThat(a.cacheId()).matches("[a-z0-9_]+")
    }

    @Test fun rendersAPngAndReusesTheCachedTileForOtherViews() {
        val r = rig()
        val rsrp = r.tile(10, 900, 600).await()
        val prob = r.tile(10, 900, 600, view = CoverageSelection.View.PROBABILITY).await()
        assertThat(rsrp.take(4)).containsExactly(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()).inOrder()
        assertThat(prob).isNotEqualTo(rsrp)
        assertThat(r.renderer.renders).hasSize(1)
    }

    @Test fun concurrentRequestsForTheSameTileShareOneRender() {
        val r = rig()
        r.renderer.hold = true
        val futures = List(3) { r.tile(10, 900, 600) }
        waitUntil { r.renderer.renders.size == 1 }
        r.renderer.hold = false
        futures.forEach { it.await() }
        assertThat(r.renderer.renders).hasSize(1)
    }

    @Test fun areaClassComesFromTheSiteCountAroundTheTileCentre() {
        val r = rig()
        r.towers.sites = 45
        r.tile(10, 900, 600).await()
        assertThat(r.renderer.renders.single().area).isEqualTo(AreaClass.DENSE_URBAN)
    }

    @Test fun setVisibleCancelsRendersForTilesThatAreNotVisible() {
        val r = rig(threads = 1)
        r.renderer.hold = true
        val a = r.tile(10, 900, 600)
        waitUntil { r.renderer.renders.size == 1 } // a is in flight
        val b = r.tile(10, 901, 600) // queued behind it
        r.service.setVisible(setOf(Triple(10, 901, 600)))

        assertThat(r.renderer.renders.single().cancel.isCancelled).isTrue()
        assertThat(failure(a)).isInstanceOf(TileCancelledException::class.java)
        r.renderer.hold = false
        b.await() // visible: still renders
        assertThat(r.renderer.renders.map { it.x }).containsExactly(900, 901).inOrder()
    }

    @Test fun aQueuedTileOutsideTheVisibleSetNeverRenders() {
        val r = rig(threads = 1)
        r.renderer.hold = true
        val a = r.tile(10, 900, 600)
        waitUntil { r.renderer.renders.size == 1 }
        val b = r.tile(10, 901, 600)
        r.service.setVisible(setOf(Triple(10, 900, 600)))
        assertThat(failure(b)).isInstanceOf(TileCancelledException::class.java)
        r.renderer.hold = false
        a.await()
        assertThat(r.renderer.renders).hasSize(1)
    }

    @Test fun aTowerStampChangeMakesNewKeysAndOldUrlsStale() {
        val r = rig()
        val oldUrl = r.url(10, 900, 600)
        r.tile(10, 900, 600).await()
        r.towers.stampValue = "stamp2"
        assertThat(failure(r.tile(10, 900, 600, stamp = "stamp1"))).isInstanceOf(StaleTileException::class.java)
        r.tile(10, 900, 600, stamp = "stamp2").await()
        assertThat(r.renderer.renders).hasSize(2) // not served from stamp1's cache entry
        assertThat(r.cache.getMemory(TileKey(10, 900, 600, r.selection.cacheId(), "stamp1", "none"))).isNotNull()
        assertThat(r.cache.getMemory(TileKey(10, 900, 600, r.selection.cacheId(), "stamp2", "none"))).isNotNull()
        assertThat(oldUrl).contains("stamp1")
    }

    @Test fun aPatternsEtagMismatchIsStale() {
        val r = rig()
        val url = r.url(10, 900, 600).replace("/none/", "/other/")
        val f = r.service.tileFuture(TileUrl.parse(url.toHttpUrl())!!)
        assertThat(failure(f)).isInstanceOf(StaleTileException::class.java)
    }

    @Test fun coverageOffFailsWithCoverageOff() {
        val r = rig()
        val f = r.tile(10, 900, 600)
        f.await()
        r.service.selection.value = null
        assertThat(failure(r.tile(10, 900, 600))).isInstanceOf(CoverageOffException::class.java)
    }

    @Test fun memoryCacheStaysWithin96Tiles() {
        val r = rig(threads = 2)
        for (i in 0 until 120) r.tile(10, 900 + i, 600).await()
        assertThat(r.cache.memorySize).isEqualTo(96)
        assertThat(r.renderer.renders).hasSize(120)
        r.tile(10, 900 + 119, 600).await() // newest is still cached
        assertThat(r.renderer.renders).hasSize(120)
    }

    @Test fun rendersAreCappedAtThePoolBudget() {
        val r = rig(threads = 2)
        r.renderer.hold = true
        val fs = List(8) { r.tile(10, 900 + it, 600) }
        waitUntil { r.renderer.renders.size == 2 }
        Thread.sleep(100)
        assertThat(r.renderer.renders).hasSize(2)
        r.renderer.hold = false
        fs.forEach { it.await() }
        assertThat(r.renderer.maxConcurrent).isEqualTo(2)
    }

    @Test fun carriersArePreparedOncePerZ8RegionAndMarginIs80km() {
        val r = rig()
        // z10 tiles 900..903 x 600..603 are one z8 tile (225, 150); 904 starts the next.
        for (x in 900..903) for (y in 600..603) r.tile(10, x, y).await()
        assertThat(r.renderer.prepares.get()).isEqualTo(1)
        r.tile(10, 904, 600).await()
        assertThat(r.renderer.prepares.get()).isEqualTo(2)
        r.tile(12, 3600, 2400).await() // z12 inside region (225,150)
        assertThat(r.renderer.prepares.get()).isEqualTo(2)

        val region = TileMath.bounds(8, 225, 150)
        val q = r.towers.queries.first()
        assertThat(region.north - q.north).isWithin(0.01).of(-80.0 / 111.19)
        assertThat(region.south - q.south).isWithin(0.01).of(80.0 / 111.19)
        assertThat(q.east).isGreaterThan(region.east)
    }

    @Test fun onlyTheEightMostRecentRegionsStayPrepared() {
        val r = rig()
        for (i in 0 until 9) r.tile(8, 100 + i, 100).await()
        assertThat(r.renderer.prepares.get()).isEqualTo(9)
        // The oldest region (x=100) was evicted by the ninth, so a new tile there prepares again.
        r.tile(9, 200, 200).await() // region (100,100) again
        assertThat(r.renderer.prepares.get()).isEqualTo(10)
    }

    @Test fun valueAtDecodesTheHighestZoomCachedTileAndNamesTheBestServer() {
        val r = rig()
        val lat = -33.87
        val lon = 151.21
        assertThat(r.service.valueAt(lat, lon)).isNull()

        val z10 = TileMath.locate(lat, lon, 10)!!
        r.renderer.rsrpCode = 100 // -90 dBm
        r.tile(10, z10.x, z10.y).await()
        val coarse = r.service.valueAt(lat, lon)!!
        assertThat(coarse.rsrpDbm).isEqualTo(-90f)
        assertThat(coarse.sinrDb).isEqualTo(0f) // code 80 -> 80/4 - 20
        assertThat(coarse.probability).isWithin(1e-6f).of(51 / 255f)

        val z12 = TileMath.locate(lat, lon, 12)!!
        r.renderer.rsrpCode = 60 // -110 dBm
        r.tile(12, z12.x, z12.y).await()
        assertThat(r.service.valueAt(lat, lon)!!.rsrpDbm).isEqualTo(-110f) // the higher zoom wins

        // best index 1 of the prepared region -> batch row (2 - 1 - 1) = 0 -> Telstra n78 at (-33.9, 151.2)
        val best = r.service.valueAt(lat, lon)!!.best!!
        assertThat(best.network).isEqualTo(Network.TELSTRA)
        assertThat(best.bandLabel).isEqualTo("n78")
        assertThat(best.distanceM).isWithin(1.0).of(TileMath.distanceM(lat, lon, -33.9, 151.2))
    }

    @Test fun valueAtHasNoRsrpWhereTheCodeIsZeroAndIsNullForOtherData() {
        val r = rig()
        r.renderer.rsrpCode = 0
        val at = TileMath.locate(-33.87, 151.21, 10)!!
        r.tile(10, at.x, at.y).await()
        val s = r.service.valueAt(-33.87, 151.21)!!
        assertThat(s.rsrpDbm).isNull()
        assertThat(s.sinrDb).isNull()
        assertThat(s.best).isNull()
        r.towers.stampValue = "stamp2"
        assertThat(r.service.valueAt(-33.87, 151.21)).isNull()
    }
}
