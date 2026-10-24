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

    private fun rig(threads: Int = 2, memoryTiles: Int = 96, towers: FakeTowers = FakeTowers()) =
        Rig(tmp.newFolder(), threads, memoryTiles, towers).also { rig = it }

    @After fun tearDown() { rig?.close() }

    private fun <T> java.util.concurrent.CompletableFuture<T>.await(): T = get(5, TimeUnit.SECONDS)

    private fun failure(f: java.util.concurrent.CompletableFuture<*>): Throwable =
        try { f.get(5, TimeUnit.SECONDS); error("expected failure") } catch (e: ExecutionException) { e.cause!! }

    @Test fun templateHasViewBeforeTheDataStampsAndNullWhenOff() {
        val r = rig()
        val id = r.selection.cacheId()
        assertThat(r.service.tileUrlTemplate()).isEqualTo(
            "https://coverage.signalatlas.invalid/v1/$id/rsrp/stamp1/none/t0/{z}/{x}/{y}.png",
        )
        r.service.selection.value = r.selection.copy(view = CoverageSelection.View.PROBABILITY)
        assertThat(r.service.tileUrlTemplate()).isEqualTo(
            "https://coverage.signalatlas.invalid/v1/$id/prob/stamp1/none/t0/{z}/{x}/{y}.png",
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

    @Test fun optusAndTpgSelectionsAlsoQueryTheJointVentureCarriers() {
        val jv = Network.OPTUS_TPG_JV
        assertThat(rig().selection.networks()).containsExactly(Network.TELSTRA)
        assertThat(rig().selection.copy(network = Network.NBN).networks()).containsExactly(Network.NBN)
        for (n in listOf(Network.OPTUS, Network.TPG)) {
            val r = rig()
            r.service.selection.value = r.selection.copy(network = n)
            val sel = r.service.selection.value!!
            r.towers.networkQueries.clear()
            val url = "https://${CoverageTileService.HOST}/v1/${sel.cacheId()}/rsrp/stamp1/none/t0/10/900/600.png"
            r.service.tileFuture(TileUrl.parse(url.toHttpUrl())!!).await()
            assertThat(r.towers.networkQueries.first()).containsExactly(n, jv)
        }
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

    /**
     * Rule 5 as amended 2026-10-24 (tile-seam bug): the class belongs to each carrier, from the sites around its own
     * site, never to the tile, so adjacent tiles evaluate every carrier with the same path-loss model.
     */
    @Test fun areaClassComesFromTheSiteCountAroundEachCarriersSite() {
        val r = rig()
        // fakeBatch: row 0 at (-33.9, 151.2) in a dense area, row 1 at (-33.8, 151.3) in a rural one.
        r.towers.sitesAt = { lat, _ -> if (lat < -33.85) 45 else 2 }
        r.tile(10, 900, 600).await()
        assertThat(r.renderer.preparedAreas.single()).containsExactly(AreaClass.DENSE_URBAN, AreaClass.RURAL).inOrder()
        assertThat(r.towers.siteCountQueries).containsExactly(-33.9 to 151.2, -33.8 to 151.3)
    }

    @Test fun adjacentTilesAcrossADensityStepShareTheCarriersClasses() {
        val r = rig()
        // The tile-centre class would differ between these neighbours; the carriers' classes must not.
        r.towers.sitesAt = { _, lon -> if (lon < 151.25) 45 else 2 }
        val west = TileMath.locate(-33.85, 151.249, 13)!!
        r.tile(13, west.x, west.y).await()
        r.tile(13, west.x + 1, west.y).await()
        assertThat(r.renderer.renders).hasSize(2)
        assertThat(r.renderer.renders.map { it.prepared }.distinct()).hasSize(1)  // one prepared region, one class per carrier
        assertThat(r.renderer.preparedAreas.single()).containsExactly(AreaClass.DENSE_URBAN, AreaClass.RURAL).inOrder()
        // Site counts are cached per site: a second region over the same sites asks no more.
        val asked = r.towers.siteCountQueries.size
        r.tile(13, west.x + 40, west.y + 40).await()
        assertThat(r.towers.siteCountQueries.size).isEqualTo(asked)
    }

    /**
     * Seam hypothesis 2: tiles on either side of a z8 region border are prepared from different regions, which must both
     * hold every carrier that can reach the border (each region is its z8 tile + 80 km, the engine's R_max cap).
     */
    @Test fun tilesEitherSideOfARegionBorderSeeTheSameCarriersNearIt() {
        val r = rig()
        val all = bigBatch(4)
        // Sites 50 km west and east of the z8 border at lon 151.875 (z8 x = 235 | 236), and 100 km east (out of reach).
        val lon = doubleArrayOf(151.875 - 0.54, 151.875 + 0.54, 151.875 + 1.08, 151.875 - 0.01)
        for (i in 0 until 4) { all.siteLon[i] = lon[i]; all.siteLat[i] = -33.87 }
        r.towers.batchFor = { b -> all.subset((0 until all.size).filter { b.contains(all.siteLat[it], all.siteLon[it]) }.toIntArray()) }
        val west = TileMath.locate(-33.87, 151.874, 13)!!
        val east = TileMath.locate(-33.87, 151.876, 13)!!
        assertThat(east.x).isEqualTo(west.x + 1)
        assertThat(west.x shr 5).isNotEqualTo(east.x shr 5)  // different z8 regions
        r.tile(13, west.x, west.y).await()
        r.tile(13, east.x, east.y).await()
        val batches = r.renderer.preparedBatches
        assertThat(batches).hasSize(2)
        fun near(b: au.signalatlas.core.data.CarrierBatch) =
            (0 until b.size).map { b.siteLon[it] }.filter { kotlin.math.abs(it - 151.875) <= 0.75 }.sorted()
        assertThat(near(batches[0])).isEqualTo(near(batches[1]))
        assertThat(near(batches[0])).hasSize(3)
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

    @Test fun onlyTheSixteenMostRecentRegionsStayPrepared() {
        val r = rig()
        for (i in 0 until 17) r.tile(8, 100 + i, 100).await()
        assertThat(r.renderer.prepares.get()).isEqualTo(17)
        // The oldest region (x=100) was evicted by the seventeenth, so a new tile there prepares again.
        r.tile(9, 200, 200).await() // region (100,100) again
        assertThat(r.renderer.prepares.get()).isEqualTo(18)
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
        assertThat(s.probability).isNull()
        assertThat(s.best).isNull()
        r.towers.stampValue = "stamp2"
        assertThat(r.service.valueAt(-33.87, 151.21)).isNull()
    }

    private fun spanDeg(b: au.signalatlas.core.model.GeoBounds) = b.north - b.south

    @Test fun anOversizedRegionFallsBackToTheZ10TileAndIsPrepared() {
        // Only the z8 + 80 km query is too big; the z10 + 80 km query fits.
        val towers = FakeTowers().apply { batchFor = { if (spanDeg(it) > 2.4) bigBatch(70_000) else bigBatch(1000) } }
        val r = rig(towers = towers)
        r.tile(10, 900, 600).await()
        assertThat(r.renderer.preparedSizes).containsExactly(1000)
        assertThat(r.towers.queries).hasSize(2) // the z8 attempt, then the z10 one
        assertThat(r.warnings).isEmpty()
        // Another tile in the same z10 tile reuses the fallback; the level-0 overflow is remembered too.
        r.tile(11, 1800, 1200).await()
        assertThat(r.towers.queries).hasSize(2)
        assertThat(r.renderer.prepares.get()).isEqualTo(1)
        // A different z10 tile in the same z8 region needs its own fallback query (but not another z8 one).
        r.tile(10, 901, 600).await()
        assertThat(r.towers.queries).hasSize(3)
    }

    @Test fun stillOversizedBatchesAreCulledByRangeAndLogged() {
        // Every second carrier is ~550 km away and cannot reach the tile.
        val r = rig(towers = FakeTowers().apply { batchFor = { bigBatch(70_000, farEvery = 2) } })
        val at = TileMath.locate(-33.87, 151.21, 10)!!
        r.tile(10, at.x, at.y).await()
        assertThat(r.renderer.preparedSizes.single()).isEqualTo(35_000)
        assertThat(r.warnings.single()).contains("culled to 35000")
    }

    @Test fun ifCullingIsNotEnoughTheNearest65535AreKeptAndLogged() {
        val r = rig(towers = FakeTowers().apply { batchFor = { bigBatch(70_000) } })
        val at = TileMath.locate(-33.87, 151.21, 10)!!
        r.tile(10, at.x, at.y).await()
        assertThat(r.renderer.preparedSizes.single()).isEqualTo(65_535)
        assertThat(r.warnings.single()).contains("nearest")
        assertThat(r.cache.getMemory(TileKey(10, at.x, at.y, r.selection.cacheId(), "stamp1", "none"))).isNotNull()
    }

    @Test fun valueAtKeepsTheCodesWhenTheRegionIsGone() {
        val r = rig()
        val at = TileMath.locate(-33.87, 151.21, 10)!!
        r.tile(10, at.x, at.y).await()
        assertThat(r.service.valueAt(-33.87, 151.21)!!.best).isNotNull()
        for (i in 0 until 17) r.tile(8, 100 + i, 100).await() // pushes the region out of the region LRU
        val s = r.service.valueAt(-33.87, 151.21)!!
        assertThat(s.rsrpDbm).isEqualTo(-90f)
        assertThat(s.sinrDb).isEqualTo(0f)
        assertThat(s.probability).isNotNull()
        assertThat(s.best).isNull()
    }

    /** Requests every z11 child of the 2x2 z10 tiles around Sydney, twice; returns the 4 z10 tiles. */
    private fun denseViewport(r: Rig) {
        val at = TileMath.locate(-33.87, 151.21, 10)!!
        // The 2x2 block must sit inside one z8 tile.
        val x0 = at.x and 1.inv(); val y0 = at.y and 1.inv()
        repeat(2) {
            for (dx in 0..1) for (dy in 0..1) for (cx in 0..1) for (cy in 0..1) {
                r.tile(11, (x0 + dx) * 2 + cx, (y0 + dy) * 2 + cy).await()
            }
        }
    }

    @Test fun aDenseViewportPreparesEachZ10RegionOnceAndSkipsTheOversizedQuery() {
        // z8 + 80 km is over the cap; each z10 + 80 km fits.
        val r = rig(towers = FakeTowers().apply { batchFor = { if (spanDeg(it) > 2.4) bigBatch(70_000) else bigBatch(1000) } })
        denseViewport(r)
        assertThat(r.renderer.prepares.get()).isEqualTo(4)
        assertThat(r.towers.queries).hasSize(5) // one z8 attempt, then one per z10 tile
    }

    @Test fun levelTwoReusesTheLevelOneBatchInsteadOfQueryingAgain() {
        val r = rig(towers = FakeTowers().apply { batchFor = { bigBatch(70_000, farEvery = 2) } })
        denseViewport(r)
        assertThat(r.renderer.prepares.get()).isEqualTo(4)
        assertThat(r.towers.queries).hasSize(5) // z8, then one per z10 tile (not two)
    }

    @Test fun aRenderWhoseKeyWentStaleIsCancelledAndNeverCached() {
        val r = rig()
        r.renderer.hold = true
        val old = r.tile(10, 900, 600)
        waitUntil { r.renderer.renders.size == 1 }
        r.towers.stampValue = "stamp2"
        r.renderer.hold = false
        r.tile(10, 900, 600, stamp = "stamp2").await() // the new request cancels the dead render
        assertThat(r.renderer.renders.first().cancel.isCancelled).isTrue()
        assertThat(failure(old)).isInstanceOf(TileCancelledException::class.java)
        assertThat(r.cache.getMemory(TileKey(10, 900, 600, r.selection.cacheId(), "stamp1", "none"))).isNull()
    }
}
