package au.signalatlas.core.map

import au.signalatlas.core.map.HighRefreshRate.ModeSpec
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HighRefreshRateTest {
    @Test
    fun picksTheFastestModeAtTheCurrentResolution() {
        val current = ModeSpec(1, 1080, 2400, 60f)
        val modes = listOf(
            current,
            ModeSpec(2, 1080, 2400, 120f),
            ModeSpec(3, 1440, 3200, 144f), // faster, but a different resolution
            ModeSpec(4, 1080, 2400, 90f),
        )
        assertThat(HighRefreshRate.pickMode(current, modes).id).isEqualTo(2)
    }

    @Test
    fun keepsTheCurrentModeWhenNothingIsFaster() {
        val current = ModeSpec(7, 1080, 2400, 60f)
        assertThat(HighRefreshRate.pickMode(current, listOf(current, ModeSpec(8, 720, 1600, 90f)))).isEqualTo(current)
        assertThat(HighRefreshRate.pickMode(current, emptyList())).isEqualTo(current)
    }
}
