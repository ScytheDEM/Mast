package au.signalatlas.app.ui.connected

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import au.signalatlas.app.connected.CalibrationHooks
import au.signalatlas.app.connected.ConnectedStatus
import au.signalatlas.app.connected.ConnectedUi
import au.signalatlas.app.connected.FIX
import au.signalatlas.app.connected.TELSTRA
import au.signalatlas.app.connected.connectedUi
import au.signalatlas.app.connected.lte
import au.signalatlas.app.connected.match
import au.signalatlas.app.connected.nr
import au.signalatlas.app.connected.radio
import au.signalatlas.app.ui.theme.SignalAtlasTheme
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import au.signalatlas.core.radio.ObservedCell
import au.signalatlas.core.radio.SimInfo
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CellularDetailsTest {
    @get:Rule val compose = createComposeRule()

    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private val OPTUS = SimInfo(2, Network.OPTUS, "Optus", "50502", false)

    private fun neighbour(pci: Int, rsrp: Int?, band: String = "B3") =
        ObservedCell(Tech.LTE, 1300, 1850.0, band, pci, null, 1, rsrp, -12, null, null, false)

    private val primary = connectedUi(
        radio(
            serving = lte(rsrp = -97, band = "B28"),
            nrSecondaries = listOf(nr()),
            fiveG = true,
        ).copy(neighbours = listOf(neighbour(30, null), neighbour(20, -104), neighbour(21, -90, "B1"))),
        match(), FIX,
    ).copy(predictedDbm = -92)
    private val secondary = connectedUi(radio(sim = OPTUS, serving = lte(rsrp = -88, band = "B1")), null, FIX).copy(isData = false)
    private val dual = primary.copy(otherSims = listOf(secondary))

    @Test fun barsFollowRsrp() {
        assertThat(listOf(-80, -90, -100, -110, -120, null).map { signalBars(it) }).containsExactly(4, 3, 2, 1, 0, 0).inOrder()
    }

    @Test fun measuredVersusPredictedReadsLikeTheBrief() {
        assertThat(measuredVsPredicted(res, -97, -92)).isEqualTo("Measured −97 dBm vs −92 dBm predicted here")
        assertThat(measuredVsPredicted(res, -97, null)).isNull()
        assertThat(predictionDelta(res, -97, -92)).isEqualTo("5 dB weaker than predicted")
        assertThat(predictionDelta(res, -85, -92)).isEqualTo("7 dB stronger than predicted")
        assertThat(predictionDelta(res, -93, -92)).isEqualTo("In line with the prediction")
    }

    @Test fun modelSortsNeighboursStrongestFirstWithUnknownLast() {
        assertThat(primary.cells!!.neighbours.map { it.pci }).containsExactly(21, 20, 30).inOrder()
        assertThat(primary.bandList).containsExactly("B28", "n78").inOrder()
        assertThat(primary.predictedDbm).isEqualTo(-92)
        assertThat(primary.tower!!.sectorIdent).isEqualTo("41428|0|1234|1200|300")
    }

    @Test fun servingRowsListTheCellFacts() {
        val rows = cellRows(lte(rsrp = -97, band = "B28").copy(taMeters = 312.0), res).toMap()
        assertThat(rows["Technology"]).isEqualTo("4G LTE")
        assertThat(rows["Band"]).isEqualTo("B28")
        assertThat(rows["Channel"]).isEqualTo("9410 · 758 MHz")
        assertThat(rows["PCI"]).isEqualTo("101")
        assertThat(rows["Cell ID"]).isEqualTo("123")
        assertThat(rows["TAC"]).isEqualTo("1")
        assertThat(rows["RSRP"]).isEqualTo("−97 dBm")
        assertThat(rows["RSRQ"]).isEqualTo("−10 dB")
        assertThat(rows["SINR"]).isEqualTo("15 dB")
        assertThat(rows["Distance (timing advance)"]).isEqualTo("≈ 310 m")
    }

    @Test fun dualSimSummariesNameTheDataSimAndEachCarrier() {
        assertThat(dual.allSims.mapIndexed { i, s -> simSummary(s, res, i) }).containsExactly(
            "Telstra · Data SIM · 5G · B28 + n78 · −97 dBm",
            "Optus · 4G · B1 · −88 dBm",
        ).inOrder()
    }

    @Test fun theBarShowsOneRowPerSimAndStaysClickable() {
        var clicks = 0
        compose.setContent { SignalAtlasTheme { ConnectedInfoBar(dual, onClick = { clicks++ }) } }
        compose.onNodeWithText("Telstra").assertIsDisplayed()
        compose.onNodeWithText("Optus").assertIsDisplayed()
        compose.onNodeWithText("5G").assertIsDisplayed()
        compose.onNodeWithText("n78").assertIsDisplayed()
        compose.onNodeWithText("−97").assertIsDisplayed()
        compose.onNodeWithTag("connected-bar").performClick()
        assertThat(clicks).isEqualTo(1)
    }

    @Test fun detailsShowEachSimTheMeasuredVersusPredictedAndCalibrationStatus() {
        val enabled = MutableStateFlow(true)
        val hooks = CalibrationHooks(enabled, { ident -> if (ident == "41428|0|1234|1200|300") 23 else 0 }, { enabled.value = it })
        var opened: Int? = null
        compose.setContent { SignalAtlasTheme { CellularDetailsContent(dual, hooks, onOpenTower = { opened = it }, onClose = {}) } }
        compose.onNodeWithText("Cellular details").assertIsDisplayed()
        compose.onNodeWithTag("cellular-sim-0").assertIsDisplayed()
        compose.onNodeWithTag("cellular-sim-1").assertExists()
        compose.onNodeWithText("Data SIM").assertIsDisplayed()
        compose.onNodeWithTag("measured-vs-predicted-0").performScrollTo().assertTextEquals("Measured −97 dBm vs −92 dBm predicted here")
        compose.waitForIdle()
        compose.onNodeWithTag("calibration-status").performScrollTo().assertTextEquals("Improving predictions: 23 samples on this tower")
        compose.onNodeWithText("Open tower").performScrollTo().performClick()
        assertThat(opened).isEqualTo(41428)
        // Turning the switch off says the readings are unused.
        compose.onNodeWithTag("calibration-switch").performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(enabled.value).isFalse()
        compose.onNodeWithTag("calibration-status").performScrollTo().assertTextEquals("Your readings are not being used to improve predictions.")
    }

    @Test fun aSimWithoutCellInfoExplainsItself() {
        val searching = ConnectedUi(ConnectedStatus.SEARCHING, carrier = "Optus", network = Network.OPTUS, subId = 2)
        compose.setContent { SignalAtlasTheme { CellularDetailsContent(searching, CalibrationHooks.None, {}, {}) } }
        compose.onNodeWithText("Looking for a signal…").assertIsDisplayed()
    }
}
