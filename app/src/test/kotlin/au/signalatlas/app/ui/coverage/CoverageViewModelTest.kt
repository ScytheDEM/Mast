package au.signalatlas.app.ui.coverage

import au.signalatlas.app.ui.map.BestSector
import au.signalatlas.app.ui.map.FakeBasemap
import au.signalatlas.app.ui.map.FakeCoverage
import au.signalatlas.app.ui.map.FakeData
import au.signalatlas.app.ui.map.FakeLocation
import au.signalatlas.app.ui.map.FakeSnapshot
import au.signalatlas.app.ui.map.FakeUpdates
import au.signalatlas.app.ui.map.MapViewModel
import au.signalatlas.app.ui.map.ReadoutResult
import au.signalatlas.app.ui.map.SITES
import au.signalatlas.app.ui.map.SignalQuality
import au.signalatlas.core.coverage.BestServer
import au.signalatlas.core.coverage.CoverageSample
import au.signalatlas.core.coverage.CoverageSelection
import au.signalatlas.core.coverage.CoverageSelection.View
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import au.signalatlas.core.model.TowerFilter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoverageViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val coverage = FakeCoverage()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(): MapViewModel {
        val vm = MapViewModel(
            FakeData(FakeSnapshot(SITES)), FakeUpdates(), FakeBasemap(null), FakeLocation(), coverage,
            compute = dispatcher, io = dispatcher,
        )
        advanceUntilIdle()
        return vm
    }

    private val sample = CoverageSample(-97.2f, 12f, 0.82f, BestServer(Network.TELSTRA, "n78", 1234.0))

    @Test fun togglingOnSelectsTelstraAllTechsAndTurningOffClearsIt() = runTest(dispatcher) {
        val vm = vm()
        assertThat(coverage.selection).isNull()
        vm.setCoverageOn(true)
        advanceUntilIdle()
        assertThat(coverage.selection).isEqualTo(
            CoverageSelection(Network.TELSTRA, setOf(Tech.LTE, Tech.NR), null, false, View.RSRP),
        )
        assertThat(vm.state.value.coverage.on).isTrue()
        assertThat(vm.state.value.coverage.urlTemplate).contains("telstra_")

        vm.setCoverageOn(false)
        advanceUntilIdle()
        assertThat(coverage.selection).isNull()
        assertThat(vm.state.value.coverage.urlTemplate).isNull()
    }

    @Test fun theDefaultNetworkIsTheFirstOneSelectedInTheFilterChips() = runTest(dispatcher) {
        val vm = vm()
        vm.setFilter(TowerFilter(networks = setOf(Network.NBN, Network.TPG)))
        vm.setCoverageOn(true)
        assertThat(coverage.selection!!.network).isEqualTo(Network.TPG)
        vm.setCoverageOn(false)
        // All networks selected (the default filter) means Telstra.
        vm.setFilter(TowerFilter())
        vm.setCoverageOn(true)
        assertThat(coverage.selection!!.network).isEqualTo(Network.TELSTRA)
    }

    @Test fun anExplicitNetworkChoiceSurvivesTogglingOffAndOn() = runTest(dispatcher) {
        val vm = vm()
        vm.setCoverageOn(true)
        vm.setCoverageNetwork(Network.NBN)
        vm.setCoverageOn(false)
        vm.setFilter(TowerFilter(networks = setOf(Network.OPTUS)))
        vm.setCoverageOn(true)
        assertThat(coverage.selection!!.network).isEqualTo(Network.NBN)
    }

    @Test fun networkAndIndoorChangesGiveNewCacheIds() = runTest(dispatcher) {
        val vm = vm()
        vm.setCoverageOn(true)
        val ids = mutableListOf(coverage.selection!!.cacheId())
        vm.setCoverageNetwork(Network.OPTUS)
        ids += coverage.selection!!.cacheId()
        vm.setCoverageIndoor(true)
        ids += coverage.selection!!.cacheId()
        vm.setCoverageFiveGOnly(true)
        ids += coverage.selection!!.cacheId()
        assertThat(ids.toSet()).hasSize(4)
        assertThat(coverage.selection).isEqualTo(
            CoverageSelection(Network.OPTUS, setOf(Tech.NR), null, true, View.RSRP),
        )
    }

    @Test fun theViewToggleKeepsTheCacheIdAndOnlyChangesTheTemplateViewSegment() = runTest(dispatcher) {
        val vm = vm()
        vm.setCoverageOn(true)
        advanceUntilIdle()
        val id = coverage.selection!!.cacheId()
        val before = vm.state.value.coverage.urlTemplate!!
        assertThat(before).contains("/$id/rsrp/")

        vm.setCoverageView(View.PROBABILITY)
        advanceUntilIdle()
        assertThat(coverage.selection!!.cacheId()).isEqualTo(id)
        assertThat(vm.state.value.coverage.urlTemplate).isEqualTo(before.replace("/$id/rsrp/", "/$id/prob/"))
    }

    @Test fun theBandFilterNarrowsTheSelection() = runTest(dispatcher) {
        val vm = vm()
        vm.setCoverageOn(true)
        vm.setFilter(TowerFilter(bands = setOf("n78")))
        assertThat(coverage.selection!!.bands).containsExactly("n78")
        vm.setFilter(TowerFilter())
        assertThat(coverage.selection!!.bands).isNull()
    }

    @Test fun visibleTilesGoToTheCoverageService() = runTest(dispatcher) {
        val vm = vm()
        vm.onVisibleTiles(setOf(Triple(10, 1, 2)))
        assertThat(coverage.lastVisible).containsExactly(Triple(10, 1, 2))
    }

    @Test fun theZoomChipShowsOnlyWhileCoverageIsOnBelowZ8() = runTest(dispatcher) {
        val vm = vm()
        vm.onZoom(5.0)
        assertThat(vm.state.value.coverage.needsZoom(vm.state.value.zoom)).isFalse() // off
        vm.setCoverageOn(true)
        assertThat(vm.state.value.coverage.needsZoom(vm.state.value.zoom)).isTrue()
        vm.onZoom(8.0)
        assertThat(vm.state.value.coverage.needsZoom(vm.state.value.zoom)).isFalse()
    }

    @Test fun longPressReadsThePredictionFromTheCachedTiles() = runTest(dispatcher) {
        val vm = vm()
        vm.setCoverageOn(true)
        coverage.sample = sample
        vm.longPress(-35.28, 149.13)
        assertThat(vm.state.value.coverage.readout!!.result).isEqualTo(ReadoutResult.Calculating)
        runCurrent()
        val r = vm.state.value.coverage.readout!!
        assertThat(r.lat).isEqualTo(-35.28)
        assertThat(r.result).isEqualTo(
            ReadoutResult.Predicted(-97, SignalQuality.GOOD, 82, BestSector(Network.TELSTRA, "n78", 1234.0)),
        )
    }

    @Test fun longPressKeepsAskingEvery500msUntilATileIsThere() = runTest(dispatcher) {
        val vm = vm()
        vm.setCoverageOn(true)
        vm.longPress(-35.28, 149.13)
        runCurrent()
        assertThat(coverage.valueAtCalls).isEqualTo(1)
        advanceTimeBy(1_600)
        assertThat(coverage.valueAtCalls).isEqualTo(4) // t = 0, 500, 1000, 1500
        assertThat(vm.state.value.coverage.readout!!.result).isEqualTo(ReadoutResult.Calculating)

        coverage.sample = sample
        advanceTimeBy(500)
        assertThat(vm.state.value.coverage.readout!!.result).isInstanceOf(ReadoutResult.Predicted::class.java)
        val calls = coverage.valueAtCalls
        advanceTimeBy(5_000)
        assertThat(coverage.valueAtCalls).isEqualTo(calls) // stopped
    }

    @Test fun longPressGivesUpAfterTenSeconds() = runTest(dispatcher) {
        val vm = vm()
        vm.setCoverageOn(true)
        vm.longPress(-35.28, 149.13)
        advanceTimeBy(9_900)
        assertThat(vm.state.value.coverage.readout!!.result).isEqualTo(ReadoutResult.Calculating)
        advanceTimeBy(200)
        assertThat(vm.state.value.coverage.readout!!.result).isEqualTo(ReadoutResult.NoPrediction)
        assertThat(coverage.valueAtCalls).isEqualTo(21) // t = 0, 500, ... 10 000
    }

    @Test fun noRsrpOrOneBelowTheLegendFloorMeansNoPredictedCoverage() = runTest(dispatcher) {
        val vm = vm()
        vm.setCoverageOn(true)
        coverage.sample = CoverageSample(null, null, null, null)
        vm.longPress(-35.0, 149.0)
        runCurrent()
        assertThat(vm.state.value.coverage.readout!!.result).isEqualTo(ReadoutResult.NoCoverage)
        coverage.sample = CoverageSample(-125f, -5f, 0.1f, null)
        vm.longPress(-35.0, 149.0)
        runCurrent()
        assertThat(vm.state.value.coverage.readout!!.result).isEqualTo(ReadoutResult.NoCoverage)
    }

    @Test fun qualityBandsFollowTheLegend() {
        val bands = mapOf(
            -60 to SignalQuality.EXCELLENT, -85 to SignalQuality.EXCELLENT, -86 to SignalQuality.GOOD,
            -100 to SignalQuality.GOOD, -101 to SignalQuality.FAIR, -110 to SignalQuality.FAIR,
            -111 to SignalQuality.WEAK, -118 to SignalQuality.WEAK, -119 to null,
        )
        for ((dbm, q) in bands) assertThat(SignalQuality.of(dbm)).isEqualTo(q)
    }

    @Test fun theReadoutIsDismissedByATapAndByAnySelectionChangeExceptTheView() = runTest(dispatcher) {
        val vm = vm()
        vm.setCoverageOn(true)
        coverage.sample = sample
        vm.longPress(-35.28, 149.13)
        runCurrent()
        vm.onMapTap()
        assertThat(vm.state.value.coverage.readout).isNull()

        vm.longPress(-35.28, 149.13)
        runCurrent()
        vm.setCoverageView(View.PROBABILITY)
        assertThat(vm.state.value.coverage.readout).isNotNull() // same tiles, same numbers
        vm.setCoverageIndoor(true)
        assertThat(vm.state.value.coverage.readout).isNull()

        vm.longPress(-35.28, 149.13)
        runCurrent()
        vm.setCoverageOn(false)
        assertThat(vm.state.value.coverage.readout).isNull()
    }

    @Test fun aLongPressWithCoverageOffDoesNothing() = runTest(dispatcher) {
        val vm = vm()
        vm.longPress(-35.28, 149.13)
        runCurrent()
        assertThat(vm.state.value.coverage.readout).isNull()
        assertThat(coverage.valueAtCalls).isEqualTo(0)
    }

    @Test fun aNewLongPressReplacesTheOneStillWaiting() = runTest(dispatcher) {
        val vm = vm()
        vm.setCoverageOn(true)
        vm.longPress(-35.0, 149.0)
        runCurrent()
        vm.longPress(-36.0, 150.0)
        coverage.sample = sample
        advanceTimeBy(600)
        val r = vm.state.value.coverage.readout!!
        assertThat(r.lat).isEqualTo(-36.0)
        assertThat(r.result).isInstanceOf(ReadoutResult.Predicted::class.java)
    }

    @Test fun aFreshViewModelStartsOffAndClearsASelectionLeftByAnEarlierOne() = runTest(dispatcher) {
        coverage.selection = CoverageSelection(Network.OPTUS, setOf(Tech.NR), null, true, View.PROBABILITY)
        val vm = vm()
        assertThat(vm.state.value.coverage.on).isFalse()
        assertThat(coverage.selection).isNull()
        assertThat(vm.state.value.coverage.urlTemplate).isNull()
    }

    @Test fun aLongPressBelowZ8DoesNotWait() = runTest(dispatcher) {
        val vm = vm()
        vm.setCoverageOn(true)
        vm.onZoom(6.0)
        vm.longPress(-35.28, 149.13)
        runCurrent()
        assertThat(vm.state.value.coverage.readout).isNull()
        assertThat(coverage.valueAtCalls).isEqualTo(0)
    }

    @Test fun aBandFilterChangeDismissesTheReadout() = runTest(dispatcher) {
        val vm = vm()
        vm.setCoverageOn(true)
        coverage.sample = sample
        vm.longPress(-35.28, 149.13)
        runCurrent()
        vm.setFilter(TowerFilter(networks = setOf(Network.OPTUS))) // not a band change: keeps it
        assertThat(vm.state.value.coverage.readout).isNotNull()
        vm.setFilter(TowerFilter(bands = setOf("n78")))
        assertThat(vm.state.value.coverage.readout).isNull()
    }
}
