package au.signalatlas.core.coverage

import au.signalatlas.engine.TerrainTable
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TerrainKeysTest {
    @Test fun frequencyIsTheBandCentreInKhz() {
        assertThat(TerrainKeys.frequencyKhz(778f)).isEqualTo(780_500)
        assertThat(TerrainKeys.frequencyKhz(791f)).isEqualTo(780_500)
        assertThat(TerrainKeys.frequencyKhz(3410f)).isEqualTo(3_550_000)
        assertThat(TerrainKeys.frequencyKhz(3750f)).isEqualTo(3_550_000)
    }

    @Test fun anUnknownBandRoundsToTenMhzAsBefore() {
        assertThat(TerrainKeys.frequencyKhz(1005f)).isEqualTo(1_010_000)
        assertThat(TerrainKeys.frequencyKhz(1004.9f)).isEqualTo(1_000_000)
        assertThat(TerrainKeys.frequencyKhz(1005f)).isEqualTo(TerrainTable.frequencyKeyMhz(1005f) * 1000)
    }

    @Test fun heightIsRoundedToTwoMetresAfterClampingTo3m() {
        assertThat(TerrainKeys.heightM(27f)).isEqualTo(28)
        assertThat(TerrainKeys.heightM(26.9f)).isEqualTo(26)
        assertThat(TerrainKeys.heightM(5f)).isEqualTo(6)
        assertThat(TerrainKeys.heightM(4.9f)).isEqualTo(4)
        assertThat(TerrainKeys.heightM(1f)).isEqualTo(4)
        assertThat(TerrainKeys.heightM(Float.NaN)).isEqualTo(4)
    }
}
