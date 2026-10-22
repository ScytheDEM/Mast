package au.signalatlas.app.ui.map

import au.signalatlas.app.data.BasemapKeys
import au.signalatlas.app.data.LocationSource
import au.signalatlas.app.data.TowerData
import au.signalatlas.app.data.TowerSnapshot
import au.signalatlas.app.data.Updates
import au.signalatlas.core.acma.ImportReport
import au.signalatlas.core.data.DataMeta
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Site
import au.signalatlas.core.model.SiteDetail
import au.signalatlas.core.model.SiteSummary
import au.signalatlas.core.model.Tech
import au.signalatlas.core.model.TowerFilter
import au.signalatlas.core.update.UpdateStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import java.time.Instant
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun site(id: Int, name: String, lat: Double, lon: Double, vararg nets: Network, techs: Set<Tech> = setOf(Tech.LTE)) =
        SiteSummary(id, lat, lon, name, nets.toSet(), techs, setOf("B28"))

    private val sites = listOf(
        site(101160, "Telstra Exchange 22 Barton Tce", -35.31, 149.13, Network.TELSTRA, Network.OPTUS),
        site(200, "Optus Rooftop Civic", -35.28, 149.13, Network.OPTUS, techs = setOf(Tech.LTE, Tech.NR)),
        site(300, "TPG Pole Kingston", -35.32, 149.15, Network.TPG),
        site(400, "NBN Tower Belconnen", -35.24, 149.06, Network.NBN),
    )

    private inner class FakeSnapshot(val all: List<SiteSummary>) : TowerSnapshot {
        var retired = false
        override val meta = DataMeta(
            "202610220500", Instant.parse("2026-10-22T06:00:00Z"),
            ImportReport(all.size, 10, 20, 5, 0, 0, 0, 0, emptyMap()),
        )
        override val allBands = listOf("B28", "n78")
        override fun allSites(f: TowerFilter) = all.filter { f.matches(it) }
        // Deliberately unsorted: the view model must order nearby sites by distance itself.
        override fun nearest(lat: Double, lon: Double, f: TowerFilter, limit: Int) =
            all.filter { f.matches(it) }.map { it to (abs(it.lat - lat) + abs(it.lon - lon)) * 100_000 }.reversed()
        override fun search(query: String, limit: Int) = all.filter { it.name.contains(query, ignoreCase = true) }
        override fun siteDetail(siteId: Int): SiteDetail? {
            if (retired) return null
            val s = all.firstOrNull { it.id == siteId } ?: return null
            return SiteDetail(Site(s.id, s.lat, s.lon, s.name, "ACT", "2600", null), emptyList())
        }
    }

    private class FakeData(initial: TowerSnapshot?) : TowerData {
        override val started = MutableStateFlow(true)
        override val changes = MutableStateFlow(initial)
        override fun current() = changes.value
    }

    private class FakeUpdates : Updates {
        override val status = MutableStateFlow<UpdateStatus>(UpdateStatus.Unknown)
        var checks = 0
        var cancels = 0
        val updateCalls = mutableListOf<Boolean>()
        override suspend fun check() { checks++ }
        override fun updateNow(allowMetered: Boolean) { updateCalls += allowMetered }
        override fun cancel() { cancels++ }
    }

    private class FakeBasemap(val key: String?, val delayMs: Long = 0, val failure: Exception? = null) : BasemapKeys {
        override suspend fun currentKey(): String? {
            delay(delayMs)
            failure?.let { throw it }
            return key
        }
    }

    private class FakeLocation : LocationSource {
        override val last = MutableStateFlow<Pair<Double, Double>?>(null)
        override val enabled = MutableStateFlow(true)
    }

    private fun TestScope.vm(
        data: TowerData = FakeData(FakeSnapshot(sites)),
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
        val snap = FakeSnapshot(sites)
        val vm = vm(data = FakeData(snap))
        advanceUntilIdle()
        assertThat(vm.state.value.hasData).isTrue()
        assertThat(vm.state.value.sites).hasSize(snap.allSites(TowerFilter()).size)
        assertThat(vm.state.value.availableBands).containsExactly("B28", "n78").inOrder()
        assertThat(vm.state.value.data?.sites).isEqualTo(sites.size)
    }

    @Test fun storeArrivingLater_switchesToData() = runTest(dispatcher) {
        val data = FakeData(null)
        val vm = vm(data = data)
        advanceUntilIdle()
        assertThat(vm.state.value.hasData).isFalse()
        data.changes.value = FakeSnapshot(sites)
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
        val old = FakeSnapshot(sites)
        val data = object : TowerData {
            val fresh = FakeSnapshot(sites)
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
        assertThat(vm.state.value.query).isEqualTo("barton")
        advanceTimeBy(100)
        runCurrent()
        assertThat(vm.state.value.searchResults).isEmpty() // still debouncing
        advanceUntilIdle()
        assertThat(vm.state.value.searchResults.map { it.id }).contains(101160)
        vm.search("")
        advanceUntilIdle()
        assertThat(vm.state.value.searchResults).isEmpty()
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
