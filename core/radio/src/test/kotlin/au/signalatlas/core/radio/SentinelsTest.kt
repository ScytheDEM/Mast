package au.signalatlas.core.radio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SentinelsTest {
    @Test fun `unavailable values become null`() {
        val na = Int.MAX_VALUE
        assertThat(Sentinels.rsrp(na)).isNull()
        assertThat(Sentinels.rsrq(na)).isNull()
        assertThat(Sentinels.sinr(na)).isNull()
        assertThat(Sentinels.pciLte(na)).isNull()
        assertThat(Sentinels.pciNr(na)).isNull()
        assertThat(Sentinels.earfcn(na)).isNull()
        assertThat(Sentinels.nrarfcn(na)).isNull()
        assertThat(Sentinels.tacLte(na)).isNull()
        assertThat(Sentinels.tacNr(na)).isNull()
        assertThat(Sentinels.eci(na)).isNull()
        assertThat(Sentinels.nci(Long.MAX_VALUE)).isNull()
        assertThat(Sentinels.lteTaMeters(na)).isNull()
    }

    @Test fun `range edges are inclusive and one beyond is rejected`() {
        assertThat(Sentinels.rsrp(-140)).isEqualTo(-140)
        assertThat(Sentinels.rsrp(-44)).isEqualTo(-44)
        assertThat(Sentinels.rsrp(-141)).isNull()
        assertThat(Sentinels.rsrp(-43)).isNull()
        assertThat(Sentinels.rsrq(-34)).isEqualTo(-34)
        assertThat(Sentinels.rsrq(3)).isEqualTo(3)
        assertThat(Sentinels.rsrq(-35)).isNull()
        assertThat(Sentinels.rsrq(4)).isNull()
        assertThat(Sentinels.sinr(-23)).isEqualTo(-23)
        assertThat(Sentinels.sinr(40)).isEqualTo(40)
        assertThat(Sentinels.sinr(-24)).isNull()
        assertThat(Sentinels.sinr(41)).isNull()
        assertThat(Sentinels.pciLte(503)).isEqualTo(503)
        assertThat(Sentinels.pciLte(504)).isNull()
        assertThat(Sentinels.pciLte(-1)).isNull()
        assertThat(Sentinels.pciNr(1007)).isEqualTo(1007)
        assertThat(Sentinels.pciNr(1008)).isNull()
        assertThat(Sentinels.nci(68719476735L)).isEqualTo(68719476735L)
        assertThat(Sentinels.nci(68719476736L)).isNull()
        assertThat(Sentinels.eci(268435455)).isEqualTo(268435455L)
    }

    @Test fun `timing advance converts at 78_12 m per step within 0 to 1282`() {
        assertThat(Sentinels.lteTaMeters(0)).isEqualTo(0.0)
        assertThat(Sentinels.lteTaMeters(10)).isWithin(1e-9).of(781.2)
        assertThat(Sentinels.lteTaMeters(1282)).isWithin(1e-6).of(1282 * 78.12)
        assertThat(Sentinels.lteTaMeters(1283)).isNull()
        assertThat(Sentinels.lteTaMeters(-1)).isNull()
    }
}
