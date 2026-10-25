package au.signalatlas.app.ui.map

import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import au.signalatlas.app.location.DeviceLocationSource
import au.signalatlas.app.ui.theme.SignalAtlasTheme
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import au.signalatlas.core.model.TowerFilter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class FiltersTest {
    @get:Rule val compose = createComposeRule()

    @Test fun activeCountCountsNarrowedNetworksTechsAndBands() {
        assertThat(TowerFilter().activeCount()).isEqualTo(0)
        assertThat(TowerFilter().toggleNetwork(Network.OPTUS).activeCount()).isEqualTo(1)
        assertThat(TowerFilter().toggleNetwork(Network.OPTUS).toggleNetwork(Network.TPG).activeCount()).isEqualTo(2)
        assertThat(TowerFilter().toggleTech(Tech.NR).toggleBand("n78").toggleBand("B3").activeCount()).isEqualTo(3)
        assertThat(TowerFilter().toggleNetwork(Network.OPTUS).toggleTech(Tech.NR).reset().activeCount()).isEqualTo(0)
    }

    @Test fun dasChipTogglesAndCounts() {
        val f = TowerFilter().toggleDas()
        assertThat(f.dasOnly).isTrue()
        assertThat(f.activeCount()).isEqualTo(1)
        assertThat(f.reset().dasOnly).isFalse()
        assertThat(f.toggleDas().dasOnly).isFalse()
    }

    @Test fun overflowIconBadgeShowsTheCountAndHidesAtZero() {
        var count by mutableStateOf(2)
        var opened = 0
        compose.setContent {
            SignalAtlasTheme {
                TowerSearchBar(
                    SearchUiState(), expanded = false, onExpandedChange = {}, onQueryChange = {}, onResultClick = {},
                    onOpenUpdates = {}, docked = true, filterCount = count, onFilters = { opened++ },
                )
            }
        }
        compose.onNodeWithTag("filters-badge", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("overflow-button").performClick()
        compose.onNodeWithText("Filters (2 active)").assertIsDisplayed()
        compose.onNodeWithTag("menu-filters").performClick()
        assertThat(opened).isEqualTo(1)
        count = 0
        compose.waitForIdle()
        compose.onNodeWithTag("filters-badge", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun chipsToggleNetworksAndResetClearsEverything() {
        var filter by mutableStateOf(TowerFilter())
        compose.setContent {
            SignalAtlasTheme {
                FiltersContent(filter, listOf("B28", "n78"), onFilter = { filter = it }, onClose = {})
            }
        }
        compose.onNodeWithText("Reset").assertIsNotEnabled()
        compose.onNodeWithTag("filter-net-OPTUS").performClick()
        assertThat(filter.networks).containsExactly(Network.OPTUS)
        compose.onNodeWithTag("filter-net-TPG").performClick()
        assertThat(filter.networks).containsExactly(Network.OPTUS, Network.TPG)
        compose.onNodeWithTag("filter-tech-NR").performClick()
        assertThat(filter.techs).containsExactly(Tech.NR)
        compose.onNodeWithText("n78").performScrollTo().performClick()
        assertThat(filter.bands).containsExactly("n78")
        compose.onNodeWithText("Reset").performScrollTo().assertIsEnabled().performClick()
        assertThat(filter).isEqualTo(TowerFilter())
    }

    @Test fun theOverflowMenuOpensFiltersOnTheMapScreen() {
        val vm = MapViewModel(
            FakeData(FakeSnapshot(SITES)), FakeUpdates(), FakeBasemap(null), FakeLocation(), FakeCoverage(),
            compute = Dispatchers.Unconfined, io = Dispatchers.Unconfined,
        )
        val location = DeviceLocationSource(ApplicationProvider.getApplicationContext())
        compose.setContent {
            SignalAtlasTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                MapScreen(state, vm, location, onOpenUpdates = {}, renderMap = false)
            }
        }
        compose.onNodeWithTag("filters-button").assertDoesNotExist() // no toolbar button any more
        compose.onNodeWithTag("overflow-button").performClick()
        compose.onNodeWithTag("menu-filters").performClick()
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
        }
        compose.onNodeWithText("Networks").assertIsDisplayed()
        compose.onNodeWithTag("filter-net-OPTUS").performClick()
        assertThat(vm.state.value.filter.networks).containsExactly(Network.OPTUS)
        compose.onNodeWithTag("filters-badge", useUnmergedTree = true).assertIsDisplayed()
    }
}
