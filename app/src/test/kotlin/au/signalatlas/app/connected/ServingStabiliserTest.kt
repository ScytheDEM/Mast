package au.signalatlas.app.connected

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServingStabiliserTest {
    private fun ui(bands: List<String>, fiveG: Boolean = false, rsrp: Int? = -95, sub: Int? = 1, status: ConnectedStatus = ConnectedStatus.CONNECTED) =
        ConnectedUi(status, network = null, fiveG = fiveG, bands = bands.joinToString(" + "), bandList = bands, rsrpDbm = rsrp, subId = sub)

    @Test fun theFirstReadingIsShownAsIs() {
        val s = ServingStabiliser()
        val out = s.update(ui(listOf("B28", "n78"), fiveG = true, rsrp = -90), 0)
        assertThat(out.bandList).containsExactly("B28", "n78").inOrder()
        assertThat(out.bands).isEqualTo("B28 + n78")
        assertThat(out.fiveG).isTrue()
        assertThat(out.rsrpDbm).isEqualTo(-90)
    }

    @Test fun aBandFlickerShorterThanThreeReadingsAndFourSecondsIsIgnored() {
        val s = ServingStabiliser()
        s.update(ui(listOf("B28", "n78"), fiveG = true), 0)
        // The NSA leg drops for two readings, then comes back.
        assertThat(s.update(ui(listOf("B28")), 1_000).bandList).containsExactly("B28", "n78")
        assertThat(s.update(ui(listOf("B28")), 2_000).fiveG).isTrue()
        assertThat(s.update(ui(listOf("B28", "n78"), fiveG = true), 3_000).bandList).containsExactly("B28", "n78")
        // Alternating values never settle either.
        for (t in 4..20) {
            val out = s.update(if (t % 2 == 0) ui(listOf("B3")) else ui(listOf("B1")), t * 1_000L)
            assertThat(out.bandList).containsExactly("B28", "n78")
        }
    }

    @Test fun aChangeIsShownOnTheThirdConsecutiveReading() {
        val s = ServingStabiliser()
        s.update(ui(listOf("B28")), 0)
        assertThat(s.update(ui(listOf("B3")), 500).bandList).containsExactly("B28")
        assertThat(s.update(ui(listOf("B3")), 1_000).bandList).containsExactly("B28")
        assertThat(s.update(ui(listOf("B3")), 1_500).bandList).containsExactly("B3")
    }

    @Test fun aChangeHeldForFourSecondsIsShownEvenWithFewReadings() {
        val s = ServingStabiliser()
        s.update(ui(listOf("B28")), 0)
        assertThat(s.update(ui(listOf("B3"), fiveG = false), 1_000).bandList).containsExactly("B28")
        assertThat(s.update(ui(listOf("B3")), 4_999).bandList).containsExactly("B28")
        // Second reading, but 4 s after the first one of the new value.
        val s2 = ServingStabiliser()
        s2.update(ui(listOf("B28")), 0)
        s2.update(ui(listOf("B3")), 1_000)
        assertThat(s2.update(ui(listOf("B3")), 5_000).bandList).containsExactly("B3")
    }

    @Test fun techFollowsTheSameHysteresis() {
        val s = ServingStabiliser()
        s.update(ui(listOf("B28"), fiveG = false), 0)
        assertThat(s.update(ui(listOf("B28"), fiveG = true), 1_000).fiveG).isFalse()
        assertThat(s.update(ui(listOf("B28"), fiveG = true), 2_000).fiveG).isFalse()
        assertThat(s.update(ui(listOf("B28"), fiveG = true), 3_000).fiveG).isTrue()
    }

    @Test fun rsrpIsSmoothedAndShownAtMostOncePerSecond() {
        val s = ServingStabiliser()
        assertThat(s.update(ui(listOf("B28"), rsrp = -100), 0).rsrpDbm).isEqualTo(-100)
        // 200 ms later: the EMA moves (-100 + 0.3 * 10 = -97) but the shown value waits.
        assertThat(s.update(ui(listOf("B28"), rsrp = -90), 200).rsrpDbm).isEqualTo(-100)
        // 1 s after the last shown: EMA = -97 + 0.3 * (-90 + 97) = -94.9 -> -95.
        assertThat(s.update(ui(listOf("B28"), rsrp = -90), 1_000).rsrpDbm).isEqualTo(-95)
        // A one-off spike barely moves it: -94.9 + 0.3 * (-60 + 94.9) = -84.43.
        assertThat(s.update(ui(listOf("B28"), rsrp = -60), 2_000).rsrpDbm).isEqualTo(-84)
        assertThat(s.update(ui(listOf("B28"), rsrp = null), 2_100).rsrpDbm).isNull()
        assertThat(s.update(ui(listOf("B28"), rsrp = -110), 2_200).rsrpDbm).isEqualTo(-110) // restarts from the reading
    }

    @Test fun losingServiceResetsSoTheNextCellIsShownAtOnce() {
        val s = ServingStabiliser()
        s.update(ui(listOf("B28")), 0)
        val searching = s.update(ui(emptyList(), status = ConnectedStatus.SEARCHING, rsrp = null), 1_000)
        assertThat(searching.status).isEqualTo(ConnectedStatus.SEARCHING)
        assertThat(s.update(ui(listOf("B3")), 2_000).bandList).containsExactly("B3")
    }

    @Test fun eachSimIsStabilisedOnItsOwn() {
        val s = ServingStabiliser()
        val first = ui(listOf("B28"), sub = 1).copy(otherSims = listOf(ui(listOf("B3"), sub = 2)))
        s.update(first, 0)
        val flicker = ui(listOf("B28"), sub = 1).copy(otherSims = listOf(ui(listOf("B1"), sub = 2)))
        val out = s.update(flicker, 1_000)
        assertThat(out.bandList).containsExactly("B28")
        assertThat(out.otherSims.single().bandList).containsExactly("B3")
        s.update(flicker, 2_000)
        assertThat(s.update(flicker, 3_000).otherSims.single().bandList).containsExactly("B1")
    }
}
