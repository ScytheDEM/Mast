package au.signalatlas.app.ui.map

import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import au.signalatlas.app.location.DeviceLocationSource
import au.signalatlas.app.ui.theme.SignalAtlasTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/** The compact (phone) layout: search box -> result -> site sheet. */
@RunWith(RobolectricTestRunner::class)
class MapScreenSearchTest {
    @get:Rule val compose = createComposeRule()

    private val vm = MapViewModel(
        FakeData(FakeSnapshot(SITES)), FakeUpdates(), FakeBasemap(null), FakeLocation(),
        compute = Dispatchers.Unconfined, io = Dispatchers.Unconfined,
    )

    private fun show() {
        val location = DeviceLocationSource(ApplicationProvider.getApplicationContext())
        compose.setContent {
            SignalAtlasTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                MapScreen(state, vm, location, onOpenUpdates = {}, renderMap = false)
            }
        }
    }

    /** Lets the search debounce (on the main looper) and any animations finish. */
    private fun settle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
        }
    }

    @Test fun postcodeSearchShowsItsSites_thenAResultOpensTheSiteSheet() {
        show()
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("2600")
        // Mid-debounce: no "No matching sites" for a lookup that has not run yet.
        compose.onNodeWithText("No matching sites").assertDoesNotExist()
        settle()

        compose.onNode(hasSetTextAction()).assertTextEquals("2600") // typed once, not "26002600"
        compose.onNodeWithText("No matching sites").assertDoesNotExist()
        compose.onNodeWithText("TPG Pole Kingston").assertIsDisplayed()
        compose.onNodeWithText("Telstra Exchange 22 Barton Tce").assertIsDisplayed().performClick()
        settle()

        compose.onNodeWithText("Site 101160 · ACT 2600").assertIsDisplayed()
        assertThatSelected(101160)

        // Closing animates the sheet away and clears the selection.
        compose.onNodeWithContentDescription("Close").performClick()
        settle()
        compose.onNodeWithText("Site 101160 · ACT 2600").assertDoesNotExist()
        assertThatSelected(null)
    }

    @Test fun aQueryWithNoMatchSaysSo() {
        show()
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("9999")
        settle()
        compose.onNodeWithText("No matching sites").assertIsDisplayed()
    }

    private fun assertThatSelected(id: Int?) {
        com.google.common.truth.Truth.assertThat(vm.state.value.selectedId).isEqualTo(id)
    }
}
