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

    /** The antenna-pattern (HRP) job runs at most this often (shared-context "HRP job policy"). */
    val HRP_PERIOD: Duration = Duration.ofDays(30)

    /** Free space the HRP job needs before it starts: the 1.56 GB zip plus the DB being built, with margin. */
    const val HRP_MIN_FREE_BYTES: Long = 3L * 1024 * 1024 * 1024

    /** The monthly pattern job: Wi-Fi (unmetered), charging, enough storage. */
    fun hrpConstraints(): Constraints = periodicConstraints()

    /** "Download patterns now": Wi-Fi (unmetered) only; the worker itself checks [HRP_MIN_FREE_BYTES]. */
    fun hrpManualConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .build()

    /** True when there is no data or it was imported more than [STALE_AFTER] before [now]. */
    fun isStale(importedAt: Instant?, now: Instant): Boolean =
        importedAt == null || Duration.between(importedAt, now) > STALE_AFTER
}
