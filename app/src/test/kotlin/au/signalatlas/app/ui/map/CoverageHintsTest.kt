package au.signalatlas.app.ui.map

import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import au.signalatlas.app.data.UiHints
import au.signalatlas.app.location.DeviceLocationSource
import au.signalatlas.app.ui.theme.SignalAtlasTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class CoverageHintsTest {
    @get:Rule val compose = createComposeRule()

    private val vm = MapViewModel(
        FakeData(FakeSnapshot(SITES)), FakeUpdates(), FakeBasemap(null), FakeLocation(), FakeCoverage(),
        compute = Dispatchers.Unconfined, io = Dispatchers.Unconfined,
    )

    @Test fun calculatingPillShowsOnlyForCoverageInRangeWithOutstandingTiles() {
        val on = CoverageUiState(on = true, calculating = true)
        assertThat(on.showsCalculating(zoom = 12.0)).isTrue()
        assertThat(on.showsCalculating(zoom = null)).isTrue() // zoom not reported yet: not "too far out"
        assertThat(on.showsCalculating(zoom = 6.0)).isFalse() // zoomed out: the "zoom in" label says why
        assertThat(on.copy(calculating = false).showsCalculating(12.0)).isFalse()
        assertThat(on.copy(on = false).showsCalculating(12.0)).isFalse()
    }

    @Test fun viewModelTracksLoadingAndClearsItWhenCoverageGoesOff() {
        vm.setCoverageOn(true)
        vm.onCoverageLoading(true)
        assertThat(vm.state.value.coverage.showsCalculating(12.0)).isTrue()
        vm.onCoverageLoading(false)
        assertThat(vm.state.value.coverage.showsCalculating(12.0)).isFalse()
        vm.onCoverageLoading(true)
        vm.setCoverageOn(false)
        assertThat(vm.state.value.coverage.calculating).isFalse()
    }

    @Test fun showCoverageForTurnsTheOverlayOnForThatNetwork() {
        vm.showCoverageFor(au.signalatlas.core.model.Network.OPTUS)
        assertThat(vm.state.value.coverage.on).isTrue()
        assertThat(vm.state.value.coverage.network).isEqualTo(au.signalatlas.core.model.Network.OPTUS)
    }

    @Test fun explainerAppearsOnFirstUseAndStaysDismissed() {
        val hints = UiHints.InMemory()
        val location = DeviceLocationSource(ApplicationProvider.getApplicationContext())
        compose.setContent {
            SignalAtlasTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                MapScreen(state, vm, location, onOpenUpdates = {}, renderMap = false, hints = hints)
            }
        }
        compose.onNodeWithTag("coverage-explainer").assertDoesNotExist()
        compose.onNodeWithText("Coverage").performClick()
        settle()
        compose.onNodeWithTag("coverage-explainer").assertIsDisplayed()
        compose.onNodeWithText("Got it").performClick()
        settle()
        compose.onNodeWithTag("coverage-explainer").assertDoesNotExist()
        assertThat(hints.coverageExplainerSeen()).isTrue()
    }

    @Test fun explainerIsNotShownAgainOnceSeen() {
        val hints = UiHints.InMemory(seen = true)
        val location = DeviceLocationSource(ApplicationProvider.getApplicationContext())
        vm.setCoverageOn(true)
        compose.setContent {
            SignalAtlasTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                MapScreen(state, vm, location, onOpenUpdates = {}, renderMap = false, hints = hints)
            }
        }
        settle()
        compose.onNodeWithTag("coverage-explainer").assertDoesNotExist()
    }

    private fun settle() = repeat(3) {
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }
}
