package au.signalatlas.core.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import au.signalatlas.core.data.TowerStore
import au.signalatlas.core.data.TowerRepository
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
 * Ties the update check and the scheduled/manual update runs into one [status].
 *
 * Unique work (each run is [AcmaDownloadWorker] then [AcmaImportWorker], see [UpdateWork]):
 * - [MANUAL_WORK]: "Update now", the chain download -> import (REPLACE; [cancelManual] cancels both).
 * - [PERIODIC_WORK]: the weekly download; it enqueues its import as [AUTO_WORK] (KEEP).
 * - [AUTO_WORK]: every other automatic run: the stale-data chain download -> import, and the
 *   periodic run's import. KEEP everywhere, so at most one automatic chain is pending.
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
        const val AUTO_WORK = "acma-auto"
        const val MANUAL_WORK = "acma-manual"
        /** [UpdateStatus.Running] phase of a manual update queued until any network is available. */
        const val PHASE_WAITING = "waiting"
        /** [UpdateStatus.Running] phase of a manual update queued until an unmetered (Wi-Fi) network. */
        const val PHASE_WAITING_WIFI = "waiting_wifi"
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
        workManager.getWorkInfosForUniqueWorkFlow(AUTO_WORK),
    ) { check, store, manual, periodic, auto -> derive(check, store, manual, periodic + auto) }
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
            PeriodicWorkRequestBuilder<AcmaDownloadWorker>(UpdatePolicy.PERIOD)
                .setConstraints(UpdatePolicy.periodicConstraints())
                .setInputData(workDataOf(UpdateWork.KEY_THEN_IMPORT to true))
                .addTag(UpdateWork.TAG_DOWNLOAD)
                .build(),
        )
        if (UpdatePolicy.isStale(repo.store.value?.meta?.importedAt, clock.instant())) {
            workManager
                .beginUniqueWork(
                    AUTO_WORK,
                    ExistingWorkPolicy.KEEP,
                    UpdateWork.downloadRequest(UpdatePolicy.staleConstraints(), expedited = false),
                )
                .then(UpdateWork.importRequest(expedited = false))
                .enqueue()
        }
    }

    /**
     * "Update now": downloads even if the stamps match; on any network only if [allowMetered].
     * Remembers the live stamp now, so a run that finds the DB updated meanwhile does not redo it.
     */
    fun updateNow(allowMetered: Boolean) {
        val input = workDataOf(
            UpdateWork.KEY_FORCE to true,
            UpdateWork.KEY_BASE_STAMP to (repo.store.value?.meta?.extractStamp ?: ""),
        )
        val network = Constraints.Builder()
            .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .build()
        workManager
            .beginUniqueWork(MANUAL_WORK, ExistingWorkPolicy.REPLACE, UpdateWork.downloadRequest(network, expedited = true, input))
            .then(UpdateWork.importRequest(expedited = true))
            .enqueue()
    }

    /** Cancels a queued or running manual update ("Cancel" while waiting). */
    fun cancelManual() {
        workManager.cancelUniqueWork(MANUAL_WORK)
    }

    private fun derive(check: Check, store: TowerStore?, manual: List<WorkInfo>, automatic: List<WorkInfo>): UpdateStatus {
        runningStatus(manual + automatic)?.let { return it }
        manual.firstOrNull { UpdateWork.TAG_DOWNLOAD in it.tags && it.state.isPending() }?.let { queued ->
            val wifiOnly = queued.constraints.requiredNetworkType == NetworkType.UNMETERED
            return UpdateStatus.Running(if (wifiOnly) PHASE_WAITING_WIFI else PHASE_WAITING, 0f)
        }
        // Downloaded, import about to start: still one run in progress, not "update available".
        if ((manual + automatic).any { UpdateWork.TAG_IMPORT in it.tags && it.state == WorkInfo.State.ENQUEUED }) {
            return UpdateStatus.Running(UpdateWork.PHASE_STARTING, 0f)
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
        // A failed download also fails its import (without a message): report the one that says why.
        val failed = manual.filter { it.state == WorkInfo.State.FAILED }
        return if (failed.isNotEmpty()) {
            UpdateStatus.Failed(failed.firstNotNullOfOrNull { it.outputData.getString(UpdateWork.KEY_ERROR) } ?: "Update failed")
        } else {
            checked
        }
    }

    private fun WorkInfo.State.isPending() = this == WorkInfo.State.ENQUEUED || this == WorkInfo.State.BLOCKED
}

/**
 * The run in progress among [works], or null if none is RUNNING. Several can be RUNNING while only
 * one holds the update lock (the others report [UpdateWork.PHASE_QUEUED]), so the one making
 * progress wins; a run that has not reported a real phase yet shows as starting.
 */
internal fun runningStatus(works: List<WorkInfo>): UpdateStatus.Running? {
    val running = works.filter { it.state == WorkInfo.State.RUNNING }
    fun WorkInfo.phase(): String? = progress.getString(UpdateWork.KEY_PHASE)
    val idle = setOf(null, UpdateWork.PHASE_QUEUED, UpdateWork.PHASE_STARTING)
    val work = running.firstOrNull { it.phase() !in idle } ?: running.firstOrNull() ?: return null
    val phase = work.phase()?.takeUnless { it == UpdateWork.PHASE_QUEUED } ?: UpdateWork.PHASE_STARTING
    val fraction = if (phase == UpdateWork.PHASE_STARTING) 0f else work.progress.getFloat(UpdateWork.KEY_FRACTION, 0f)
    return UpdateStatus.Running(phase, fraction)
}
