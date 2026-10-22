package au.signalatlas.app.ui.map

import au.signalatlas.app.data.BasemapKeys
import au.signalatlas.app.data.LocationSource
import au.signalatlas.app.data.TowerData
import au.signalatlas.app.data.TowerSnapshot
import au.signalatlas.app.data.Updates
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.TowerFilter
import au.signalatlas.core.update.UpdateStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(
        data: TowerData = FakeData(FakeSnapshot(SITES)),
        updates: Updates = FakeUpdates(),
        basemap: BasemapKeys = FakeBasemap("20261022.pmtiles"),
        location: LocationSource = FakeLocation(),
    ) = MapViewModel(data, updates, basemap, location, compute = dispatcher, io = dispatcher)

    @Test fun noStore_hasNoData() = runTest(dispatcher) {
        val vm = vm(data = FakeData(null))
        advanceUntilIdle()
        assertThat(vm.state.value.loading).isFalse()
        assertThat(vm.state.value.hasData).isFalse()
        assertThat(vm.state.value.sites).isEmpty()
    }

    @Test fun storePresent_showsAllSitesForDefaultFilter() = runTest(dispatcher) {
        val snap = FakeSnapshot(SITES)
        val vm = vm(data = FakeData(snap))
        advanceUntilIdle()
        assertThat(vm.state.value.hasData).isTrue()
        assertThat(vm.state.value.sites).hasSize(snap.allSites(TowerFilter()).size)
        assertThat(vm.state.value.availableBands).containsExactly("B28", "n78").inOrder()
        assertThat(vm.state.value.data?.sites).isEqualTo(SITES.size)
    }

    @Test fun storeArrivingLater_switchesToData() = runTest(dispatcher) {
        val data = FakeData(null)
        val vm = vm(data = data)
        advanceUntilIdle()
        assertThat(vm.state.value.hasData).isFalse()
        data.changes.value = FakeSnapshot(SITES)
        advanceUntilIdle()
        assertThat(vm.state.value.hasData).isTrue()
        assertThat(vm.state.value.sites).hasSize(4)
    }

    @Test fun setFilter_keepsOnlyMatchingNetworks() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.setFilter(TowerFilter(networks = setOf(Network.OPTUS)))
        assertThat(vm.state.value.filter.networks).containsExactly(Network.OPTUS)
        advanceUntilIdle()
        assertThat(vm.state.value.sites).isNotEmpty()
        assertThat(vm.state.value.sites.all { Network.OPTUS in it.networks }).isTrue()
        assertThat(vm.state.value.sites.map { it.id }).containsExactly(101160, 200)
    }

    @Test fun sitesListIdentity_isStableWhenFilterUnchanged() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val before = vm.state.value.sites
        vm.setFilter(TowerFilter())
        vm.search("barton")
        advanceUntilIdle()
        assertThat(vm.state.value.sites).isSameInstanceAs(before)
    }

    @Test fun selectSite_loadsDetail_andNullClears() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.selectSite(101160)
        advanceUntilIdle()
        assertThat(vm.state.value.selected?.site?.id).isEqualTo(101160)
        vm.selectSite(null)
        advanceUntilIdle()
        assertThat(vm.state.value.selected).isNull()
    }

    @Test fun selectSite_retiredStore_requeriesCurrentStore() = runTest(dispatcher) {
        val old = FakeSnapshot(SITES)
        val data = object : TowerData {
            val fresh = FakeSnapshot(SITES)
            var reads = 0
            override val started = MutableStateFlow(true)
            override val changes = MutableStateFlow<TowerSnapshot?>(old)
            // The first read hands out a store that has just been retired; the next is the new one.
            override fun current(): TowerSnapshot? = if (reads++ == 0) old.also { it.retired = true } else fresh
        }
        val vm = vm(data = data)
        advanceUntilIdle()
        vm.selectSite(101160)
        advanceUntilIdle()
        assertThat(vm.state.value.selected?.site?.id).isEqualTo(101160)
    }

    @Test fun selectSite_unknownId_clearsSelection() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.selectSite(999_999)
        advanceUntilIdle()
        assertThat(vm.state.value.selected).isNull()
        assertThat(vm.state.value.selectedId).isNull()
    }

    @Test fun search_findsBySiteName_afterDebounce() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.search("barton")
        assertThat(vm.state.value.search.query).isEqualTo("barton")
        advanceTimeBy(100)
        runCurrent()
        assertThat(vm.state.value.search.pending).isTrue() // still debouncing
        assertThat(vm.state.value.search.resultsFor("barton")).isNull()
        advanceUntilIdle()
        assertThat(vm.state.value.search.pending).isFalse()
        assertThat(vm.state.value.search.resultsFor("barton")!!.map { it.id }).contains(101160)
        vm.search("")
        advanceUntilIdle()
        assertThat(vm.state.value.search.results).isEmpty()
    }

    @Test fun search_postcode_findsItsSites() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        // Typed one digit at a time, as from the keyboard.
        for (prefix in listOf("2", "26", "260", "2600")) {
            vm.search(prefix)
            advanceTimeBy(50)
            runCurrent()
        }
        advanceUntilIdle()
        val search = vm.state.value.search
        assertThat(search.query).isEqualTo("2600")
        assertThat(search.resultsFor).isEqualTo("2600")
        assertThat(search.results.map { it.id }).containsExactly(101160, 300)
    }

    @Test fun search_resultsAreTaggedWithTheirQuery_neverShownForAnother() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.search("barton")
        advanceUntilIdle()
        vm.search("2600")
        runCurrent()
        // The "barton" results are still in state, but they are not results for "2600".
        assertThat(vm.state.value.search.resultsFor("2600")).isNull()
        assertThat(vm.state.value.search.pending).isTrue()
        advanceUntilIdle()
        assertThat(vm.state.value.search.resultsFor("2600")!!.map { it.id }).containsExactly(101160, 300)
    }

    @Test fun permissionDenied_noNearby_noCrash() = runTest(dispatcher) {
        val location = FakeLocation()
        val vm = vm(location = location)
        advanceUntilIdle()
        vm.onLocationPermission(false)
        vm.onLocation(-35.3, 149.13)
        location.last.value = -35.3 to 149.13
        advanceUntilIdle()
        assertThat(vm.state.value.locationGranted).isFalse()
        assertThat(vm.state.value.nearby).isEmpty()
    }

    @Test fun onLocation_withPermission_nearbySortedAscending() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onLocationPermission(true)
        vm.onLocation(-35.3, 149.13)
        advanceUntilIdle()
        val distances = vm.state.value.nearby.map { it.second }
        assertThat(vm.state.value.locationGranted).isTrue()
        assertThat(distances).hasSize(4)
        assertThat(distances).isInOrder()
        assertThat(vm.state.value.location).isEqualTo(-35.3 to 149.13)
    }

    @Test fun locationSource_feedsNearby() = runTest(dispatcher) {
        val location = FakeLocation()
        val vm = vm(location = location)
        vm.onLocationPermission(true)
        location.last.value = -35.24 to 149.06
        advanceUntilIdle()
        assertThat(vm.state.value.nearby.first().first.id).isEqualTo(400)
    }

    @Test fun updates_statusMirrored_andActionsForwarded() = runTest(dispatcher) {
        val updates = FakeUpdates()
        val vm = vm(updates = updates)
        advanceUntilIdle()
        updates.status.value = UpdateStatus.Running("download", 0.4f)
        advanceUntilIdle()
        assertThat(vm.state.value.update).isEqualTo(UpdateStatus.Running("download", 0.4f))
        vm.checkUpdates()
        vm.updateNow(allowMetered = true)
        vm.cancelUpdate()
        advanceUntilIdle()
        assertThat(updates.checks).isEqualTo(1)
        assertThat(updates.updateCalls).containsExactly(true)
        assertThat(updates.cancels).isEqualTo(1)
    }

    @Test fun basemap_resolvedBeforeMapShown() = runTest(dispatcher) {
        val vm = vm(basemap = FakeBasemap("20261022.pmtiles", delayMs = 200))
        runCurrent()
        assertThat(vm.state.value.basemapResolved).isFalse()
        advanceUntilIdle()
        assertThat(vm.state.value.basemapResolved).isTrue()
        assertThat(vm.state.value.basemapKey).isEqualTo("20261022.pmtiles")
    }

    @Test fun basemap_slowLookup_showsMapAfterTimeout_thenAppliesKey() = runTest(dispatcher) {
        val vm = vm(basemap = FakeBasemap("20261022.pmtiles", delayMs = 5_000))
        advanceTimeBy(1_501)
        runCurrent()
        assertThat(vm.state.value.basemapResolved).isTrue()
        assertThat(vm.state.value.basemapKey).isNull()
        advanceUntilIdle()
        assertThat(vm.state.value.basemapKey).isEqualTo("20261022.pmtiles")
    }

    @Test fun basemapLookupFailure_becomesNullKey() = runTest(dispatcher) {
        val vm = vm(basemap = FakeBasemap(null, failure = java.io.IOException("datastore corrupt")))
        advanceUntilIdle()
        assertThat(vm.state.value.basemapResolved).isTrue()
        assertThat(vm.state.value.basemapKey).isNull()
    }

    @Test fun locationServicesOff_isExposed() = runTest(dispatcher) {
        val location = FakeLocation()
        location.enabled.value = false
        val vm = vm(location = location)
        vm.onLocationPermission(true)
        advanceUntilIdle()
        assertThat(vm.state.value.locationEnabled).isFalse()
        location.enabled.value = true
        advanceUntilIdle()
        assertThat(vm.state.value.locationEnabled).isTrue()
    }
}
