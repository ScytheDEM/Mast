package au.signalatlas.core.radio

import android.telephony.TelephonyDisplayInfo
import au.signalatlas.core.model.Tech
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CellMapperTest {
    @Test fun `LTE serving cell maps every field`() {
        val c = CellMapper.map(
            FakeCells.lte(true, earfcn = 1300, pci = 100, eci = 1_234_567, tac = 4000, ta = 5, bands = intArrayOf(3)),
        )!!
        assertThat(c.tech).isEqualTo(Tech.LTE)
        assertThat(c.channel).isEqualTo(1300)
        assertThat(c.freqMhz).isEqualTo(1815.0)
        assertThat(c.band).isEqualTo("B3")
        assertThat(c.pci).isEqualTo(100)
        assertThat(c.cellId).isEqualTo(1_234_567L)
        assertThat(c.tac).isEqualTo(4000)
        assertThat(c.rsrp).isEqualTo(-95)
        assertThat(c.rsrq).isEqualTo(-10)
        assertThat(c.sinr).isEqualTo(12)
        assertThat(c.taMeters).isWithin(1e-9).of(390.6)
        assertThat(c.registered).isTrue()
    }

    @Test fun `LTE band falls back to the channel table when the API reports none`() {
        val c = CellMapper.map(FakeCells.lte(false, earfcn = 9410))!!
        assertThat(c.band).isEqualTo("B28")
        assertThat(c.freqMhz).isEqualTo(778.0)
    }

    @Test fun `LTE vendor sentinels and junk become null`() {
        val c = CellMapper.map(
            FakeCells.lte(
                true, earfcn = FakeCells.NA, pci = FakeCells.NA, eci = FakeCells.NA, tac = FakeCells.NA,
                rsrp = FakeCells.NA, rsrq = FakeCells.NA, rssnr = FakeCells.NA, ta = 1500,
            ),
        )!!
        assertThat(c.channel).isNull()
        assertThat(c.freqMhz).isNull()
        assertThat(c.band).isNull()
        assertThat(c.pci).isNull()
        assertThat(c.cellId).isNull()
        assertThat(c.tac).isNull()
        assertThat(c.rsrp).isNull()
        assertThat(c.rsrq).isNull()
        assertThat(c.sinr).isNull()
        assertThat(c.taMeters).isNull()
    }

    @Test fun `NR cell with unavailable PCI keeps channel and signal`() {
        val c = CellMapper.map(FakeCells.nr(false, nrarfcn = 636666))!!
        assertThat(c.tech).isEqualTo(Tech.NR)
        assertThat(c.pci).isNull()
        assertThat(c.cellId).isNull()
        assertThat(c.tac).isNull()
        assertThat(c.channel).isEqualTo(636666)
        assertThat(c.freqMhz).isEqualTo(3549.99)
        assertThat(c.band).isEqualTo("n78")
        assertThat(c.rsrp).isEqualTo(-100)
        assertThat(c.sinr).isEqualTo(8)
        assertThat(c.taMeters).isNull()
    }

    @Test fun `NR API band wins and real identifiers pass through`() {
        val c = CellMapper.map(FakeCells.nr(true, nrarfcn = 155400, pci = 321, nci = 5_000_000_000L, tac = 77, bands = intArrayOf(28)))!!
        assertThat(c.band).isEqualTo("n28")
        assertThat(c.pci).isEqualTo(321)
        assertThat(c.cellId).isEqualTo(5_000_000_000L)
        assertThat(c.tac).isEqualTo(77)
    }

    @Test fun `NSA display needs an LTE serving cell and an NR override`() {
        val lte = CellMapper.map(FakeCells.lte(true))
        val nr = CellMapper.map(FakeCells.nr(true))
        assertThat(CellMapper.displayFiveG(lte, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA)).isTrue()
        assertThat(CellMapper.displayFiveG(lte, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED)).isTrue()
        assertThat(CellMapper.displayFiveG(lte, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE)).isFalse()
        assertThat(CellMapper.displayFiveG(lte, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA)).isFalse()
        assertThat(CellMapper.displayFiveG(null, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA)).isFalse()
        assertThat(CellMapper.displayFiveG(nr, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA)).isFalse()
    }

    @Test fun `split puts the registered LTE cell first with NR as secondaries even when unregistered`() {
        val cells = listOf(
            FakeCells.lte(false, pci = 11), FakeCells.nr(false), FakeCells.lte(true, pci = 10), FakeCells.lte(false, pci = 12),
        ).mapNotNull { CellMapper.map(it) }
        val s = CellMapper.split(cells)
        assertThat(s.serving!!.pci).isEqualTo(10)
        assertThat(s.neighbours.map { it.pci }).containsExactly(11, 12).inOrder()
        assertThat(s.nrSecondaries).hasSize(1)
    }

    @Test fun `split with an empty list has no serving cell`() {
        val s = CellMapper.split(emptyList())
        assertThat(s.serving).isNull()
        assertThat(s.neighbours).isEmpty()
        assertThat(s.nrSecondaries).isEmpty()
    }
}
