package au.signalatlas.core.update

import androidx.work.NetworkType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class UpdatePolicyTest {
    private val now = Instant.parse("2026-10-22T10:00:00Z")

    @Test fun periodicNeedsWifiChargingAndStorage() {
        val c = UpdatePolicy.periodicConstraints()
        assertThat(c.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(c.requiresCharging()).isTrue()
        assertThat(c.requiresStorageNotLow()).isTrue()
    }

    @Test fun staleNeedsWifiAndStorageButNotCharging() {
        val c = UpdatePolicy.staleConstraints()
        assertThat(c.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(c.requiresCharging()).isFalse()
        assertThat(c.requiresStorageNotLow()).isTrue()
    }

    @Test fun noDataIsStale() {
        assertThat(UpdatePolicy.isStale(null, now)).isTrue()
    }

    @Test fun staleAfterFourteenDays() {
        assertThat(UpdatePolicy.isStale(now - Duration.ofDays(13), now)).isFalse()
        assertThat(UpdatePolicy.isStale(now - Duration.ofDays(14), now)).isFalse()
        assertThat(UpdatePolicy.isStale(now - Duration.ofDays(15), now)).isTrue()
    }
}
