package au.signalatlas.core.update

import androidx.work.Constraints
import androidx.work.NetworkType
import java.time.Duration
import java.time.Instant

/** When automatic updates may run (shared-context "Automatic updates"). */
object UpdatePolicy {
    val PERIOD: Duration = Duration.ofDays(7)
    val STALE_AFTER: Duration = Duration.ofDays(14)

    /** The weekly update: Wi-Fi (unmetered), charging, enough storage. */
    fun periodicConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresCharging(true)
        .setRequiresStorageNotLow(true)
        .build()

    /** Data older than [STALE_AFTER]: Wi-Fi (unmetered) and enough storage, charging not needed. */
    fun staleConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresStorageNotLow(true)
        .build()

    /** True when there is no data or it was imported more than [STALE_AFTER] before [now]. */
    fun isStale(importedAt: Instant?, now: Instant): Boolean =
        importedAt == null || Duration.between(importedAt, now) > STALE_AFTER
}
