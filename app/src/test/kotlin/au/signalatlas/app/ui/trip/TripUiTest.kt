package au.signalatlas.app.ui.trip

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import au.signalatlas.app.ui.coverage.CoverageActions
import au.signalatlas.app.ui.coverage.CoverageToolbar
import au.signalatlas.app.ui.theme.SignalAtlasTheme
import au.signalatlas.feature.trip.TechFloor
import au.signalatlas.feature.trip.TripConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TripUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsChangeEachOption() {
        val config = mutableStateOf(TripConfig())
        compose.setContent {
            SignalAtlasTheme {
                TripSettingsScreen(config.value, { f -> config.value = f(config.value) }, onBack = {})
            }
        }
        compose.onNodeWithText("120 s").performClick()
        compose.onNodeWithText("1000 m").performClick()
        compose.onNodeWithText("5G").performClick()
        compose.onNodeWithTag("trip-voice").performClick()
        assertThat(config.value).isEqualTo(TripConfig(120, 1000, TechFloor.LOSE_5G, true))
    }

    @Test fun toolbarShowsTheTripButtonAndTheBannerShowsItsText() {
        var taps = 0
        val noop = CoverageActions({}, {}, {}, {}, {}, {})
        compose.setContent {
            SignalAtlasTheme {
                androidx.compose.foundation.layout.Column {
                    TripBanner("Trip · Telstra 4G · No dead zones ahead")
                    CoverageToolbar(on = false, optionsOpen = false, actions = noop, tripRunning = true, onTrip = { taps++ })
                }
            }
        }
        compose.onNodeWithTag("trip-button").performClick()
        assertThat(taps).isEqualTo(1)
        compose.onNodeWithText("Trip · Telstra 4G · No dead zones ahead").assertIsDisplayed()
    }
}
