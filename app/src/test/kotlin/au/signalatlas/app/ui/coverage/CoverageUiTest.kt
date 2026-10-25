package au.signalatlas.app.ui.coverage

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import au.signalatlas.app.ui.map.BestSector
import au.signalatlas.app.ui.map.CoverageUiState
import au.signalatlas.app.ui.map.ReadoutResult
import au.signalatlas.app.ui.map.SignalQuality
import au.signalatlas.app.ui.theme.SignalAtlasTheme
import au.signalatlas.app.ui.updates.PatternsSection
import au.signalatlas.core.coverage.CoverageSelection
import au.signalatlas.core.model.Network
import au.signalatlas.core.update.HrpStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class CoverageUiTest {
    @get:Rule val compose = createComposeRule()

    private val res get() = ApplicationProvider.getApplicationContext<Context>().resources

    private fun predicted(dbm: Int, q: SignalQuality, pct: Int?, best: BestSector?) = ReadoutResult.Predicted(dbm, q, pct, best)

    @Test fun readoutStringsAreExact() {
        val best = BestSector(Network.TELSTRA, "n78", 1234.0)
        assertThat(predicted(-97, SignalQuality.GOOD, 82, best).lines(res))
            .containsExactly("Predicted −97 dBm · Good · 82% reliable", "Best sector: Telstra n78 · 1.2 km").inOrder()
        assertThat(predicted(-70, SignalQuality.EXCELLENT, 100, BestSector(Network.OPTUS, "B28", 850.0)).lines(res))
            .containsExactly("Predicted −70 dBm · Excellent · 100% reliable", "Best sector: Optus B28 · 850 m").inOrder()
        assertThat(predicted(-112, SignalQuality.WEAK, 55, null).lines(res))
            .containsExactly("Predicted −112 dBm · Weak · 55% reliable")
        assertThat(ReadoutResult.Calculating.lines(res)).containsExactly("Calculating…")
        assertThat(ReadoutResult.NoPrediction.lines(res)).containsExactly("No prediction here")
        assertThat(ReadoutResult.NoCoverage.lines(res)).containsExactly("No predicted coverage")
    }

    @Test fun distancesUnderAKilometreAreWholeMetresOtherwiseOneDecimalKm() {
        assertThat(distanceLabel(res, 0.0)).isEqualTo("0 m")
        assertThat(distanceLabel(res, 849.6)).isEqualTo("850 m")
        assertThat(distanceLabel(res, 999.4)).isEqualTo("999 m")
        assertThat(distanceLabel(res, 999.6)).isEqualTo("1.0 km") // rounds to 1000 m: never "1000 m"
        assertThat(distanceLabel(res, 1_234.0)).isEqualTo("1.2 km")
        assertThat(distanceLabel(res, 12_340.0)).isEqualTo("12.3 km")
    }

    @Test fun readoutCardShowsItsLines() {
        compose.setContent {
            SignalAtlasTheme {
                PointReadout(predicted(-97, SignalQuality.GOOD, 82, BestSector(Network.TELSTRA, "n78", 1234.0)))
            }
        }
        compose.onNodeWithText("Predicted −97 dBm · Good · 82% reliable").assertIsDisplayed()
        compose.onNodeWithText("Best sector: Telstra n78 · 1.2 km").assertIsDisplayed()
    }

    @Test fun signalLegendHasTheFourBands() {
        compose.setContent { SignalAtlasTheme { CoverageLegend(CoverageSelection.View.RSRP) } }
        for (t in listOf("Excellent", "Good", "Fair", "Weak")) compose.onNodeWithText(t).assertIsDisplayed()
        compose.onNodeWithText("≥ 90%").assertDoesNotExist()
    }

    @Test fun reliabilityLegendHasTwoRanges() {
        compose.setContent { SignalAtlasTheme { CoverageLegend(CoverageSelection.View.PROBABILITY) } }
        compose.onNodeWithText("≥ 90%").assertIsDisplayed()
        compose.onNodeWithText("50–90%").assertIsDisplayed()
        compose.onNodeWithText("Weak").assertDoesNotExist()
    }

    @Test fun legendColoursAreTheRule11Ones() {
        assertThat(LegendExcellent.value).isEqualTo(0xFF0B5FA5.toULong() shl 32)
        assertThat(LegendGood.value).isEqualTo(0xFF2A9D8F.toULong() shl 32)
        assertThat(LegendFair.value).isEqualTo(0xFFE9C46A.toULong() shl 32)
        assertThat(LegendWeak.value).isEqualTo(0xFFF4A261.toULong() shl 32)
    }

    @Test fun controlsCardOffersEveryControlAndReportsChanges() {
        val calls = mutableListOf<String>()
        val actions = CoverageActions(
            onToggle = { calls += "toggle $it" }, onOptions = { calls += "options" }, onNetwork = { calls += "net $it" },
            onFiveGOnly = { calls += "5g $it" }, onIndoor = { calls += "indoor $it" }, onView = { calls += "view $it" },
        )
        compose.setContent { SignalAtlasTheme { CoverageControlsCard(CoverageUiState(on = true), actions) } }
        for (t in listOf("Telstra", "Optus", "TPG", "NBN", "4G + 5G", "5G only", "Indoor", "Signal", "Reliability")) {
            compose.onNodeWithText(t).assertIsDisplayed()
        }
        compose.onNodeWithText("Optus").performClick()
        compose.onNodeWithText("5G only").performClick()
        compose.onNodeWithText("Reliability").performClick()
        assertThat(calls).containsExactly("net OPTUS", "5g true", "view PROBABILITY").inOrder()
    }

    @Test fun toolbarTogglesOptionsAndTrip() {
        var options = 0
        var trips = 0
        compose.setContent {
            SignalAtlasTheme { CoverageToolbar(optionsOpen = true, onOptions = { options++ }, onTrip = { trips++ }) }
        }
        compose.onNodeWithText("Options").performClick()
        compose.onNodeWithText("Trip").performClick()
        assertThat(options).isEqualTo(1)
        assertThat(trips).isEqualTo(1)
    }

    @Test fun patternsLineSaysWhenTheyWereLastUpdated() {
        compose.setContent {
            SignalAtlasTheme { PatternsSection(HrpStatus(Instant.parse("2026-10-22T20:00:00Z"), 28_431, null), onDownload = {}) }
        }
        // 20:00Z on the 22nd is the 23rd in Sydney.
        compose.onNodeWithText("Antenna patterns: Updated 23 Oct 2026 (28,431 transmitters)").assertIsDisplayed()
        compose.onNodeWithText("Download patterns now").assertIsDisplayed()
    }

    @Test fun patternsLineWithoutPatternsSaysStandardAndOneTransmitterIsSingular() {
        compose.setContent { SignalAtlasTheme { PatternsSection(HrpStatus(null, 0, null), onDownload = {}) } }
        compose.onNodeWithText(
            "Antenna patterns: Using standard patterns until the monthly download runs (Wi-Fi + charging)",
        ).assertIsDisplayed()
        assertThat(res.getQuantityString(au.signalatlas.app.R.plurals.patterns_transmitters, 1, "1")).isEqualTo("1 transmitter")
    }
}
