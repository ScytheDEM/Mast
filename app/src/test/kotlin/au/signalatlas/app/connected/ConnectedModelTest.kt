package au.signalatlas.app.connected

import au.signalatlas.core.calibration.Candidate
import au.signalatlas.core.calibration.Confidence
import au.signalatlas.core.calibration.Fix
import au.signalatlas.core.calibration.MatchResult
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import au.signalatlas.core.radio.ObservedCell
import au.signalatlas.core.radio.Permission
import au.signalatlas.core.radio.RadioState
import au.signalatlas.core.radio.SimInfo
import au.signalatlas.engine.AreaClass
import com.google.common.truth.Truth.assertThat
import org.junit.Test

internal val FIX = Fix(-35.28, 149.13, 8.0, 1_000L)

internal fun lte(rsrp: Int? = -94, sinr: Int? = 15, band: String? = "B28", registered: Boolean = true) =
    ObservedCell(Tech.LTE, 9410, 758.0, band, 101, 123L, 1, rsrp, -10, sinr, null, registered)

internal fun nr(band: String = "n78", registered: Boolean = false) =
    ObservedCell(Tech.NR, 630000, 3500.0, band, 20, null, 1, -100, -11, 10, null, registered)

internal val TELSTRA = SimInfo(1, Network.TELSTRA, "Telstra", "50501", false)

internal fun radio(
    serving: ObservedCell? = lte(), sim: SimInfo? = TELSTRA, permission: Permission = Permission.GRANTED,
    airplane: Boolean = false, fiveG: Boolean = false, nrSecondaries: List<ObservedCell> = emptyList(),
) = RadioState(permission, sim, serving, emptyList(), nrSecondaries, fiveG, airplane, 0L)

/** A sector ~1.3 km north-east of [FIX], on site 41428. */
internal fun match(conf: Confidence = Confidence.LIKELY, bandwidthMhz: Double = 20.0) = MatchResult(
    Candidate(7, 5007L, "41428|0|1234|1200|300", Network.TELSTRA, -35.2715, 149.1417, 758.0, -96.0, 30.0, bandwidthMhz),
    conf, 2.0, 1300.0, AreaClass.SUBURBAN, 3,
    listOf(Candidate(8, 5008L, "9|0|1|0|300", Network.TELSTRA, -35.30, 149.10, 758.0, -100.0, 30.0)),
)

class ConnectedModelTest {
    @Test fun missingPermissionsExplainThemselves() {
        assertThat(connectedUi(radio(permission = Permission.MISSING_PHONE), null, FIX).status).isEqualTo(ConnectedStatus.NEEDS_PHONE)
        assertThat(connectedUi(radio(permission = Permission.MISSING_LOCATION), null, FIX).status).isEqualTo(ConnectedStatus.NEEDS_LOCATION)
    }

    @Test fun airplaneNoSimRoamingAndUntrackedNetworksNeverCrashAndExplain() {
        assertThat(connectedUi(radio(airplane = true), null, FIX).status).isEqualTo(ConnectedStatus.AIRPLANE)
        assertThat(connectedUi(radio(sim = null), null, FIX).status).isEqualTo(ConnectedStatus.NO_SIM)
        assertThat(connectedUi(radio(sim = TELSTRA.copy(roaming = true)), match(), FIX).status).isEqualTo(ConnectedStatus.ROAMING)
        assertThat(connectedUi(radio(sim = TELSTRA.copy(network = null, carrierName = "Verizon")), null, FIX).status)
            .isEqualTo(ConnectedStatus.UNTRACKED)
        assertThat(connectedUi(radio(serving = null), null, FIX).status).isEqualTo(ConnectedStatus.SEARCHING)
    }

    @Test fun aLikelyMatchGivesTowerHighlightAndSpeed() {
        val ui = connectedUi(radio(nrSecondaries = listOf(nr()), fiveG = true), match(), FIX)
        assertThat(ui.status).isEqualTo(ConnectedStatus.CONNECTED)
        assertThat(ui.fiveG).isTrue()
        assertThat(ui.bands).isEqualTo("B28 + n78")
        assertThat(ui.rsrpDbm).isEqualTo(-94)
        assertThat(ui.tower!!.siteId).isEqualTo(41428)
        assertThat(ui.tower!!.compass).isEqualTo("NE")
        assertThat(ui.tower!!.distanceM).isEqualTo(1300.0)
        assertThat(ui.confidence).isEqualTo(Confidence.LIKELY)
        assertThat(ui.speedMbps).isEqualTo(speedMbps(15, Tech.LTE, 20.0))
        assertThat(ui.highlight!!.serving).isEqualTo(-35.2715 to 149.1417)
        assertThat(ui.highlight!!.user).isEqualTo(-35.28 to 149.13)
        assertThat(ui.highlight!!.neighbours).containsExactly(-35.30 to 149.10)
    }

    @Test fun anUncertainMatchShowsDistanceButNoHighlightOrSpeed() {
        val ui = connectedUi(radio(), match(Confidence.UNCERTAIN), FIX)
        assertThat(ui.confidence).isEqualTo(Confidence.UNCERTAIN)
        assertThat(ui.tower).isNotNull()
        assertThat(ui.highlight).isNull()
        assertThat(ui.speedMbps).isNull()
    }

    @Test fun standaloneNrServingCellShowsFiveG() {
        val ui = connectedUi(radio(serving = nr(registered = true)), null, FIX)
        assertThat(ui.fiveG).isTrue()
        assertThat(ui.bands).isEqualTo("n78")
        assertThat(connectedUi(radio(), null, FIX).fiveG).isFalse()
    }

    @Test fun noFixOrNoMatchStillShowsTheCell() {
        val ui = connectedUi(radio(), null, null)
        assertThat(ui.status).isEqualTo(ConnectedStatus.CONNECTED)
        assertThat(ui.tower).isNull()
        assertThat(ui.rsrpDbm).isEqualTo(-94)
    }

    @Test fun speedFollowsTheBriefsFormula() {
        // SINR 15 dB: 0.75 * log2(1 + 31.62) = 3.77 bit/s/Hz; x 20 MHz x 0.75 = 56.6.
        assertThat(speedMbps(15, Tech.LTE, 20.0)).isEqualTo(57)
        // Very high SINR hits the cap: LTE 5.5, NR 7.4 bit/s/Hz.
        assertThat(speedMbps(40, Tech.LTE, 20.0)).isEqualTo(83)  // 5.5 * 20 * 0.75 = 82.5
        assertThat(speedMbps(40, Tech.NR, 100.0)).isEqualTo(555)
        assertThat(speedMbps(null, Tech.LTE, 20.0)).isNull()
        assertThat(speedMbps(15, Tech.LTE, 0.0)).isNull()
    }

    @Test fun bearingAndCompass() {
        assertThat(bearingDeg(0.0, 0.0, 1.0, 0.0)).isWithin(1e-6).of(0.0)
        assertThat(bearingDeg(0.0, 0.0, 0.0, 1.0)).isWithin(1e-6).of(90.0)
        assertThat(bearingDeg(0.0, 0.0, -1.0, 0.0)).isWithin(1e-6).of(180.0)
        assertThat(bearingDeg(0.0, 0.0, 0.0, -1.0)).isWithin(1e-6).of(270.0)
        assertThat(TowerRef(1, 1.0, 359.0).compass).isEqualTo("N")
        assertThat(TowerRef(1, 1.0, 225.0).compass).isEqualTo("SW")
    }
}
