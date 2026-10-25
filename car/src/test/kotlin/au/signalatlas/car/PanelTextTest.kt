package au.signalatlas.car

import au.signalatlas.core.radio.CarrierAggregation
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PanelTextTest {
    @Test fun headlineAndTech() {
        val s = sim()
        assertThat(PanelText.header(s)).isEqualTo("SIM 1 · Telstra")
        assertThat(PanelText.tech(s)).isEqualTo("5G+ · n78")
        assertThat(PanelText.ca(s)).isEqualTo("CA ×3 · 60 MHz")
        assertThat(PanelText.signal(s)).isEqualTo("-92 dBm")
        assertThat(PanelText.tower(s)).isEqualTo("1.2 km NE")
        assertThat(PanelText.detail(s)).isEqualTo("CA ×3 · 60 MHz  ·  -92 dBm  ·  1.2 km NE")
    }

    @Test fun singleCarrierShowsNoCa() {
        assertThat(PanelText.ca(sim(ca = CarrierAggregation(listOf(20000))))).isNull()
        assertThat(PanelText.ca(sim(ca = null))).isNull()
    }

    @Test fun distances() {
        assertThat(PanelText.distance(84.0)).isEqualTo("80 m")
        assertThat(PanelText.distance(960.0)).isEqualTo("960 m")
        assertThat(PanelText.distance(1800.0)).isEqualTo("1.8 km")
        assertThat(PanelText.distance(12_400.0)).isEqualTo("12 km")
    }

    @Test fun compassWrapsAround() {
        assertThat(PanelText.compass(0.0)).isEqualTo("N")
        assertThat(PanelText.compass(359.0)).isEqualTo("N")
        assertThat(PanelText.compass(90.0)).isEqualTo("E")
        assertThat(PanelText.compass(225.0)).isEqualTo("SW")
        assertThat(PanelText.compass(-45.0)).isEqualTo("NW")
    }

    @Test fun bars() {
        assertThat(PanelText.bars(null)).isEqualTo(0)
        assertThat(PanelText.bars(-80)).isEqualTo(4)
        assertThat(PanelText.bars(-92)).isEqualTo(3)
        assertThat(PanelText.bars(-100)).isEqualTo(2)
        assertThat(PanelText.bars(-110)).isEqualTo(1)
        assertThat(PanelText.bars(-120)).isEqualTo(0)
    }

    @Test fun missingTowerAndSignalFallBackToNote() {
        val s = sim(rsrp = null, dist = null, bearing = null, ca = null).copy(note = "Roaming")
        assertThat(PanelText.tower(s)).isNull()
        assertThat(PanelText.detail(s)).isEqualTo("Roaming")
        assertThat(PanelText.detail(s.copy(note = null))).isEqualTo("Searching…")
    }

    @Test fun measuredVersusPredicted() {
        assertThat(PanelText.predicted(sim())).isEqualTo("Measured -92 · predicted -96 dBm (+4)")
        assertThat(PanelText.predicted(sim(rsrp = null))).isNull()
    }
}
