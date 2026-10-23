package au.signalatlas.core.radio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChannelTablesTest {
    private fun lte(n: Int) = ChannelTables.lte(n)!!

    @Test fun `each LTE band first and last channel`() {
        // band, first channel, first MHz, last channel, last MHz
        val cases = listOf(
            listOf("B1", 0, 2110.0, 599, 2169.9),
            listOf("B3", 1200, 1805.0, 1949, 1879.9),
            listOf("B5", 2400, 869.0, 2649, 893.9),
            listOf("B7", 2750, 2620.0, 3449, 2689.9),
            listOf("B8", 3450, 925.0, 3799, 959.9),
            listOf("B26", 8690, 859.0, 9039, 893.9),
            listOf("B28", 9210, 758.0, 9659, 802.9),
            listOf("B40", 38650, 2300.0, 39649, 2399.9),
            listOf("B42", 41590, 3400.0, 43589, 3599.9),
        )
        for (c in cases) {
            val first = lte(c[1] as Int)
            val last = lte(c[3] as Int)
            assertThat(first.band).isEqualTo(c[0])
            assertThat(last.band).isEqualTo(c[0])
            assertThat(first.freqMhz).isWithin(1e-9).of(c[2] as Double)
            assertThat(last.freqMhz).isWithin(1e-9).of(c[4] as Double)
        }
        assertThat(ChannelTables.lteBands.map { it.name })
            .containsExactly("B1", "B3", "B5", "B7", "B8", "B26", "B28", "B40", "B42")
    }

    @Test fun `well known LTE channels`() {
        assertThat(lte(100).freqMhz).isWithin(1e-9).of(2120.0)
        assertThat(lte(1300).freqMhz).isWithin(1e-9).of(1815.0)
        assertThat(lte(3100).freqMhz).isWithin(1e-9).of(2655.0)
        assertThat(lte(9410).freqMhz).isWithin(1e-9).of(778.0)
    }

    @Test fun `LTE channels outside the table or range are unknown`() {
        assertThat(ChannelTables.lte(600)).isNull()
        assertThat(ChannelTables.lte(-1)).isNull()
        assertThat(ChannelTables.lte(3800)).isNull()
        assertThat(ChannelTables.lte(Int.MAX_VALUE)).isNull()
    }

    @Test fun `NR range 1 uses 5 kHz steps`() {
        assertThat(ChannelTables.nrFreqMhz(0)).isEqualTo(0.0)
        assertThat(ChannelTables.nrFreqMhz(155400)).isEqualTo(777.0)
        assertThat(ChannelTables.nrFreqMhz(599999)).isEqualTo(2999.995)
    }

    @Test fun `NR range 2 uses 15 kHz steps from 3000 MHz`() {
        assertThat(ChannelTables.nrFreqMhz(600000)).isEqualTo(3000.0)
        assertThat(ChannelTables.nrFreqMhz(636666)).isEqualTo(3549.99)
        assertThat(ChannelTables.nrFreqMhz(646666)).isEqualTo(3699.99)
        assertThat(ChannelTables.nrFreqMhz(2016666)).isEqualTo(24249.99)
    }

    @Test fun `NR range 3 uses 60 kHz steps from 24250_08 MHz`() {
        assertThat(ChannelTables.nrFreqMhz(2016667)).isEqualTo(24250.08)
        assertThat(ChannelTables.nrFreqMhz(2054166)).isEqualTo(26500.02) // n258 mid-band
        assertThat(ChannelTables.nrFreqMhz(3279165)).isEqualTo(99999.96)
    }

    @Test fun `NR channels outside the raster are unknown`() {
        assertThat(ChannelTables.nrFreqMhz(-1)).isNull()
        assertThat(ChannelTables.nrFreqMhz(3279166)).isNull()
        assertThat(ChannelTables.nrFreqMhz(Int.MAX_VALUE)).isNull()
    }
}
