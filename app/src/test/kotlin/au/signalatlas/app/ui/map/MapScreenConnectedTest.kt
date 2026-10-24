package au.signalatlas.app.ui.map

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import au.signalatlas.app.connected.ConnectedStatus
import au.signalatlas.app.connected.FIX
import au.signalatlas.app.connected.connectedUi
import au.signalatlas.app.connected.match
import au.signalatlas.app.connected.radio
import androidx.compose.ui.test.performScrollTo
import au.signalatlas.app.connected.ConnectedUi
import au.signalatlas.app.location.DeviceLocationSource
import au.signalatlas.app.ui.theme.SignalAtlasTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The info bar on the map and the phone-access explanation behind it. */
@RunWith(RobolectricTestRunner::class)
class MapScreenConnectedTest {
    @get:Rule val compose = createComposeRule()

    private val vm = MapViewModel(
        FakeData(FakeSnapshot(SITES)), FakeUpdates(), FakeBasemap(null), FakeLocation(), FakeCoverage(),
        compute = Dispatchers.Unconfined, io = Dispatchers.Unconfined,
    )
    private var starts = 0
    private var stops = 0

    private fun show(ui: ConnectedUi?, offer: Boolean = false) {
        val location = DeviceLocationSource(ApplicationProvider.getApplicationContext())
        val actions = ConnectedActions({ starts++ }, { stops++ }, {}, { offer })
        compose.setContent {
            SignalAtlasTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                MapScreen(state, vm, location, onOpenUpdates = {}, renderMap = false, connected = ui, connectedActions = actions)
            }
        }
    }

    @Test fun withoutPermissionTheBarAsksAndTheRationaleIsTheExactText() {
        show(ConnectedUi(ConnectedStatus.NEEDS_PHONE))
        compose.onNodeWithText("Allow phone access to see your tower").assertIsDisplayed()
        compose.onNodeWithTag("connected-bar").performClick()
        compose.onNodeWithText(
            "Signal Atlas reads your phone's mobile network details (carrier, tower, band, signal) to show which tower " +
                "you're using and improve predictions. It never reads calls, messages or phone numbers.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithText("Not now").assertDoesNotExist()
    }

    @Test fun theExplanationIsVolunteeredOnceWhenThePermissionIsMissing() {
        show(ConnectedUi(ConnectedStatus.NEEDS_PHONE), offer = true)
        compose.onNodeWithText("See your tower").assertIsDisplayed()
    }

    @Test fun tappingTheBarOpensCellularDetailsAndOpenTowerSelectsTheSite() {
        val ui = connectedUi(radio(), match(), FIX).let { it.copy(tower = it.tower!!.copy(siteId = 101160)) }
        show(ui)
        compose.onNodeWithTag("connected-bar").performClick()
        settle()
        compose.onNodeWithText("Cellular details").assertIsDisplayed()
        assertThat(vm.state.value.selectedId).isNull()
        compose.onNodeWithText("Open tower").performScrollTo().performClick()
        settle()
        assertThat(vm.state.value.selectedId).isEqualTo(101160)
    }

    @Test fun theRadioRunsOnlyWhileTheScreenIsStarted() {
        show(null)
        compose.waitForIdle()
        assertThat(starts).isAtLeast(1)
        assertThat(stops).isEqualTo(0)
    }

    private fun settle() = repeat(3) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }
}
