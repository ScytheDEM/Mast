package au.signalatlas.core.coverage

import au.signalatlas.core.geo.InsufficientStorageException
import au.signalatlas.core.data.CarrierBatch
import au.signalatlas.core.model.Network
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
        assertThat(tables.map { it.second }).containsExactly(r.renderer.tableFor[3550f to 30f], r.renderer.tableFor[700f to 30f])
    }

    /**
     * Latency (2026-10-24) and M3b: a terrain tile never waits for table demand, and demand needs no extra render (the
     * old z10 quick sample took 8 s in Sydney): the rendered tile's own servers are what it asks tables for.
     */
    @Test fun demandComesFromTheRenderedTileWithoutAnExtraRender() {
        val r = rig(threads = 1)
        val (z, x, y) = r.tile
        r.render(z + 3, x * 8, y * 8)
        waitUntil(what = "both tables") { r.renderer.builds.size == 2 }
        assertThat(r.renderer.renders.map { it.z }.toSet()).containsExactly(z + 3)
    }

    /** Task: the calibration matcher predicts with the tables the map already has (any batch, keyed like the renderer's). */
    @Test fun aPointQueryGetsTheTablesTheRegionHasReadyAndNothingBefore() {
        val r = rig()
        val here = -33.87 to 151.21
        assertThat(r.service.terrainTablesAt(here.first, here.second)).isNull() // region not created yet
        r.render()
        r.renderer.holdBuilds = true
        r.service.setVisible(setOf(r.tile))
        assertThat(r.service.terrainTablesAt(here.first, here.second)).isNull() // region kept, no table published
        r.renderer.holdBuilds = false
        settled(r, tables = 2)

        val lookup = r.service.terrainTablesAt(here.first, here.second)!!
        val batch = fakeBatch() // the batch the tiles were rendered from: rows 0 and 1 have tables
        assertThat(lookup(batch, 0)).isSameInstanceAs(r.renderer.tableFor[3550f to 30f])
        assertThat(lookup(batch, 1)).isSameInstanceAs(r.renderer.tableFor[700f to 30f])
        // Another batch (the matcher's own query) resolves by key: row 0's site at another n78 frequency shares its
        // table, the same site at another height or in another band has none.
        val b = fakeBatch()
        val other = CarrierBatch(
            3, DoubleArray(3) { b.siteLat[0] }, DoubleArray(3) { b.siteLon[0] }, floatArrayOf(30f, 12f, 30f), FloatArray(3), FloatArray(3),
            FloatArray(3) { 60f }, floatArrayOf(3410f, 3410f, 2140f), IntArray(3) { 10_000_000 }, ByteArray(3) { 1 },
            ByteArray(3) { Network.TELSTRA.ordinal.toByte() }, FloatArray(3) { 65f }, FloatArray(3) { 25f }, FloatArray(3) { 15f },
            IntArray(3) { it }, LongArray(3) { it.toLong() }, ByteArray(3), listOf("n78"),
        )
        assertThat(lookup(other, 0)).isSameInstanceAs(r.renderer.tableFor[3550f to 30f])
        assertThat(lookup(other, 1)).isNull()
        assertThat(lookup(other, 2)).isNull()
        assertThat(r.service.terrainTablesAt(-30.0, 140.0)).isNull() // another region
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
        assertThat(r.renderer.buildCalls.sortedBy { it.first }).containsExactly(700f to 30f, 3550f to 30f).inOrder()
        assertThat(r.renderer.builds).hasSize(2)
    }

    @Test fun theBuildGetsTheCarriersKeyFrequencyAndKeyHeightAndRowsMapToTheirTables() {
        // 3512.4 MHz -> n78 centre 3550, 29.6 m -> 30; 2599.9 (no band) -> 2600, 2 m -> 4 (3 m floor, then 2 m steps); 3514 shares the n78 key.
        val towers = FakeTowers().apply {
            batchFor = { sydneyBatch(floatArrayOf(3512.4f, 2599.9f, 3514f), floatArrayOf(29.6f, 2f, 30.4f), sameSite = true) }
        }
        val r = rig(towers = towers)
        val key0 = r.key()
        r.render()
        r.awaitEpochAbove(0)
        assertThat(r.renderer.buildCalls).containsExactly(3550f to 30f, 2600f to 4f)
        r.render()
        assertThat(r.key()).isNotEqualTo(key0)
        val tables = r.renderer.preparedTables.last().toMap()
        assertThat(tables.keys).containsExactly(0, 1, 2)
        assertThat(tables.getValue(0)).isSameInstanceAs(r.renderer.tableFor[3550f to 30f])
        assertThat(tables.getValue(1)).isSameInstanceAs(r.renderer.tableFor[2600f to 4f])
        assertThat(tables.getValue(2)).isSameInstanceAs(tables.getValue(0)) // same key: one table, shared
    }

    /**
     * A table is built from its site's home DEM (the site's z10 tile + 40 km), so it is the same table whichever region
     * asks. A server whose home chunks are missing waits for them, and the view's fetch includes them.
     */
    @Test fun aServerWhoseHomeDemIsMissingWaitsForItAndTheViewFetchesIt(): Unit = runBlocking {
        // Row 1 is 3 degrees (330 km) east of the tile: its home chunks are not stored.
        val towers = FakeTowers().apply {
            batchFor = { sydneyBatch(floatArrayOf(700f, 800f), floatArrayOf(30f, 30f)).let { b -> b.siteLon[1] = 154.5; b } }
        }
        val r = rig(towers = towers, metered = true) // metered: no auto-cache
        r.service.setVisible(setOf(r.tile))
        r.render()
        r.awaitEpochAbove(0)
        assertThat(r.renderer.buildCalls).containsExactly(700f to 30f)
        val home = TileMath.locate(-33.86, 154.5, 10)!!
        val homeChunks = ChunkMathRegion.of(home.x, home.y)
        assertThat(r.service.estimateIncreaseBytes()).isEqualTo(homeChunks.size * 1000L)
        assertThat(r.service.increaseResolution(allowMetered = true).toList().last()).isEqualTo(FetchProgress.Done)
        assertThat(r.source.fetches.flatten()).containsAtLeastElementsIn(homeChunks)
        waitUntil(what = "the waiting table built") { r.renderer.buildCalls.size == 2 }
        assertThat(r.renderer.builds.last().dem.southDeg).isLessThan(-33.86)
        assertThat(r.renderer.builds.last().dem.westDeg).isGreaterThan(153.0) // its own home mosaic
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
        r.service.setVisible(setOf(r.tile))
        r.render()
        waitUntil { r.renderer.buildCalls.size == 2 }
        val far = Triple(10, r.at.x + 40, r.at.y)
        r.service.setVisible(setOf(far))
        Thread.sleep(100)
        assertThat(r.renderer.builds).isEmpty()
        assertThat(r.service.terrainEpoch.value).isEqualTo(0L)
        r.renderer.holdBuilds = false
        r.service.setVisible(setOf(r.tile)) // back: the tile's known servers are asked for again, without a new request
        r.awaitEpochAbove(0)
        assertThat(r.renderer.builds).hasSize(2)
    }

    @Test fun aTileStillOnScreenReclaimsTheBuildsDroppedWithAPannedAwayTile() {
        val r = rig()
        r.renderer.holdBuilds = true
        val a = Triple(11, r.at.x * 2, r.at.y * 2)
        val b = Triple(11, r.at.x * 2 + 1, r.at.y * 2) // same terrain region, the same servers
        r.service.setVisible(setOf(a, b))
        r.render(a.first, a.second, a.third)
        r.render(b.first, b.second, b.third)
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

    @Test fun aTileWhoseServerHasNoTableIsPartialNotTerrainNorQuick() {
        // Row 1's home DEM is not stored: its pixels (half of them) stay incomplete; the other half have terrain.
        val towers = FakeTowers().apply {
            batchFor = { sydneyBatch(floatArrayOf(700f, 800f), floatArrayOf(30f, 30f)).let { b -> b.siteLon[1] = 154.5; b } }
        }
        val r = rig(towers = towers, metered = true)
        r.render()
        r.awaitEpochAbove(0)
        r.render()
        val tile = r.cache.getMemory(r.key())!!
        assertThat(tile.terrainIncompletePixels).isEqualTo(tile.coveredPixels / 2)
        assertThat(accuracyOf(r, r.key())).isEqualTo(Accuracy.PARTIAL)
        assertThat(r.service.accuracyFor(setOf(r.tile)).value).isEqualTo(Accuracy.PARTIAL)
    }

    /** Contender-aware demand: a carrier serving >= 1% of a tile's covered pixels gets its table before the others. */
    @Test fun contendersGetTheirTablesBeforeCarriersServingUnderOnePercent() {
        val n = 60
        val towers = FakeTowers().apply {
            batchFor = { sydneyBatch(FloatArray(n) { 700f + 10 * it }, FloatArray(n) { 30f }, sameSite = true) }
        }
        val r = rig(threads = 1, towers = towers)
        // Prepared carrier 7 serves 90% of the pixels; the other 59 split the rest (each well under 1%).
        r.renderer.serverOf = { p, i -> if (i % 10 != 0) 7 else (i / 10) % p.carriers }
        val contender = TerrainKeys.frequencyKhz(700f + 10 * (n - 1 - 7)) / 1000f
        val keys = (0 until n).map { TerrainKeys.frequencyKhz(700f + 10 * it) }.toSet()
        assertThat(keys.size).isGreaterThan(3)
        r.render()
        waitUntil(timeoutMs = 20_000, what = "all tables") { r.renderer.builds.size == keys.size }
        assertThat(r.renderer.buildCalls.first().first).isEqualTo(contender)
    }

    /**
     * M3b regression (the CBD seams): a dense centre with far more server tables than the old 128-per-region budget
     * (Sydney's z10 CBD region had 760) still reaches TERRAIN: every serving carrier gets its table.
     */
    @Test fun aDenseCityCentreReachesTerrain() {
        val batch = cbdBatch(sites = 40)
        val keys = (0 until batch.size).map {
            Triple(batch.siteLat[it] to batch.siteLon[it], TerrainKeys.frequencyKhz(batch.freqMhz[it]), TerrainKeys.heightM(batch.heightM[it]))
        }.toSet()
        assertThat(keys.size).isGreaterThan(128)
        val r = rig(towers = FakeTowers().apply { batchFor = { batch } })
        r.service.setVisible(setOf(r.tile)) // on screen: its tables are kept even past the memory cap
        r.render()
        waitUntil(timeoutMs = 20_000, what = "all ${keys.size} tables built") { r.renderer.builds.size == keys.size }
        r.awaitEpochAbove(0)
        Thread.sleep(100)
        assertThat(r.renderer.buildCalls).hasSize(keys.size)
        r.render()
        assertThat(r.renderer.preparedTables.last()).hasSize(batch.size) // every carrier has its (shared) table
        assertThat(accuracyOf(r, r.key())).isEqualTo(Accuracy.TERRAIN)
    }

    /**
     * M3b (seam report H3): a carrier serving both sides of a z10 border has one table, used by both regions, and its
     * publication changes both regions' versions, so neither side renders it without terrain once it exists.
     */
    @Test fun aCarrierServingAcrossAZ10BorderUsesTheSameTableOnBothSides() {
        val r = rig()
        val east = Triple(10, r.at.x + 1, r.at.y)
        r.putChunks(ChunkMathRegion.of(east.second, east.third))
        r.service.setVisible(setOf(r.tile, east))
        val east0 = r.key(east.first, east.second, east.third)
        r.render() // only the west tile asks for the tables
        r.awaitEpochAbove(0)
        val east1 = r.key(east.first, east.second, east.third)
        assertThat(east1.terrainVersion).isNotIn(listOf(0L, east0.terrainVersion)) // the neighbour moved on too
        r.render()
        val west = r.renderer.preparedTables.last().toMap()
        r.render(east.first, east.second, east.third)
        val eastTables = r.renderer.preparedTables.last().toMap()
        assertThat(eastTables.keys).containsExactly(0, 1)
        for (row in 0..1) assertThat(eastTables.getValue(row)).isSameInstanceAs(west.getValue(row))
        assertThat(r.renderer.buildCalls).hasSize(2) // built once, for both regions
        assertThat(accuracyOf(r, r.key(east.first, east.second, east.third))).isEqualTo(Accuracy.TERRAIN)
    }

    @Test fun aPanThatDrainsCancelledJobsDoesNotPublishStagedTablesOrBumpTheEpoch() {
        val r = rig()
        r.renderer.holdAfter = 1 // the first table finishes (staged), the second keeps building
        r.service.setVisible(setOf(r.tile))
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
        Thread.sleep(100)
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
        r.render(b.first, b.second, b.third)
        waitUntil { r.renderer.buildCalls.size == 2 && r.renderer.builds.size == 1 }
        r.service.setVisible(setOf(b)) // a leaves, b stays and wants the same tables: the cancelled job is requeued under b
        Thread.sleep(200)
        assertThat(r.service.terrainEpoch.value).isEqualTo(0L)
        r.renderer.holdAfter = Int.MAX_VALUE
        waitUntil { r.renderer.builds.size == 2 }
        r.awaitEpochAbove(0)
    }

    @Test fun epochBumpsAreCoalescedToOnePerWindowOnAMonotonicClock() {
        val now = java.util.concurrent.atomic.AtomicLong(1_000)
        // One site in the tile's region and one in the next region east, whose home chunks are not stored yet.
        val towers = FakeTowers().apply {
            batchFor = { sydneyBatch(FloatArray(2) { 700f }, FloatArray(2) { 30f }).also { b -> b.siteLat[1] = -33.87; b.siteLon[1] = 151.21 + 0.3516 } }
        }
        val r = TerrainRig(tmp, chunksPresent = true, minBumpMs = 10_000, clock = now::get, towers = towers).also { rig = it }
        r.render()
        r.awaitEpochAbove(0) // the first bump is immediate
        assertThat(r.service.terrainEpoch.value).isEqualTo(1L)
        assertThat(r.renderer.builds).hasSize(1)
        now.addAndGet(9_000) // 1 s left in the window
        val before = r.service.terrainEpoch.value
        // Two version changes inside the window: the east region's chunks arrive (it becomes available), then the
        // waiting table of its site publishes (both regions change).
        val x = r.at.x + 1
        r.putChunks(ChunkMathRegion.of(x, r.at.y))
        r.render(10, x, r.at.y)
        waitUntil(what = "second site's table") { r.renderer.builds.size == 2 }
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




    /** A memory-cache hit never renders: it still asks for its servers' tables (counted on the pool). */
    @Test fun aCacheHitAsksForTheTablesOfTheCarriersServingIt() {
        val r = rig()
        val key0 = r.key()
        cacheTile(r, key0) // every pixel served by prepared carrier 0 = batch row 1 (700 MHz)
        r.service.setVisible(setOf(r.tile))
        r.rig.tile(r.tile.first, r.tile.second, r.tile.third).get(5, TimeUnit.SECONDS)
        waitUntil(what = "the server's table") { r.renderer.builds.size == 1 }
        assertThat(r.renderer.buildCalls).containsExactly(700f to 30f)
        assertThat(r.renderer.renders).isEmpty()
        r.awaitEpochAbove(0)
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

    /** The global table LRU is capped at 400; off-screen regions' tables go first, and leave those regions' snapshots. */
    @Test fun theTableCacheIsCappedAndEvictsOffScreenTablesFirst() {
        assertThat(TerrainCoordinator.MAX_READY_TABLES).isEqualTo(400)
        val r0 = TerrainRig(tmp, chunksPresent = true, towers = FakeTowers()).also { rig = it }
        val far = Triple(10, r0.at.x + 40, r0.at.y)
        val farLat = TileMath.centreLat(10, far.third)
        val farLon = TileMath.centreLon(10, far.second)
        fun site(n: Int, lat: Double, lon: Double) = sydneyBatch(FloatArray(n) { 700f }, FloatArray(n) { 4f + 2 * it }, sameSite = true)
            .also { b -> for (i in 0 until n) { b.siteLat[i] = lat; b.siteLon[i] = lon } }
        val a = site(300, -33.87, 151.21)
        val b = site(200, farLat, farLon)
        r0.rig.towers.batchFor = { bounds -> if (bounds.contains(-33.87, 151.21)) a else b }
        r0.putChunks(ChunkMathRegion.of(far.second, far.third))
        r0.service.setVisible(setOf(r0.tile))
        r0.render()
        waitUntil(timeoutMs = 20_000, what = "300 tables") { r0.renderer.builds.size == 300 && r0.service.terrainReadyCount == 300 }
        r0.awaitEpochAbove(0)
        val nearKey = r0.key()
        r0.service.setVisible(setOf(far))
        r0.render(far.first, far.second, far.third)
        waitUntil(timeoutMs = 20_000, what = "500 builds") { r0.renderer.builds.size == 500 }
        waitUntil(what = "trimmed") { r0.service.terrainReadyCount == 400 }
        r0.render(far.first, far.second, far.third)
        assertThat(r0.renderer.preparedTables.last()).hasSize(200) // the on-screen region kept all of its tables
        assertThat(r0.key()).isNotEqualTo(nearKey) // the off-screen region lost the evicted ones (and re-renders later)
    }

    /** M2 rule 5a (M3b): building data arrives with Increase resolution, changes tile keys, and reaches the renderer. */
    @Test fun buildingChunksAreFetchedWithIncreaseResolutionAndChangeTheBuildingsVersion(): Unit = runBlocking {
        val r = TerrainRig(tmp, chunksPresent = true, withBuildings = true, metered = true).also { rig = it }
        r.service.setVisible(setOf(r.tile))
        val key0 = r.key()
        assertThat(key0.buildingsVersion).isEqualTo(0L)
        val need = au.signalatlas.engine.BuildingGrid.chunksFor(TileMath.bounds(10, r.at.x, r.at.y))
        assertThat(r.service.estimateIncreaseBytes()).isEqualTo(need.size * 10L)
        val epoch = r.service.terrainEpoch.value
        assertThat(r.service.increaseResolution(allowMetered = true).toList().last()).isEqualTo(FetchProgress.Done)
        assertThat(r.buildingSource.fetches.single()).isEqualTo(need)
        assertThat(r.service.terrainEpoch.value).isGreaterThan(epoch) // MapLibre refetches
        val key1 = r.key()
        assertThat(key1.buildingsVersion).isNotEqualTo(0L)
        assertThat(r.key(12, r.at.x * 4 + 1, r.at.y * 4 + 2).buildingsVersion).isEqualTo(key1.buildingsVersion) // per z10 area
        r.render()
        val grid = r.renderer.renders.last().buildings!!
        assertThat(grid.stored).isNotEmpty()
        assertThat(grid.coverCode(1, 1)).isEqualTo(0) // empty chunk: data, no buildings
        assertThat(r.cache.getMemory(key1)).isNotNull()
        assertThat(r.cache.getMemory(key0)).isNull()
    }

    @Test fun autoCacheAlsoFetchesTheViewsBuildingChunks() {
        val r = TerrainRig(tmp, chunksPresent = false, withBuildings = true).also { rig = it }
        r.service.setVisible(setOf(r.tile))
        waitUntil(what = "building fetch") { r.buildingSource.fetches.isNotEmpty() }
        waitUntil(what = "buildings version") { r.key().buildingsVersion != 0L }
        assertThat(r.source.fetches.single()).isEqualTo(r.regionChunks)
    }

    @Test fun withoutBuildingSupportTheBuildingsVersionStaysZeroAndNoGridIsPassed() {
        val r = rig()
        r.render()
        assertThat(r.key().buildingsVersion).isEqualTo(0L)
        assertThat(r.renderer.renders.last().buildings).isNull()
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
