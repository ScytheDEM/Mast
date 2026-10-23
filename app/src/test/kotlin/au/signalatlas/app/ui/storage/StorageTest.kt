package au.signalatlas.app.ui.storage

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import au.signalatlas.app.data.Storage
import au.signalatlas.app.data.StorageSizes
import au.signalatlas.app.ui.theme.SignalAtlasTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class FakeStorage : Storage {
    var sizes = StorageSizes(68_000_000L, 1_500_000_000L, 350L * 1_048_576, 12L * 1_048_576)
    val calls = mutableListOf<String>()
    override fun sizes() = sizes
    override fun clearTerrain() { calls += "terrain"; sizes = sizes.copy(terrain = 0) }
    override fun clearCoverageCache() { calls += "cache"; sizes = sizes.copy(coverageCache = 0) }
    override var terrainBudgetMib = 2048
}

@OptIn(ExperimentalCoroutinesApi::class)
class StorageViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val storage = FakeStorage()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun refreshMeasuresAndClearsRemeasure() = runTest(dispatcher) {
        val vm = StorageViewModel(storage, io = dispatcher)
        assertThat(vm.state.value.sizes).isNull()
        assertThat(vm.state.value.terrainBudgetMib).isEqualTo(2048)
        vm.refresh()
        advanceUntilIdle()
        assertThat(vm.state.value.sizes!!.terrain).isEqualTo(350L * 1_048_576)
        vm.clearTerrain()
        advanceUntilIdle()
        assertThat(vm.state.value.sizes!!.terrain).isEqualTo(0)
        assertThat(vm.state.value.sizes!!.coverageCache).isEqualTo(12L * 1_048_576)
        vm.clearCoverageCache()
        advanceUntilIdle()
        assertThat(vm.state.value.sizes!!.coverageCache).isEqualTo(0)
        assertThat(storage.calls).containsExactly("terrain", "cache").inOrder()
    }

    @Test fun budgetIsPersistedThroughTheStorage() = runTest(dispatcher) {
        val vm = StorageViewModel(storage, io = dispatcher)
        vm.setTerrainBudgetMib(512)
        advanceUntilIdle()
        assertThat(storage.terrainBudgetMib).isEqualTo(512)
        assertThat(vm.state.value.terrainBudgetMib).isEqualTo(512)
    }
}

@RunWith(RobolectricTestRunner::class)
class StorageScreenTest {
    @get:Rule val compose = createComposeRule()

    private val res get() = ApplicationProvider.getApplicationContext<Context>().resources
    private var clearedTerrain = 0
    private var clearedCache = 0
    private var budget: Int? = null

    private fun show(sizes: StorageSizes? = StorageSizes(68_000_000L, 1_500_000_000L, 350L * 1_048_576, 12L * 1_048_576), mib: Int = 2048) =
        compose.setContent {
            SignalAtlasTheme {
                StorageScreen(
                    StorageUiState(sizes, mib),
                    onRefresh = {}, onClearTerrain = { clearedTerrain++ }, onClearCoverageCache = { clearedCache++ },
                    onBudget = { budget = it }, onBack = {},
                )
            }
        }

    @Test fun sizesAreHumanReadable() {
        assertThat(formatSize(res, 0)).isEqualTo("0 B")
        assertThat(formatSize(res, 1023)).isEqualTo("1023 B")
        assertThat(formatSize(res, 2048)).isEqualTo("2 KB")
        assertThat(formatSize(res, 12L * 1_048_576)).isEqualTo("12.0 MB")
        assertThat(formatSize(res, (1.4 * 1_073_741_824).toLong())).isEqualTo("1.4 GB")
    }

    @Test fun showsTheFourRowsWithSizes() {
        show()
        compose.onNodeWithText("Tower data").assertExists()
        compose.onNodeWithText("64.8 MB").assertExists()
        compose.onNodeWithText("Antenna patterns").assertExists()
        compose.onNodeWithText("1.4 GB").assertExists()
        compose.onNodeWithText("Terrain").assertExists()
        compose.onNodeWithText("350.0 MB").assertExists()
        compose.onNodeWithText("Coverage cache").assertExists()
        compose.onNodeWithText("12.0 MB").assertExists()
    }

    @Test fun onlyTerrainAndCoverageCacheCanBeCleared() {
        show()
        compose.onAllNodesWithText("Clear").assertCountEquals(2)
    }

    @Test fun clearingAsksFirst() {
        show()
        compose.onAllNodesWithText("Clear")[0].performClick()
        compose.onNodeWithText("Clear terrain data?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertThat(clearedTerrain).isEqualTo(0)
        compose.onAllNodesWithText("Clear")[0].performClick()
        compose.onAllNodesWithText("Clear")[2].performClick() // the dialog's button
        assertThat(clearedTerrain).isEqualTo(1)
        assertThat(clearedCache).isEqualTo(0)

        compose.onAllNodesWithText("Clear")[1].performClick()
        compose.onNodeWithText("Clear coverage cache?").assertIsDisplayed()
        compose.onAllNodesWithText("Clear")[2].performClick()
        assertThat(clearedCache).isEqualTo(1)
    }

    @Test fun budgetPickerOffersTheFourLimitsAndMarksTheCurrentOne() {
        show(mib = 1024)
        compose.onNodeWithText("Terrain storage limit").assertExists()
        compose.onNodeWithText("1 GB").performScrollTo().assertIsSelected()
        compose.onNodeWithText("4 GB").performScrollTo().performClick()
        assertThat(budget).isEqualTo(4096)
        compose.onNodeWithText("0.5 GB").performScrollTo().performClick()
        assertThat(budget).isEqualTo(512)
        compose.onNodeWithText("2 GB").assertExists()
    }

    @Test fun sizesShowMeasuringUntilKnown() {
        show(sizes = null)
        compose.onAllNodesWithText("Measuring…").assertCountEquals(4)
    }
}
