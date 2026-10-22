package au.signalatlas.core.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import au.signalatlas.core.data.TowerStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import java.time.Clock
import java.time.Duration

sealed interface UpdateStatus {
    data object Unknown : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val stamp: String) : UpdateStatus
    data class Available(val info: RemoteInfo) : UpdateStatus
    data class Running(val phase: String, val fraction: Float) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
    data object Offline : UpdateStatus
}

/**
 * Ties the update check and the scheduled/manual [TowerUpdateWorker] runs into one [status].
 *
 * [status] is shared in [scope] for as long as that scope lives (the app's scope in production),
 * so every collector reads the same upstream instead of starting its own WorkManager queries.
 */
class UpdateManager(
    context: Context,
    private val remote: AcmaRemote,
    private val repo: TowerRepository,
    private val prefs: UpdatePrefs,
    scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        const val PERIODIC_WORK = "acma-periodic"
        const val STALE_WORK = "acma-stale"
        const val MANUAL_WORK = "acma-manual"
        /** [UpdateStatus.Running] phase of a manual update queued until its network is available. */
        const val PHASE_WAITING = "waiting"
        val CHECK_INTERVAL: Duration = Duration.ofMinutes(30)
    }

    private sealed interface Check {
        data object None : Check
        data object InProgress : Check
        data class Seen(val info: RemoteInfo) : Check
        data object Offline : Check
        data class Error(val message: String) : Check
    }

    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val check = MutableStateFlow<Check>(Check.None)

    val status: StateFlow<UpdateStatus> = combine(
        check,
        repo.store,
        workManager.getWorkInfosForUniqueWorkFlow(MANUAL_WORK),
        workManager.getWorkInfosForUniqueWorkFlow(PERIODIC_WORK),
        workManager.getWorkInfosForUniqueWorkFlow(STALE_WORK),
    ) { check, store, manual, periodic, stale -> derive(check, store, manual, periodic + stale) }
        .stateIn(scope, SharingStarted.Eagerly, UpdateStatus.Unknown)

    /**
     * Compares ACMA's current stamp with the live DB's. Hits the network at most once per
     * [CHECK_INTERVAL]; within it, re-uses the last [RemoteInfo] seen. Never throws (except
     * cancellation): network trouble becomes [UpdateStatus.Offline].
     */
    suspend fun checkOnAppOpen() {
        try {
            val now = clock.millis()
            val last = prefs.lastCheckAt()
            val seen = prefs.lastRemote()
            if (last != null && seen != null && now - last in 0 until CHECK_INTERVAL.toMillis()) {
                check.value = Check.Seen(seen)
                return
            }
            check.value = Check.InProgress
            val info = remote.info()
            prefs.recordCheck(now, info)
            check.value = Check.Seen(info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteFormatException) {
            check.value = Check.Error("ACMA returned unexpected data: ${e.message}")
        } catch (e: IOException) {
            check.value = Check.Offline
        } catch (e: Exception) {
            check.value = Check.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Weekly update on Wi-Fi + charging; plus a one-off Wi-Fi update when the live data is stale. */
    fun schedulePeriodic() {
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TowerUpdateWorker>(UpdatePolicy.PERIOD)
                .setConstraints(UpdatePolicy.periodicConstraints())
                .build(),
        )
        if (UpdatePolicy.isStale(repo.store.value?.meta?.importedAt, clock.instant())) {
            workManager.enqueueUniqueWork(
                STALE_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<TowerUpdateWorker>()
                    .setConstraints(UpdatePolicy.staleConstraints())
                    .build(),
            )
        }
    }

    /** "Update now": downloads even if the stamps match; on any network only if [allowMetered]. */
    fun updateNow(allowMetered: Boolean) {
        workManager.enqueueUniqueWork(
            MANUAL_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TowerUpdateWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                        .build(),
                )
                .setInputData(workDataOf(TowerUpdateWorker.KEY_FORCE to true))
                .build(),
        )
    }

    private fun derive(check: Check, store: TowerStore?, manual: List<WorkInfo>, automatic: List<WorkInfo>): UpdateStatus {
        (manual + automatic).firstOrNull { it.state == WorkInfo.State.RUNNING }?.let { running ->
            return UpdateStatus.Running(
                running.progress.getString(TowerUpdateWorker.KEY_PHASE) ?: TowerUpdateWorker.PHASE_STARTING,
                running.progress.getFloat(TowerUpdateWorker.KEY_FRACTION, 0f),
            )
        }
        if (manual.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) {
            return UpdateStatus.Running(PHASE_WAITING, 0f)
        }
        val liveStamp = store?.meta?.extractStamp
        val checked = when (check) {
            Check.None -> UpdateStatus.Unknown
            Check.InProgress -> UpdateStatus.Checking
            Check.Offline -> UpdateStatus.Offline
            is Check.Error -> UpdateStatus.Failed(check.message)
            // Stamps are fixed-width yyyyMMddHHmm, so string order is time order.
            is Check.Seen -> if (liveStamp != null && liveStamp >= check.info.extractStamp) {
                UpdateStatus.UpToDate(liveStamp)
            } else {
                UpdateStatus.Available(check.info)
            }
        }
        if (checked is UpdateStatus.UpToDate) return checked
        val failed = manual.firstOrNull { it.state == WorkInfo.State.FAILED }
        return if (failed != null) {
            UpdateStatus.Failed(failed.outputData.getString(TowerUpdateWorker.KEY_ERROR) ?: "Update failed")
        } else {
            checked
        }
    }
}
