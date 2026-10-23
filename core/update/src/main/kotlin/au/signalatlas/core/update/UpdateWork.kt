package au.signalatlas.core.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import au.signalatlas.core.acma.AcmaImporter
import au.signalatlas.core.data.DbInstaller
import au.signalatlas.core.data.TowerRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/** Everything the update workers need; the app supplies it through [TowerUpdateWorkerFactory]. */
interface UpdateDeps {
    val remote: AcmaRemote
    val repo: TowerRepository
    val importer: AcmaImporter
    val installer: DbInstaller
    /** The installer's directory: holds `towers.db`, the `.building` candidate and `download/`. */
    val workDir: File
    val notifications: UpdateNotifications
}

/**
 * Builds [AcmaDownloadWorker], [AcmaImportWorker] and (given [hrpDeps]) [HrpWorker] with their
 * dependencies; null for other workers.
 */
class TowerUpdateWorkerFactory(
    private val deps: UpdateDeps,
    private val hrpDeps: HrpDeps? = null,
) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        when (workerClassName) {
            AcmaDownloadWorker::class.java.name -> AcmaDownloadWorker(appContext, workerParameters, deps)
            AcmaImportWorker::class.java.name -> AcmaImportWorker(appContext, workerParameters, deps)
            HrpWorker::class.java.name -> hrpDeps?.let { HrpWorker(appContext, workerParameters, it) }
            else -> null
        }
}

/**
 * Keys, phases and request builders shared by the two update workers.
 *
 * An update is a chain: [AcmaDownloadWorker] (network constraint; checks ACMA and downloads the
 * zip, resumable) then [AcmaImportWorker] (no network constraint, so a Wi-Fi change cannot stop
 * it; imports, installs and reloads). The download's output (stamp, ETag, size, force, base stamp)
 * is the import's input.
 */
object UpdateWork {
    /** Download even if the live DB already has ACMA's stamp (manual "Update now"). */
    const val KEY_FORCE = "force"
    /**
     * With [KEY_FORCE]: the live stamp when the user asked ("" = no data). If the live DB has changed
     * since and already has ACMA's stamp, the forced run is skipped (another run installed it).
     */
    const val KEY_BASE_STAMP = "base_stamp"
    /** Periodic download: enqueue the import itself (periodic work cannot be chained). */
    const val KEY_THEN_IMPORT = "then_import"
    const val KEY_STAMP = "stamp"
    const val KEY_ETAG = "etag"
    const val KEY_SIZE = "size"
    /** The download found the live DB current; the import has nothing to do. */
    const val KEY_UP_TO_DATE = "up_to_date"
    const val KEY_ERROR = "error"
    const val KEY_PHASE = "phase"
    const val KEY_FRACTION = "fraction"

    /** Published before a worker waits for the update lock; a worker in this phase is doing nothing. */
    const val PHASE_QUEUED = "queued"
    const val PHASE_STARTING = "starting"
    const val PHASE_DOWNLOAD = "download"

    const val TAG_DOWNLOAD = "acma-download"
    const val TAG_IMPORT = "acma-import"
    /** The monthly (and manual) antenna-pattern job, [HrpWorker]. */
    const val TAG_HRP = "acma-hrp"
    /** At most 4 progress updates per second. */
    const val PROGRESS_INTERVAL_MS = 250L
    const val MAX_ATTEMPTS = 5

    /** Logcat tag of the download timing line (`phase=download ms=<elapsed> bytes=<size>`). */
    const val LOG_TAG_DOWNLOAD = "AcmaDownload"

    /** The import step: only storage-not-low, never a network constraint. */
    fun importRequest(expedited: Boolean, input: Data = Data.EMPTY): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<AcmaImportWorker>()
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .setInputData(input)
            .addTag(TAG_IMPORT)
            .apply { if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()

    fun downloadRequest(constraints: Constraints, expedited: Boolean, input: Data = Data.EMPTY): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<AcmaDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(input)
            .addTag(TAG_DOWNLOAD)
            .apply { if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
}

/**
 * Shared plumbing: one worker at a time (periodic, automatic and manual work share one directory),
 * throttled progress, the foreground notification and the retry policy.
 */
abstract class UpdateWorker(
    appContext: Context,
    params: WorkerParameters,
    protected val deps: UpdateDeps,
) : CoroutineWorker(appContext, params) {

    protected val progress = MutableStateFlow(UpdateWork.PHASE_STARTING to 0f)
    @Volatile private var inForeground = false

    protected val zip: File get() = File(deps.workDir, "download/spectra_rrl.zip")
    protected val building: File get() = File(deps.workDir, "towers.db.building")
    protected val force: Boolean get() = inputData.getBoolean(UpdateWork.KEY_FORCE, false)
    protected val baseStamp: String? get() = inputData.getString(UpdateWork.KEY_BASE_STAMP)

    override suspend fun getForegroundInfo(): ForegroundInfo =
        progress.value.let { (phase, fraction) -> deps.notifications.foregroundInfo(phase, fraction) }

    final override suspend fun doWork(): Result {
        // Visible to the status while this worker waits for another one to finish.
        setProgress(workDataOf(UpdateWork.KEY_PHASE to UpdateWork.PHASE_QUEUED, UpdateWork.KEY_FRACTION to 0f))
        return UpdateLocks.running.withLock {
            coroutineScope {
                val reporter = launch { reportProgress() }
                try {
                    work()
                } finally {
                    reporter.cancel()
                }
            }
        }
    }

    protected abstract suspend fun work(): Result

    /** True when the live DB already has [stamp] and this run need not (re)build it. */
    protected fun isUpToDate(stamp: String): Boolean {
        val live = deps.repo.store.value?.meta?.extractStamp ?: return false
        if (live != stamp) return false
        val base = baseStamp
        return !force || (base != null && live != base)
    }

    /** A terminal failure: the zip would only fail the same way again, so free its space. */
    protected fun fail(message: String): Result {
        deps.remote.discard(zip)
        return Result.failure(workDataOf(UpdateWork.KEY_ERROR to message))
    }

    protected fun retryOrFail(e: IOException, prefix: String): Result =
        if (runAttemptCount >= UpdateWork.MAX_ATTEMPTS) {
            Result.failure(workDataOf(UpdateWork.KEY_ERROR to "$prefix: ${e.message ?: e.javaClass.simpleName}"))
        } else {
            Result.retry()
        }

    /** Best effort: tests and background-start restrictions (API 31+) may refuse a foreground service. */
    protected suspend fun goForeground() {
        try {
            setForeground(getForegroundInfo())
            inForeground = true
        } catch (e: IllegalStateException) {
            inForeground = false
        }
    }

    private suspend fun reportProgress() =
        publishProgress(progress) { phase, fraction -> if (inForeground) deps.notifications.update(phase, fraction) }
}

/**
 * Publishes the latest of [progress] as WorkManager progress ([UpdateWork.KEY_PHASE],
 * [UpdateWork.KEY_FRACTION]) and to [notify], at most every [UpdateWork.PROGRESS_INTERVAL_MS]
 * (conflating in between). Runs until cancelled.
 */
internal suspend fun CoroutineWorker.publishProgress(
    progress: StateFlow<Pair<String, Float>>,
    notify: (phase: String, fraction: Float) -> Unit,
) {
    progress.collect { (phase, fraction) ->
        setProgress(workDataOf(UpdateWork.KEY_PHASE to phase, UpdateWork.KEY_FRACTION to fraction))
        notify(phase, fraction)
        delay(UpdateWork.PROGRESS_INTERVAL_MS)
    }
}
