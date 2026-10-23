package au.signalatlas.app

import android.os.PowerManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RenderThreadsTest {
    @Test fun coolDeviceUsesCoresMinusTwoCappedAtFour() {
        assertThat(RenderThreads.budget(34, 0.3f, 0, 8)).isEqualTo(4)
        assertThat(RenderThreads.budget(34, 0.3f, 0, 5)).isEqualTo(3)
        assertThat(RenderThreads.budget(34, null, 0, 12)).isEqualTo(4)
    }

    @Test fun atLeastOneThread() {
        assertThat(RenderThreads.budget(34, 0.1f, 0, 2)).isEqualTo(1)
        assertThat(RenderThreads.budget(30, 0.1f, 0, 1)).isEqualTo(1)
    }

    @Test fun api30HeadroomAtOrAboveLimitIsOneThread() {
        assertThat(RenderThreads.budget(30, 0.85f, 0, 8)).isEqualTo(1)
        assertThat(RenderThreads.budget(34, 1.2f, 0, 8)).isEqualTo(1)
        assertThat(RenderThreads.budget(34, 0.84f, 0, 8)).isEqualTo(4)
    }

    @Test fun nanHeadroomIsNotHot() {
        assertThat(RenderThreads.budget(34, Float.NaN, 0, 8)).isEqualTo(4)
    }

    @Test fun api30IgnoresStatus() {
        assertThat(RenderThreads.budget(30, 0.2f, PowerManager.THERMAL_STATUS_CRITICAL, 8)).isEqualTo(4)
    }

    @Test fun api29UsesStatusSevere() {
        assertThat(RenderThreads.budget(29, null, PowerManager.THERMAL_STATUS_SEVERE, 8)).isEqualTo(1)
        assertThat(RenderThreads.budget(29, null, PowerManager.THERMAL_STATUS_EMERGENCY, 8)).isEqualTo(1)
        assertThat(RenderThreads.budget(29, null, PowerManager.THERMAL_STATUS_MODERATE, 8)).isEqualTo(4)
        assertThat(RenderThreads.budget(29, 0.99f, PowerManager.THERMAL_STATUS_NONE, 8)).isEqualTo(4)
    }
}
