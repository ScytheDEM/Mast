package au.signalatlas.app.ui.coverage

import au.signalatlas.app.ui.map.FakeBasemap
import au.signalatlas.app.ui.map.FakeCoverage
import au.signalatlas.app.ui.map.FakeData
import au.signalatlas.app.ui.map.FakeLocation
import au.signalatlas.app.ui.map.FakeSnapshot
import au.signalatlas.app.ui.map.FakeUpdates
import au.signalatlas.app.ui.map.IncreaseStatus
import au.signalatlas.app.ui.map.MapViewModel
import au.signalatlas.app.ui.map.SITES
import au.signalatlas.core.coverage.Accuracy
import au.signalatlas.core.coverage.FetchProgress
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncreaseResolutionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val coverage = FakeCoverage()
    private val tiles = setOf(Triple(11, 1, 1), Triple(10, 0, 0))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(): MapViewModel {
        val vm = MapViewModel(
            FakeData(FakeSnapshot(SITES)), FakeUpdates(), FakeBasemap(null), FakeLocation(), coverage,
            compute = dispatcher, io = dispatcher,
        )
        advanceUntilIdle()
        vm.setCoverageOn(true)
        vm.onVisibleTiles(tiles)
        advanceUntilIdle()
        return vm
    }

    private val MapViewModel.sheet get() = state.value.coverage.increase

    @Test fun chipFollowsTheAccuracyOfTheTilesInView() = runTest(dispatcher) {
        val vm = vm()
        assertThat(vm.state.value.coverage.accuracy).isEqualTo(Accuracy.QUICK)
        assertThat(coverage.accuracyQueries.last()).isEqualTo(tiles)
        coverage.accuracy.value = Accuracy.PARTIAL
        advanceUntilIdle()
        assertThat(vm.state.value.coverage.accuracy).isEqualTo(Accuracy.PARTIAL)
        coverage.accuracy.value = Accuracy.TERRAIN
        advanceUntilIdle()
        assertThat(vm.state.value.coverage.accuracy).isEqualTo(Accuracy.TERRAIN)
    }

    @Test fun aTerrainEpochBumpAsksForTheAccuracyAgain() = runTest(dispatcher) {
        val vm = vm()
        val before = coverage.accuracyQueries.size
        coverage.terrainEpoch.value = 1
        advanceUntilIdle()
        assertThat(coverage.accuracyQueries.size).isGreaterThan(before)
        assertThat(vm.state.value.coverage.accuracy).isEqualTo(Accuracy.QUICK)
    }

    @Test fun theChipIsQuickWhileCoverageIsOffWhateverTheTilesSay() = runTest(dispatcher) {
        val vm = vm()
        coverage.accuracy.value = Accuracy.TERRAIN
        advanceUntilIdle()
        vm.setCoverageOn(false)
        advanceUntilIdle()
        assertThat(vm.state.value.coverage.accuracy).isEqualTo(Accuracy.QUICK)
    }

    @Test fun openingTheSheetShowsTheEstimate() = runTest(dispatcher) {
        val vm = vm()
        vm.openIncreaseResolution()
        assertThat(vm.sheet!!.status).isEqualTo(IncreaseStatus.LOADING)
        advanceUntilIdle()
        assertThat(vm.sheet!!.status).isEqualTo(IncreaseStatus.READY)
        assertThat(vm.sheet!!.bytes).isEqualTo(12_000_000L)
    }

    @Test fun noEstimateWhileOfflineSaysOffline() = runTest(dispatcher) {
        val vm = vm()
        coverage.estimate = null
        coverage.online = false
        vm.openIncreaseResolution()
        advanceUntilIdle()
        assertThat(vm.sheet!!.status).isEqualTo(IncreaseStatus.OFFLINE)
    }

    @Test fun noEstimateWhileOnlineMeansZoomIn() = runTest(dispatcher) {
        val vm = vm()
        coverage.estimate = null
        vm.openIncreaseResolution()
        advanceUntilIdle()
        assertThat(vm.sheet!!.status).isEqualTo(IncreaseStatus.ZOOM_IN)
    }

    @Test fun theSheetDoesNotOpenOnceEverythingIsTerrain() = runTest(dispatcher) {
        val vm = vm()
        coverage.accuracy.value = Accuracy.TERRAIN
        advanceUntilIdle()
        vm.openIncreaseResolution()
        advanceUntilIdle()
        assertThat(vm.sheet).isNull()
    }

    @Test fun downloadShowsProgressThenClosesOnDone() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coverage.scripts += flow {
            emit(FetchProgress.Estimating(12_000_000L))
            emit(FetchProgress.Fetching(0.25f))
            emit(FetchProgress.Fetching(0.5f))
            gate.await()
            emit(FetchProgress.Done)
        }
        val vm = vm()
        vm.openIncreaseResolution()
        advanceUntilIdle()
        vm.downloadIncrease()
        advanceUntilIdle()
        assertThat(vm.sheet!!.status).isEqualTo(IncreaseStatus.FETCHING)
        assertThat(vm.sheet!!.progress).isEqualTo(0.5f)
        assertThat(vm.sheet!!.bytes).isEqualTo(12_000_000L)
        assertThat(coverage.increaseCalls).containsExactly(false)
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.sheet).isNull()
    }

    @Test fun meteredNetworkAsksFirstThenFetchesOnMobileData() = runTest(dispatcher) {
        coverage.scripts += flowOf(FetchProgress.Estimating(30_000_000L), FetchProgress.Failed("metered"))
        coverage.scripts += flowOf(FetchProgress.Estimating(30_000_000L), FetchProgress.Fetching(1f), FetchProgress.Done)
        val vm = vm()
        vm.openIncreaseResolution()
        advanceUntilIdle()
        vm.downloadIncrease()
        advanceUntilIdle()
        assertThat(vm.sheet!!.status).isEqualTo(IncreaseStatus.CONFIRM_METERED)
        assertThat(vm.sheet!!.bytes).isEqualTo(30_000_000L)
        vm.confirmIncreaseOnMobileData()
        advanceUntilIdle()
        assertThat(coverage.increaseCalls).containsExactly(false, true).inOrder()
        assertThat(vm.sheet).isNull()
    }

    @Test fun failuresMapToSheetStates() = runTest(dispatcher) {
        val expected = mapOf(
            "offline" to IncreaseStatus.OFFLINE,
            "zoom in" to IncreaseStatus.ZOOM_IN,
            "no view" to IncreaseStatus.ZOOM_IN,
            "storage" to IncreaseStatus.STORAGE_FULL,
            "connection reset" to IncreaseStatus.ERROR,
        )
        for ((reason, status) in expected) {
            coverage.scripts.clear()
            coverage.increaseCalls.clear()
            coverage.scripts += flowOf(FetchProgress.Failed(reason))
            val vm = vm()
            vm.openIncreaseResolution()
            advanceUntilIdle()
            vm.downloadIncrease()
            advanceUntilIdle()
            assertThat(vm.sheet!!.status).isEqualTo(status)
        }
    }

    @Test fun dismissingStopsTheDownloadAndTurningCoverageOffClosesTheSheet() = runTest(dispatcher) {
        var cancelled = false
        coverage.scripts += flow {
            emit(FetchProgress.Fetching(0.1f))
            try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true }
        }
        val vm = vm()
        vm.openIncreaseResolution()
        advanceUntilIdle()
        vm.downloadIncrease()
        advanceUntilIdle()
        vm.dismissIncreaseResolution()
        advanceUntilIdle()
        assertThat(cancelled).isTrue()
        assertThat(vm.sheet).isNull()

        vm.openIncreaseResolution()
        advanceUntilIdle()
        assertThat(vm.sheet).isNotNull()
        vm.setCoverageOn(false)
        advanceUntilIdle()
        assertThat(vm.sheet).isNull()
    }
}
