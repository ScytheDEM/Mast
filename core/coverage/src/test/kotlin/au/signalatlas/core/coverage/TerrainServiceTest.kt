package au.signalatlas.core.coverage

import au.signalatlas.core.geo.InsufficientStorageException
import au.signalatlas.engine.TerrainTable
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
class TerrainServiceTest {
    @get:Rule val tmp = TemporaryFolder()
    private var rig: TerrainRig? = null

    private fun rig(
        chunks: Boolean = true, threads: Int = 2, online: Boolean = true, metered: Boolean = false,
        towers: FakeTowers = FakeTowers(),
    ) = TerrainRig(tmp, chunks, threads, online, metered, towers = towers).also { rig = it }

    @After fun tearDown() { rig?.close() }

    private fun accuracyOf(r: TerrainRig, key: TileKey) = Accuracy.of(key, r.cache.getMemory(key)!!)

    /** Renders the tile, waits for the tables to finish and publish, and returns the key then in force. */
    private fun settled(r: TerrainRig, tables: Int): TileKey {
        r.render()
        waitUntil(what = "$tables tables published") { r.service.terrainEpoch.value >= 1 && r.renderer.builds.size >= tables }
        return r.key()
    }

    @Test fun withoutChunksTheRegionIsQuickOnlyAndNothingIsBuilt() {
        val r = rig(chunks = false)
        r.render()
        val key = r.key()
        assertThat(key.terrainVersion).isEqualTo(0L)
        assertThat(accuracyOf(r, key)).isEqualTo(Accuracy.QUICK)
        Thread.sleep(100)
        assertThat(r.renderer.builds).isEmpty()
        assertThat(r.renderer.buildCalls).isEmpty()
        assertThat(r.service.terrainEpoch.value).isEqualTo(0L)
        assertThat(r.rig.warnings).isEmpty()
    }

    @Test fun serviceWithoutTerrainSupportKeepsVersionZeroAndEpochZero() {
        val r = Rig(tmp.newFolder())
        try {
            r.tile(10, 900, 600).get(5, TimeUnit.SECONDS)
            assertThat(r.service.currentKey(10, 900, 600)!!.terrainVersion).isEqualTo(0L)
            assertThat(r.service.terrainEpoch.value).isEqualTo(0L)
            assertThat(r.service.accuracyFor(setOf(Triple(10, 900, 600))).value).isEqualTo(Accuracy.QUICK)
            assertThat(runBlocking { r.service.increaseResolution(true).toList() }).containsExactly(FetchProgress.Failed("terrain unavailable"))
        } finally {
            r.close()
        }
    }

    @Test fun completeChunksGiveAVersionAndTablesChangeItWithTheUrlSoMapLibreRefetches() {
        val r = rig()
        val template0 = r.service.tileUrlTemplate()!!
        val oldUrl = r.rig.url(r.tile.first, r.tile.second, r.tile.third)
        val key0 = r.key()
        assertThat(key0.terrainVersion).isNotEqualTo(0L)
        r.render()
        assertThat(accuracyOf(r, key0)).isEqualTo(Accuracy.QUICK) // rendered at once, without tables

        val key1 = settled(r, tables = 2)
        assertThat(key1.terrainVersion).isNotIn(listOf(0L, key0.terrainVersion))
        assertThat(r.service.tileUrlTemplate()).isNotEqualTo(template0)
        assertThat(template0).contains("/t0/")
        assertThat(r.service.tileUrlTemplate()).contains("/t1/")

        // The URL MapLibre had is stale now.
        val stale = r.service.tileFuture(TileUrl.parse(oldUrl.toHttpUrl())!!)
        val cause = try { stale.get(5, TimeUnit.SECONDS); null } catch (e: ExecutionException) { e.cause }
        assertThat(cause).isInstanceOf(StaleTileException::class.java)

        // The new key renders from both tables; the old tile is still there under its own key (never mixed).
        r.render()
        assertThat(accuracyOf(r, key1)).isEqualTo(Accuracy.TERRAIN)
        assertThat(accuracyOf(r, key0)).isEqualTo(Accuracy.QUICK)
        assertThat(r.cache.getMemory(key0)).isNotSameInstanceAs(r.cache.getMemory(key1))
        val tables = r.renderer.preparedTables.last()
        assertThat(tables.map { it.first }).containsExactly(0, 1)
        assertThat(tables.map { it.second }).containsExactly(r.renderer.tableFor[3500f to 30f], r.renderer.tableFor[700f to 30f])
    }

    @Test fun theVersionStaysPutWhilstTablesArePendingSoNoTileMixesTableSets() {
        val r = rig()
        r.renderer.holdBuilds = true
        val key0 = r.key()
        r.render()
        waitUntil { r.renderer.buildCalls.size == 2 }
        r.render()
        assertThat(r.key()).isEqualTo(key0)
        assertThat(r.service.terrainEpoch.value).isEqualTo(0L)
        assertThat(r.renderer.preparedTables).isEmpty() // every render so far used the plain quick region
        r.renderer.holdBuilds = false
        r.awaitEpochAbove(0)
        assertThat(r.key()).isNotEqualTo(key0)
    }

    @Test fun eachTableIsBuiltOnceAcrossTilesAndRepeatRequests() {
        val r = rig()
        for (cx in 0..1) for (cy in 0..1) r.render(11, r.at.x * 2 + cx, r.at.y * 2 + cy) // four z11 tiles of the region
        r.awaitEpochAbove(0)
        r.service.setVisible(setOf(r.tile))
        for (cx in 0..1) for (cy in 0..1) r.render(11, r.at.x * 2 + cx, r.at.y * 2 + cy)
        r.service.setVisible(setOf(r.tile))
        Thread.sleep(150)
        assertThat(r.renderer.buildCalls.sortedBy { it.first }).containsExactly(700f to 30f, 3500f to 30f).inOrder()
        assertThat(r.renderer.builds).hasSize(2)
    }

    @Test fun theBuildGetsTheCarriersKeyFrequencyAndKeyHeightAndRowsMapToTheirTables() {
        // 3512.4 MHz -> 3510, 29.6 m -> 30; 2599.9 -> 2600, 2 m -> 3 (the 3 m floor); 3504 shares 3510's key at 30 m.
        val towers = FakeTowers().apply {
            batchFor = { sydneyBatch(floatArrayOf(3512.4f, 2599.9f, 3514f), floatArrayOf(29.6f, 2f, 30.4f), sameSite = true) }
        }
        val r = rig(towers = towers)
        val key0 = r.key()
        r.render()
        r.awaitEpochAbove(0)
        assertThat(r.renderer.buildCalls).containsExactly(3510f to 30f, 2600f to 3f)
        r.render()
        assertThat(r.key()).isNotEqualTo(key0)
        val tables = r.renderer.preparedTables.last().toMap()
        assertThat(tables.keys).containsExactly(0, 1, 2)
        assertThat(tables.getValue(0)).isSameInstanceAs(r.renderer.tableFor[3510f to 30f])
        assertThat(tables.getValue(1)).isSameInstanceAs(r.renderer.tableFor[2600f to 3f])
        assertThat(tables.getValue(2)).isSameInstanceAs(tables.getValue(0)) // same key: one table, shared
    }

    @Test fun carriersOutsideTheDemAreQuickAndGetNoTable() {
        // Row 1 is 3 degrees (330 km) east of the tile: its site is outside the 40 km elevation margin.
        val towers = FakeTowers().apply {
            batchFor = { sydneyBatch(floatArrayOf(700f, 800f), floatArrayOf(30f, 30f)).let { b -> b.siteLon[1] = 154.5; b } }
        }
        val r = rig(towers = towers)
        r.render()
        r.awaitEpochAbove(0)
        assertThat(r.renderer.buildCalls).containsExactly(700f to 30f)
    }

    @Test fun aChunkThatVanishedBeforeTheMosaicOpenedFallsBackToQuickWithoutBuildsOrErrors() {
        val r = rig(threads = 1)
        r.renderer.hold = true // the render occupies the only thread, so the table jobs wait
        val key0 = r.key()
        val f = r.rig.tile(r.tile.first, r.tile.second, r.tile.third)
        waitUntil { r.renderer.renders.size == 1 }
        r.chunkStore.clear() // the region looked complete when the key was made
        r.renderer.hold = false
        f.get(5, TimeUnit.SECONDS)
        waitUntil(what = "region back to quick") { r.service.terrainEpoch.value == 1L }
        assertThat(r.key().terrainVersion).isEqualTo(0L)
        assertThat(r.key()).isNotEqualTo(key0)
        assertThat(r.renderer.buildCalls).isEmpty()
        assertThat(r.rig.warnings).isEmpty() // not an exception path
        r.render()
        assertThat(accuracyOf(r, r.key())).isEqualTo(Accuracy.QUICK)
    }

    @Test fun panningAwayCancelsRunningTableBuildsWithoutChangingTheVersionAndPanningBackRebuilds() {
        val r = rig()
        r.renderer.holdBuilds = true
        r.render()
        waitUntil { r.renderer.buildCalls.size == 2 }
        val far = Triple(10, r.at.x + 40, r.at.y)
        r.service.setVisible(setOf(far))
        waitUntil(what = "builds cancelled") { r.renderer.holdBuilds && r.renderer.buildCalls.size == 2 && r.service.terrainEpoch.value == 0L }
        Thread.sleep(100)
        assertThat(r.renderer.builds).isEmpty()
        assertThat(r.service.terrainEpoch.value).isEqualTo(0L)
        r.renderer.holdBuilds = false
        r.service.setVisible(setOf(r.tile))
        r.awaitEpochAbove(0)
        assertThat(r.renderer.builds).hasSize(2)
    }

    @Test fun aTileStillOnScreenReclaimsTheBuildsDroppedWithAPannedAwayTile() {
        val r = rig()
        r.renderer.holdBuilds = true
        val a = Triple(11, r.at.x * 2, r.at.y * 2)
        val b = Triple(11, r.at.x * 2 + 1, r.at.y * 2) // same terrain region
        r.service.setVisible(setOf(a, b))
        r.render(a.first, a.second, a.third)
        waitUntil { r.renderer.buildCalls.size == 2 }
        r.service.setVisible(setOf(b)) // the jobs sat under tile a
        r.renderer.holdBuilds = false
        waitUntil(what = "b claims both tables again") { r.renderer.builds.size == 2 }
        r.awaitEpochAbove(0)
    }

    @Test fun tablesComeFromTheDiskCacheWhenTheServiceIsRebuilt() {
        val r = rig()
        r.render()
        r.awaitEpochAbove(0)
        assertThat(r.renderer.builds).hasSize(2)
        val chunkDir = r.chunkDir
        val tableDir = r.tableDir
        r.close()

        val again = TerrainRig(tmp, chunksPresent = false, chunkDir = chunkDir, tableDir = tableDir).also { rig = it }
        again.render()
        again.awaitEpochAbove(0)
        assertThat(again.renderer.buildCalls).isEmpty() // both tables loaded from the files
        again.render()
        assertThat(accuracyOf(again, again.key())).isEqualTo(Accuracy.TERRAIN)
    }

    @Test fun autoCacheFetchesTheViewsMissingChunksOnceSettledAtZ10OnAnUnmeteredNetwork() {
        val r = rig(chunks = false)
        r.render()
        val key0 = r.key()
        val template0 = r.service.tileUrlTemplate()
        r.service.setVisible(setOf(r.tile))
        waitUntil(what = "auto fetch") { r.source.fetches.isNotEmpty() }
        assertThat(r.source.fetches.single()).isEqualTo(r.regionChunks)
        r.awaitEpochAbove(0)
        assertThat(r.key().terrainVersion).isNotIn(listOf(0L, key0.terrainVersion))
        assertThat(r.service.tileUrlTemplate()).isNotEqualTo(template0)
        r.render() // renders under the new key and asks for the tables
        waitUntil { r.renderer.builds.size == 2 }
    }

    @Test fun autoCacheWaitsForTheCameraToSettleAndDoesNotRepeatForTheSameView() {
        val r = rig(chunks = false)
        repeat(5) { r.service.setVisible(setOf(r.tile)); Thread.sleep(5) } // identical sets don't restart the wait
        waitUntil { r.source.fetches.size == 1 }
        Thread.sleep(100)
        assertThat(r.source.fetches).hasSize(1)
        r.service.setVisible(setOf(r.tile)) // nothing missing now
        Thread.sleep(100)
        assertThat(r.source.fetches).hasSize(1)
    }

    @Test fun autoCacheNeverRunsBelowZ10OnMeteredNetworksOrOffline() {
        val low = rig(chunks = false)
        low.service.setVisible(setOf(Triple(9, 1, 1)))
        Thread.sleep(150)
        assertThat(low.source.fetches).isEmpty()
        low.close()

        val metered = rig(chunks = false, metered = true)
        metered.service.setVisible(setOf(metered.tile))
        Thread.sleep(150)
        assertThat(metered.source.fetches).isEmpty()
        metered.network.set(online = true, metered = false) // the unmetered network arrives: the settled view is fetched
        waitUntil(what = "fetch on unmetered") { metered.source.fetches.size == 1 }
        metered.close()

        val offline = rig(chunks = false, online = false)
        offline.service.setVisible(setOf(offline.tile))
        Thread.sleep(150)
        assertThat(offline.source.fetches).isEmpty()
    }

    @Test fun anAutoCacheStorageFailureIsSilentAndNotRetriedForTheSameView() {
        val r = rig(chunks = false)
        r.source.failWith = InsufficientStorageException(10, 1)
        r.service.setVisible(setOf(r.tile))
        waitUntil { r.source.fetches.size == 1 }
        r.service.setVisible(setOf(r.tile, Triple(9, 1, 1)))
        Thread.sleep(150)
        assertThat(r.source.fetches).hasSize(1)
        assertThat(r.rig.warnings).isEmpty()
        assertThat(r.key().terrainVersion).isEqualTo(0L)
    }

    @Test fun increaseResolutionEstimatesThenFetchesThenDoneAndChangesTheKeys(): Unit = runBlocking {
        val r = rig(chunks = false, metered = true)
        r.render()
        val key0 = r.key()
        r.service.setVisible(setOf(r.tile))
        assertThat(r.service.estimateIncreaseBytes()).isEqualTo(r.regionChunks.size * 1000L)

        val events = r.service.increaseResolution(allowMetered = true).toList()
        assertThat(events.first()).isEqualTo(FetchProgress.Estimating(r.regionChunks.size * 1000L))
        assertThat(events.last()).isEqualTo(FetchProgress.Done)
        val fractions = events.filterIsInstance<FetchProgress.Fetching>().map { it.fraction }
        assertThat(fractions).isNotEmpty()
        assertThat(fractions).isInOrder()
        assertThat(fractions.last()).isEqualTo(1f)
        assertThat(r.key().terrainVersion).isNotIn(listOf(0L, key0.terrainVersion)) // regions rebuilt on arrival
        assertThat(r.service.terrainEpoch.value).isEqualTo(1L)
        assertThat(r.service.increaseResolution(true).toList()).containsExactly(FetchProgress.Done) // nothing left
    }

    /**
     * Regression (emulator, Canberra z11): the view's fetch must cover the terrain regions of its tiles (the z10 tile +
     * 40 km), not just the viewport + 40 km. A view smaller than its z10 region left some of the region's chunks unfetched,
     * so the region never became available and the chip stayed "Quick estimate" forever.
     */
    @Test fun increaseResolutionFetchesEveryChunkTheRegionsOfAViewSmallerThanZ10Need(): Unit = runBlocking {
        val r = rig(chunks = false)
        val corner = Triple(12, r.at.x * 4, r.at.y * 4) // the north-west z12 tile of the Sydney z10 region
        r.render(corner.first, corner.second, corner.third)
        r.service.setVisible(setOf(corner))
        assertThat(r.service.estimateIncreaseBytes()).isEqualTo(r.regionChunks.size * 1000L)
        assertThat(r.service.increaseResolution(allowMetered = false).toList().last()).isEqualTo(FetchProgress.Done)
        assertThat(r.source.fetches.flatten()).containsAtLeastElementsIn(r.regionChunks)
        assertThat(r.key(corner.first, corner.second, corner.third).terrainVersion).isNotEqualTo(0L)
        assertThat(r.service.terrainEpoch.value).isAtLeast(1L)
    }

    @Test fun autoCacheOfAViewSmallerThanZ10FetchesItsWholeRegion() {
        val r = rig(chunks = false)
        val corner = Triple(12, r.at.x * 4, r.at.y * 4)
        r.render(corner.first, corner.second, corner.third)
        r.service.setVisible(setOf(corner))
        r.awaitEpochAbove(0)
        assertThat(r.source.fetches.single()).containsAtLeastElementsIn(r.regionChunks)
        assertThat(r.key(corner.first, corner.second, corner.third).terrainVersion).isNotEqualTo(0L)
    }

    @Test fun increaseResolutionRefusesMeteredWithoutConsentButStillTellsTheSize(): Unit = runBlocking {
        val r = rig(chunks = false, metered = true)
        r.service.setVisible(setOf(r.tile))
        val events = r.service.increaseResolution(allowMetered = false).toList()
        assertThat(events).containsExactly(FetchProgress.Estimating(r.regionChunks.size * 1000L), FetchProgress.Failed("metered")).inOrder()
        assertThat(r.source.fetches).isEmpty()
    }

    @Test fun increaseResolutionFailsOfflineBeforeAnyEstimateAndWithoutAViewOrBelowZ10(): Unit = runBlocking {
        val off = rig(chunks = false, online = false)
        off.service.setVisible(setOf(off.tile))
        assertThat(off.service.increaseResolution(true).toList()).containsExactly(FetchProgress.Failed("offline"))
        assertThat(off.source.estimates).isEmpty()
        assertThat(off.service.estimateIncreaseBytes()).isNull()
        off.close()

        val r = rig(chunks = false)
        assertThat(r.service.increaseResolution(true).toList()).containsExactly(FetchProgress.Failed("no view"))
        r.service.setVisible(setOf(Triple(9, 1, 1)))
        assertThat(r.service.increaseResolution(true).toList()).containsExactly(FetchProgress.Failed("zoom in"))
    }

    @Test fun increaseResolutionReportsStorageAndIoFailures(): Unit = runBlocking {
        val r = rig(chunks = false)
        r.service.setVisible(setOf(r.tile))
        r.source.failWith = InsufficientStorageException(10, 1)
        assertThat(r.service.increaseResolution(true).toList().last()).isEqualTo(FetchProgress.Failed("storage"))
        r.source.failWith = java.io.IOException("connection reset")
        assertThat(r.service.increaseResolution(true).toList().last()).isEqualTo(FetchProgress.Failed("connection reset"))
        assertThat(r.key().terrainVersion).isEqualTo(0L)
    }

    @Test fun accuracyForGoesFromQuickToTerrainAsTablesPublishAndIsPartialWhenOnlySomeTilesAre() {
        val r = rig()
        val far = Triple(10, r.at.x + 40, r.at.y) // a region without chunks: stays quick
        val both = setOf(r.tile, far)
        val only = r.service.accuracyFor(setOf(r.tile))
        val mixed = r.service.accuracyFor(both)
        assertThat(only.value).isEqualTo(Accuracy.QUICK) // nothing rendered yet
        r.render()
        r.render(far.first, far.second, far.third)
        assertThat(only.value).isEqualTo(Accuracy.QUICK)
        r.awaitEpochAbove(0)
        r.render()
        waitUntil(what = "terrain accuracy") { only.value == Accuracy.TERRAIN }
        assertThat(mixed.value).isEqualTo(Accuracy.PARTIAL)
        assertThat(r.service.accuracyFor(both).value).isEqualTo(Accuracy.PARTIAL)
    }

    @Test fun accuracyIgnoresParentTilesBelowTheViewportZoom() {
        val r = rig()
        r.render()
        r.awaitEpochAbove(0)
        r.render()
        val parent = Triple(8, 1, 1) // never rendered
        assertThat(r.service.accuracyFor(setOf(r.tile, parent)).value).isEqualTo(Accuracy.TERRAIN)
    }

    @Test fun anIncompleteTableSetLabelsTheTileQuick() {
        // 21 sites in a row: the nearest 256 keys are all built, but a batch row whose site is outside the DEM never
        // gets one, so its pixels stay incomplete: 1 of 2 carriers = 50% > 5%.
        val towers = FakeTowers().apply {
            batchFor = { sydneyBatch(floatArrayOf(700f, 800f), floatArrayOf(30f, 30f)).let { b -> b.siteLon[1] = 154.5; b } }
        }
        val r = rig(towers = towers)
        r.render()
        r.awaitEpochAbove(0)
        r.render()
        assertThat(accuracyOf(r, r.key())).isEqualTo(Accuracy.QUICK)
        assertThat(r.cache.getMemory(r.key())!!.terrainIncompletePixels).isGreaterThan(0)
    }

    /**
     * Regression (emulator, Canberra): with more wanted tables than the per-region cap, demand by distance alone left
     * out the carriers that actually serve the region's pixels, so every tile stayed > 5% terrain-incomplete (QUICK).
     * The best servers of a quick render of the region come first.
     */
    @Test fun theCarriersThatServeTheRegionGetTheirTablesBeforeMerelyNearOnes() {
        val n = TerrainCoordinator.MAX_TABLES_PER_REGION + 40
        val towers = FakeTowers().apply {
            batchFor = { sydneyBatch(FloatArray(n) { 700f + 10 * it }, FloatArray(n) { 30f }, sameSite = true) }
        }
        val r = rig(towers = towers)
        // Every pixel is served by prepared carrier 1 = batch row n - 2 (FakeRenderer), last in distance order.
        val server = TerrainTable.frequencyKeyMhz(700f + 10 * (n - 2)).toFloat()
        r.render()
        waitUntil(timeoutMs = 20_000, what = "all tables") { r.renderer.builds.size == TerrainCoordinator.MAX_TABLES_PER_REGION }
        assertThat(r.renderer.buildCalls.map { it.first }).contains(server)
        assertThat(r.renderer.buildCalls.first().first).isEqualTo(server)
    }

    @Test fun onlyTheNearestTablesPerRegionAreBuilt() {
        assertThat(TerrainCoordinator.MAX_TABLES_PER_REGION).isEqualTo(128)
        val n = TerrainCoordinator.MAX_TABLES_PER_REGION + 40
        val towers = FakeTowers().apply {
            batchFor = { sydneyBatch(FloatArray(n) { 700f + 10 * it }, FloatArray(n) { 30f }, sameSite = true) }
        }
        val r = rig(towers = towers)
        r.render()
        waitUntil(timeoutMs = 20_000, what = "all tables") { r.renderer.builds.size == TerrainCoordinator.MAX_TABLES_PER_REGION }
        Thread.sleep(100)
        assertThat(r.renderer.buildCalls).hasSize(TerrainCoordinator.MAX_TABLES_PER_REGION)
        // Published in batches along the way, once more when the queue drained.
        assertThat(r.service.terrainEpoch.value).isAtLeast(TerrainCoordinator.MAX_TABLES_PER_REGION / TerrainCoordinator.PUBLISH_BATCH.toLong())
    }

    @Test fun aPanThatDrainsCancelledJobsDoesNotPublishStagedTablesOrBumpTheEpoch() {
        val r = rig()
        r.renderer.holdAfter = 1 // the first table finishes (staged), the second keeps building
        r.render()
        waitUntil { r.renderer.buildCalls.size == 2 && r.renderer.builds.size == 1 }
        val far = Triple(10, r.at.x + 40, r.at.y)
        r.service.setVisible(setOf(far)) // cancels the second build; pending drains with one table staged
        Thread.sleep(200)
        assertThat(r.service.terrainEpoch.value).isEqualTo(0L)
        val v0 = r.key()
        r.renderer.holdAfter = Int.MAX_VALUE
        r.service.setVisible(setOf(r.tile)) // back: the missing table is rebuilt and then everything publishes at once
        r.awaitEpochAbove(0)
        assertThat(r.service.terrainEpoch.value).isEqualTo(1L)
        assertThat(r.key()).isNotEqualTo(v0)
        assertThat(r.renderer.builds).hasSize(2)
        r.render() // the staged table and the rebuilt one are both in the published set
        assertThat(r.renderer.preparedTables.last()).hasSize(2)
    }

    @Test fun aTileCrossingPanOfAVisibleRegionDoesNotBumpTheEpoch() {
        val r = rig()
        val a = Triple(11, r.at.x * 2, r.at.y * 2)
        val b = Triple(11, r.at.x * 2 + 1, r.at.y * 2)
        r.renderer.holdAfter = 1
        r.service.setVisible(setOf(a, b))
        r.render(a.first, a.second, a.third)
        waitUntil { r.renderer.buildCalls.size == 2 && r.renderer.builds.size == 1 }
        r.service.setVisible(setOf(b)) // a leaves, b stays in the same region: the cancelled job is requeued under b
        Thread.sleep(200)
        assertThat(r.service.terrainEpoch.value).isEqualTo(0L)
        r.renderer.holdAfter = Int.MAX_VALUE
        waitUntil { r.renderer.builds.size == 2 }
        r.awaitEpochAbove(0)
    }

    @Test fun epochBumpsAreCoalescedToOnePerWindowOnAMonotonicClock() {
        val now = java.util.concurrent.atomic.AtomicLong(1_000)
        val r = TerrainRig(tmp, chunksPresent = true, minBumpMs = 10_000, clock = now::get).also { rig = it }
        r.render()
        r.awaitEpochAbove(0) // the first bump is immediate
        assertThat(r.service.terrainEpoch.value).isEqualTo(1L)
        now.addAndGet(9_000) // 1 s left in the window
        val before = r.service.terrainEpoch.value
        // Two version changes inside the window: chunks of a second region arrive, then its tables publish.
        val x = r.at.x + 1
        r.putChunks(ChunkMathRegion.of(x, r.at.y))
        r.render(10, x, r.at.y) // new region: complete from the start, tables come
        r.render(10, x, r.at.y)
        waitUntil(what = "second region's tables") { r.renderer.builds.size == 4 }
        assertThat(r.service.terrainEpoch.value).isEqualTo(before) // deferred, not lost
        waitUntil(timeoutMs = 5_000, what = "deferred bump") { r.service.terrainEpoch.value > before }
        now.addAndGet(10_000)
        assertThat(r.service.terrainEpoch.value).isEqualTo(before + 1) // one bump for both changes
    }

    @Test fun anImmediateBumpThatAbsorbsADeferredOneDoesNotLoseALaterChange() {
        val now = java.util.concurrent.atomic.AtomicLong(1_000)
        // One site in each of the next tiles eastwards, so every region has something to build.
        val towers = FakeTowers().apply {
            batchFor = { sydneyBatch(FloatArray(5) { 700f }, FloatArray(5) { 30f }).also { b -> for (i in 0 until 5) b.siteLon[i] = 151.21 + 0.3516 * i } }
        }
        val r = TerrainRig(tmp, chunksPresent = true, minBumpMs = 10_000, clock = now::get, towers = towers).also { rig = it }
        fun change(dx: Int) {
            val x = r.at.x + dx
            r.putChunks(ChunkMathRegion.of(x, r.at.y))
            val k0 = r.key(10, x, r.at.y)
            r.render(10, x, r.at.y)
            waitUntil(what = "region $dx published") { r.key(10, x, r.at.y) != k0 }
            Thread.sleep(50) // the epoch step follows the version change by a few instructions
        }
        r.render()
        r.awaitEpochAbove(0) // immediate: epoch 1 at t=1000
        now.addAndGet(9_500)
        change(1) // inside the window: deferred (500 ms)
        now.addAndGet(11_000) // the window is over before the deferred job woke ...
        change(2) // ... so this one bumps at once and absorbs the waiting job
        waitUntil(what = "absorbing bump") { r.service.terrainEpoch.value == 2L }
        now.addAndGet(9_500)
        change(3) // inside the new window, in the gap where the old deferred job would have been pending
        waitUntil(timeoutMs = 5_000, what = "bump for the later change") { r.service.terrainEpoch.value == 3L }
        Thread.sleep(700)
        assertThat(r.service.terrainEpoch.value).isEqualTo(3L) // exactly one more, and no stale extra
    }

    @Test fun aCancellationEscapingTheBuildDoesNotMarkTheTableFailedForGood() {
        val r = rig()
        r.renderer.throwOnBuild = java.util.concurrent.CancellationException("native cancel")
        r.service.setVisible(setOf(r.tile))
        r.render()
        waitUntil { r.renderer.buildCalls.size >= 2 && r.renderer.builds.isNotEmpty() }
        Thread.sleep(100)
        r.service.setVisible(setOf(r.tile)) // asks again for what is still missing
        waitUntil(what = "both tables built") { r.renderer.builds.size == 2 }
        waitUntil(what = "both published") { r.render(); r.renderer.preparedTables.lastOrNull()?.size == 2 }
        assertThat(r.renderer.buildCalls).hasSize(3)
        assertThat(r.rig.warnings).isEmpty()
    }

    @Test fun anEmptyTileIsQuickAndLeftOutOfTheViewportSummary() {
        val r = rig()
        val b = Triple(10, r.at.x + 1, r.at.y) // a neighbouring region with chunks, where nothing is covered
        r.putChunks(ChunkMathRegion.of(b.second, b.third))
        r.render()
        r.awaitEpochAbove(0)
        r.render()
        assertThat(accuracyOf(r, r.key())).isEqualTo(Accuracy.TERRAIN)
        r.renderer.rsrpCode = 0
        r.render(b.first, b.second, b.third)
        val emptyKey = r.key(b.first, b.second, b.third)
        assertThat(emptyKey.terrainVersion).isNotEqualTo(0L)
        assertThat(accuracyOf(r, emptyKey)).isEqualTo(Accuracy.QUICK)
        assertThat(r.service.accuracyFor(setOf(r.tile, b)).value).isEqualTo(Accuracy.TERRAIN)
        assertThat(r.service.accuracyFor(setOf(b)).value).isEqualTo(Accuracy.QUICK)
    }

    /** A tile of [key] in the tile cache, so a request for it never renders (and never asks for tables itself). */
    private fun cacheTile(r: TerrainRig, key: TileKey) {
        val n = au.signalatlas.engine.RenderedTile.PIXELS
        r.cache.put(key, au.signalatlas.engine.RenderedTile(key.z, key.x, key.y, ByteArray(n) { 100 }, ByteArray(n), ByteArray(n), ShortArray(n), n, n))
    }

    /** Creates [MAX_REGIONS] + 1 other regions, which pushes the tile's region out of the coordinator's LRU. */
    private fun evictTileRegion(r: TerrainRig) {
        repeat(TerrainCoordinator.MAX_REGIONS + 1) { r.key(10, r.at.x + 1 + it, r.at.y) }
        assertThat(r.service.hasTerrainRegion(r.tile.first, r.tile.second, r.tile.third)).isFalse()
    }

    /** Occupies the single pool thread until the returned latch is released. */
    private fun blockPool(r: TerrainRig): java.util.concurrent.CountDownLatch {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        r.rig.pool.submit(r.tile, { started.countDown(); release.await() }, {})
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
        return release
    }

    /** A low-priority job queued after everything queued so far; await the latch to know those ran. */
    private fun marker(r: TerrainRig, run: () -> Unit = {}): java.util.concurrent.CountDownLatch {
        val done = java.util.concurrent.CountDownLatch(1)
        r.rig.pool.submit(r.tile, RenderPool.PRIORITY_TABLE, { try { run() } finally { done.countDown() } }, { done.countDown() })
        return done
    }

    @Test fun aRegionEvictedBeforeItsQueuedDemandJobRunsIsRecreatedAndAsksForItsTables() {
        val r = rig(threads = 1)
        val key0 = r.key()
        assertThat(key0.terrainVersion).isNotEqualTo(0L)
        cacheTile(r, key0)
        r.service.setVisible(setOf(r.tile))
        val release = blockPool(r)
        r.rig.tile(r.tile.first, r.tile.second, r.tile.third).get(5, TimeUnit.SECONDS) // cache hit: the demand job queues
        evictTileRegion(r) // genuinely gone before the demand job runs
        val ran = marker(r)
        release.countDown()
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(r.service.hasTerrainRegion(r.tile.first, r.tile.second, r.tile.third)).isTrue() // the job recreated it
        waitUntil(what = "the recreated region's tables built") { r.renderer.builds.size == 2 }
        assertThat(r.renderer.buildCalls).containsExactly(3500f to 30f, 700f to 30f)
        r.awaitEpochAbove(0)
    }

    @Test fun aDemandJobRecreatesAnEvictedRegionAtMostOncePerDataKey() {
        val r = rig(threads = 1)
        val key0 = r.key()
        cacheTile(r, key0)
        r.service.setVisible(setOf(r.tile))
        val release = blockPool(r)
        r.rig.tile(r.tile.first, r.tile.second, r.tile.third).get(5, TimeUnit.SECONDS) // demand job A queues
        evictTileRegion(r)
        // Runs after A recreated the region: evicts it, asks again (job B queues on a fresh region), evicts that too.
        val churned = marker(r) {
            evictTileRegion(r)
            r.rig.tile(r.tile.first, r.tile.second, r.tile.third).get(5, TimeUnit.SECONDS)
            evictTileRegion(r)
        }
        release.countDown()
        assertThat(churned.await(5, TimeUnit.SECONDS)).isTrue()
        val after = marker(r) // B has run
        assertThat(after.await(5, TimeUnit.SECONDS)).isTrue()
        // B must not resurrect the region a second time (no recreate -> evict -> recreate loop driven by demand jobs).
        assertThat(r.service.hasTerrainRegion(r.tile.first, r.tile.second, r.tile.third)).isFalse()
        assertThat(r.renderer.buildCalls).isEmpty()
        // A real request still recreates it and gets its tables.
        r.rig.tile(r.tile.first, r.tile.second, r.tile.third).get(5, TimeUnit.SECONDS)
        waitUntil(what = "tables after a real request") { r.renderer.builds.size == 2 }
    }

    @Test fun belowZ10NoTerrainRegionIsEverCreatedAndTilesAreQuickOnly() {
        val r = rig()
        for (z in 8..9) {
            val x = r.at.x shr (10 - z)
            val y = r.at.y shr (10 - z)
            r.render(z, x, y)
            val key = r.key(z, x, y)
            assertThat(key.terrainVersion).isEqualTo(0L)
            assertThat(accuracyOf(r, key)).isEqualTo(Accuracy.QUICK)
        }
        r.service.setVisible(setOf(Triple(9, r.at.x shr 1, r.at.y shr 1)))
        Thread.sleep(100)
        assertThat(r.renderer.buildCalls).isEmpty()
        assertThat(r.service.terrainEpoch.value).isEqualTo(0L)
        // z10 does get one (chunks are present) and builds.
        r.render()
        r.awaitEpochAbove(0)
    }

    @Test fun atMostTwelveTerrainRegionsAreKept() {
        val r = rig(chunks = false)
        assertThat(TerrainCoordinator.MAX_REGIONS).isEqualTo(12)
        repeat(TerrainCoordinator.MAX_REGIONS + 3) { r.key(10, r.at.x + it, r.at.y) }
        assertThat(r.service.terrainRegionCount).isEqualTo(12)
        // The first one was evicted; asking again recreates it (and evicts another), the count stays at the cap.
        r.key()
        assertThat(r.service.terrainRegionCount).isEqualTo(12)
        // Chunks that arrive later are noticed by the next tile request of a quick region.
        r.putChunks(r.regionChunks)
        assertThat(r.key().terrainVersion).isEqualTo(0L)
        r.render()
        assertThat(r.key().terrainVersion).isNotEqualTo(0L)
    }

    @Test fun trimMemoryDropsTheTableLruAndOffScreenRegionsButTheDiskTablesStillServe() {
        val r = rig()
        r.service.setVisible(setOf(r.tile))
        r.render()
        r.awaitEpochAbove(0)
        r.render()
        val withTables = r.key()
        assertThat(r.tableStore.memorySize).isEqualTo(2)
        r.service.onTrimMemory(5) // below RUNNING_LOW: nothing
        assertThat(r.tableStore.memorySize).isEqualTo(2)
        r.service.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertThat(r.tableStore.memorySize).isEqualTo(0)
        assertThat(r.key()).isEqualTo(withTables) // the region on screen keeps its snapshot

        r.service.setVisible(setOf(Triple(10, r.at.x + 40, r.at.y)))
        r.service.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        // The region left the screen and was dropped: rebuilt from the chunks, its two tables come from disk, no builds.
        val builds = r.renderer.buildCalls.size
        r.service.setVisible(setOf(r.tile))
        r.render()
        
        r.awaitEpochAbove(1)
        assertThat(r.renderer.buildCalls).hasSize(builds)
    }

    @Test fun estimateAndIncreaseWithoutAViewAnswerNoViewInsteadOfCrashing(): Unit = runBlocking {
        val r = rig(chunks = false)
        r.service.setVisible(setOf(r.tile))
        r.service.setVisible(emptySet()) // the view becomes null
        assertThat(r.service.estimateIncreaseBytes()).isNull()
        assertThat(r.service.increaseResolution(true).toList()).containsExactly(FetchProgress.Failed("no view"))
    }
}

/** Chunks of the z10 tile (x, y). */
private object ChunkMathRegion {
    fun of(x: Int, y: Int) = au.signalatlas.core.geo.ChunkMath.chunksFor(TileMath.bounds(10, x, y), 40.0)
}
