package au.signalatlas.car

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SignalPaneModelTest {
    @Test fun dualSimGivesTwoSimRowsAndATripRow() {
        val rows = SignalPaneModel.rows(
            listOf(sim(1), sim(2, carrier = "Optus", label = au.signalatlas.core.radio.DisplayLabel.LTE_A, band = "B3")),
            CarTripState(true, "Leaving Telstra coverage in 1.8 km"),
        )
        assertThat(rows).hasSize(3)
        assertThat(rows[0].title).isEqualTo("SIM 1 · Telstra   5G+ · n78")
        assertThat(rows[0].lines).containsExactly("CA ×3 · 60 MHz   ▮▮▮▯  -92 dBm", "Tower 1.2 km NE   Measured -92 · predicted -96 dBm (+4)").inOrder()
        assertThat(rows[1].title).isEqualTo("SIM 2 · Optus   LTE-A · B3")
        assertThat(rows[2].title).isEqualTo("Trip running")
        assertThat(rows[2].lines).containsExactly("Leaving Telstra coverage in 1.8 km", "4 neighbour cells").inOrder()
    }

    @Test fun noSimsStillShowsRows() {
        val rows = SignalPaneModel.rows(emptyList(), CarTripState(false))
        assertThat(rows.map { it.title }).containsExactly("No SIM signal", "Trip stopped").inOrder()
    }

    @Test fun neverMoreThanFourRows() {
        val rows = SignalPaneModel.rows(List(6) { sim(it + 1) }, CarTripState(false))
        assertThat(rows).hasSize(SignalPaneModel.MAX_ROWS)
    }
}
