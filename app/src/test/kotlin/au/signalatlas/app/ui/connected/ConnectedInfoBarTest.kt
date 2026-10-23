package au.signalatlas.app.ui.connected

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import au.signalatlas.app.connected.ConnectedStatus
import au.signalatlas.app.connected.ConnectedUi
import au.signalatlas.app.connected.TELSTRA
import au.signalatlas.app.connected.connectedUi
import au.signalatlas.app.connected.FIX
import au.signalatlas.app.connected.lte
import au.signalatlas.app.connected.match
import au.signalatlas.app.connected.nr
import au.signalatlas.app.connected.radio
import au.signalatlas.app.ui.theme.SignalAtlasTheme
import au.signalatlas.core.calibration.Confidence
import au.signalatlas.core.radio.Permission
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConnectedInfoBarTest {
    @get:Rule val compose = createComposeRule()

    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test fun theBriefsExampleLine() {
        val ui = connectedUi(radio(nrSecondaries = listOf(nr()), fiveG = true), match(), FIX)
        assertThat(connectedText(ui, res)).isEqualTo("Telstra 5G · B28 + n78 · −94 dBm · tower 1.3 km NE · Likely · ≈ 57 Mbps")
    }

    @Test fun anMvnoShowsItsOwnNameAndUncertainMatchesSaySo() {
        val sim = TELSTRA.copy(carrierName = "Boost")
        val ui = connectedUi(radio(sim = sim), match(Confidence.UNCERTAIN), FIX)
        assertThat(connectedText(ui, res)).isEqualTo("Boost 4G · B28 · −94 dBm · tower 1.3 km NE · Uncertain")
    }

    @Test fun everyNonConnectedStateHasItsExplanation() {
        fun text(r: au.signalatlas.core.radio.RadioState) = connectedText(connectedUi(r, null, FIX), res)
        assertThat(text(radio(permission = Permission.MISSING_PHONE))).isEqualTo("Allow phone access to see your tower")
        assertThat(text(radio(permission = Permission.MISSING_LOCATION))).isEqualTo("Allow location access to see your tower")
        assertThat(text(radio(airplane = true))).contains("Airplane mode")
        assertThat(text(radio(sim = null))).contains("No SIM")
        assertThat(text(radio(sim = TELSTRA.copy(roaming = true)))).contains("Roaming")
        assertThat(text(radio(serving = null))).contains("Looking for a signal")
        assertThat(text(radio(sim = TELSTRA.copy(network = null, carrierName = "Verizon")))).startsWith("Verizon")
    }

    @Test fun distancesAreHumanReadable() {
        assertThat(distance(850.0)).isEqualTo("850 m")
        assertThat(distance(1300.0)).isEqualTo("1.3 km")
        assertThat(distance(12_400.0)).isEqualTo("12 km")
    }

    @Test fun tappingTheBarReportsAClick() {
        var clicks = 0
        compose.setContent {
            SignalAtlasTheme { ConnectedInfoBar(ConnectedUi(ConnectedStatus.NEEDS_PHONE), onClick = { clicks++ }) }
        }
        compose.onNodeWithText("Allow phone access to see your tower").assertIsDisplayed()
        compose.onNodeWithTag("connected-bar").performClick()
        assertThat(clicks).isEqualTo(1)
    }
}
