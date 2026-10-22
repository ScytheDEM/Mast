package au.signalatlas.core.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import au.signalatlas.core.acma.AcmaImporter
import au.signalatlas.core.acma.ImportCancelledException
import au.signalatlas.core.acma.csv.AcmaFormatException
import au.signalatlas.core.data.DbInstaller
import au.signalatlas.core.data.Validation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipException

/** Everything [TowerUpdateWorker] needs; the app supplies it through [TowerUpdateWorkerFactory]. */
interface UpdateDeps {
    val remote: AcmaRemote
    val repo: TowerRepository
    val importer: AcmaImporter
    val installer: DbInstaller
    /** The installer's directory: holds `towers.db`, the `.building` candidate and `download/`. */
    val workDir: File
    val notifications: UpdateNotifications
}

/** Builds [TowerUpdateWorker] with its dependencies; returns null for other workers (default construction). */
class TowerUpdateWorkerFactory(private val deps: UpdateDeps) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == TowerUpdateWorker::class.java.name) TowerUpdateWorker(appContext, workerParameters, deps) else null
}

/**
 * One update: check ACMA → download the zip (resumable) → import into `towers.db.building` →
 * validated atomic install → live store reload. The current DB stays installed and open on any
 * failure. Without input `force = true` it stops early when the live DB already has ACMA's stamp.
 *
 * Results: network trouble retries (failure after [MAX_ATTEMPTS] attempts); a malformed ACMA
 * response, an unusable zip or a DB that fails validation fails with output [KEY_ERROR].
 */
class TowerUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
    private val deps: UpdateDeps,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_FORCE = "force"
        const val KEY_ERROR = "error"
        const val KEY_PHASE = "phase"
        const val KEY_FRACTION = "fraction"
        const val PHASE_DOWNLOAD = "download"
        const val PHASE_STARTING = "starting"
        const val MAX_ATTEMPTS = 5
        private const val PROGRESS_INTERVAL_MS = 250L // at most 4 updates per second

        /** Periodic, stale and manual work share one directory; only one of them may run at a time. */
        private val running = Mutex()
    }

    private val progress = MutableStateFlow(PHASE_STARTING to 0f)
    @Volatile private var inForeground = false

    override suspend fun getForegroundInfo(): ForegroundInfo =
        progress.value.let { (phase, fraction) -> deps.notifications.foregroundInfo(phase, fraction) }

    override suspend fun doWork(): Result = running.withLock {
        coroutineScope {
            val reporter = launch { reportProgress() }
            try {
                update()
            } finally {
                reporter.cancel()
            }
        }
    }

    private suspend fun update(): Result {
        val zip = File(deps.workDir, "download/spectra_rrl.zip")
        val building = File(deps.workDir, "towers.db.building")
        val info = try {
            withContext(Dispatchers.IO) { deps.installer.cleanupStale() }
            deps.repo.start()
            deps.remote.info()
        } catch (e: RemoteFormatException) {
            return Result.failure(workDataOf(KEY_ERROR to "ACMA returned unexpected data: ${e.message}"))
        } catch (e: IOException) {
            return retryOrFail(e)
        }
        if (!inputData.getBoolean(KEY_FORCE, false) && deps.repo.store.value?.meta?.extractStamp == info.extractStamp) {
            return Result.success()
        }

        goForeground()
        try {
            zip.parentFile?.mkdirs()
            deps.remote.download(info, zip) { bytes, total ->
                progress.value = PHASE_DOWNLOAD to if (total > 0) bytes.toFloat() / total else 0f
            }
        } catch (e: IOException) {
            return retryOrFail(e) // RemoteChangedException already discarded the partial file.
        }

        val validation = try {
            withContext(Dispatchers.IO) {
                deps.importer.import(
                    zip, building, info.extractStamp, info.etag,
                    progress = { phase, fraction -> progress.value = phase to fraction },
                    cancel = { isStopped },
                )
                deps.installer.install(building)
            }
        } catch (e: ImportCancelledException) {
            return Result.retry() // stopped by WorkManager; the result is ignored
        } catch (e: ZipException) {
            return fail(zip, "Downloaded file is not a valid zip: ${e.message}")
        } catch (e: AcmaFormatException) {
            return fail(zip, "ACMA data format changed: ${e.message}")
        } catch (e: IOException) {
            return retryOrFail(e) // e.g. storage full; the downloaded zip is kept for the retry
        }

        return when (validation) {
            Validation.Ok -> {
                deps.repo.reload()
                deps.remote.discard(zip)
                Result.success()
            }
            is Validation.Invalid -> fail(zip, "New tower data rejected: ${validation.reason}")
        }
    }

    /** A terminal failure: the zip would only fail the same way again, so free its space. */
    private fun fail(zip: File, message: String): Result {
        deps.remote.discard(zip)
        return Result.failure(workDataOf(KEY_ERROR to message))
    }

    private fun retryOrFail(e: IOException): Result =
        if (runAttemptCount >= MAX_ATTEMPTS) {
            Result.failure(workDataOf(KEY_ERROR to "Could not reach ACMA: ${e.message ?: e.javaClass.simpleName}"))
        } else {
            Result.retry()
        }

    /** Best effort: tests and background-start restrictions (API 31+) may refuse a foreground service. */
    private suspend fun goForeground() {
        try {
            setForeground(getForegroundInfo())
            inForeground = true
        } catch (e: IllegalStateException) {
            inForeground = false
        }
    }

    /** Publishes the latest progress at most every [PROGRESS_INTERVAL_MS] (conflating in between). */
    private suspend fun reportProgress() {
        progress.collect { (phase, fraction) ->
            setProgress(workDataOf(KEY_PHASE to phase, KEY_FRACTION to fraction))
            if (inForeground) deps.notifications.update(phase, fraction)
            delay(PROGRESS_INTERVAL_MS)
        }
    }
}
