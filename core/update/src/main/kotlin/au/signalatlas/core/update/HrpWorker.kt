package au.signalatlas.core.update

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import au.signalatlas.core.acma.ImportCancelledException
import au.signalatlas.core.acma.hrp.HrpFormatException
import au.signalatlas.core.acma.hrp.HrpImporter
import au.signalatlas.core.data.PatternInstaller
import au.signalatlas.core.data.PatternRepository
import au.signalatlas.core.data.Validation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipException

/** Everything [HrpWorker] needs; the app supplies it through [TowerUpdateWorkerFactory]. */
interface HrpDeps {
    val remote: HrpRemote
    val importer: HrpImporter
    val installer: PatternInstaller
    val patterns: PatternRepository
    /** The live `towers.db`: its carriers decide which devices' patterns are kept. */
    val towersDb: File
    /** Holds `patterns.db`, its `.building` candidate and `download/` (the tower DB's directory). */
    val workDir: File
    val notifications: UpdateNotifications
    /** Free space on the volume holding a file (seam for tests). */
    val freeBytes: (File) -> Long get() = { StatFs(it.path).availableBytes }
}

/**
 * The antenna-pattern job: download ACMA's HRP zip, stream it into `patterns.db.building`, then a
 * validated atomic install and reload of [HrpDeps.patterns]. Unique work [UpdateManager.HRP_WORK]
 * (monthly) or [UpdateManager.HRP_MANUAL_WORK]; one run at a time per process, independent of the
 * tower update (different files, different lock).
 *
 * It skips (success, no download) when the installed patterns already have the published ETag.
 * Before any request it needs [UpdatePolicy.HRP_MIN_FREE_BYTES] free (bytes already downloaded
 * count), else it retries later. It also retries while no tower DB is installed.
 *
 * Stops: the downloaded zip and its sidecar persist, so a retry resumes or reuses the download; the
 * import itself restarts from the beginning of the zip (the 10 GB CSV is not checkpointed). A stop
 * before the install leaves no `.building` file (the importer deletes it, and [PatternInstaller]
 * cleans up after a killed process); once the install begins, install and reload finish together.
 *
 * Rulings BB/HH: a version change while downloading (412, or a different ETag, Last-Modified or
 * length) restarts the download from byte 0 after a fresh HEAD; a CRC or size mismatch of the zip
 * entry discards the zip and its sidecar and retries, so the next run downloads from byte 0.
 */
class HrpWorker(
    appContext: Context,
    params: WorkerParameters,
    private val deps: HrpDeps,
) : CoroutineWorker(appContext, params) {

    companion object {
        /** Progress phase while streaming the CSV into `patterns.db.building`. */
        const val PHASE_IMPORT = "patterns"
        const val LOG_TAG = "HrpWorker"
        /** HEAD + download attempts in one run when the remote version changes underneath. */
        const val VERSION_ATTEMPTS = 3

        /** Process-wide: monthly and manual runs share `patterns.db.building` and the download. */
        private val running = Mutex()
    }

    private val progress = MutableStateFlow(UpdateWork.PHASE_STARTING to 0f)
    @Volatile private var inForeground = false

    private val zip: File get() = File(deps.workDir, "download/spectra_licence_hrp.zip")

    override suspend fun getForegroundInfo(): ForegroundInfo = progress.value.let { (phase, fraction) ->
        deps.notifications.foregroundInfo(phase, fraction, UpdateNotifications.Job.PATTERNS)
    }

    override suspend fun doWork(): Result {
        setProgress(workDataOf(UpdateWork.KEY_PHASE to UpdateWork.PHASE_QUEUED, UpdateWork.KEY_FRACTION to 0f))
        return running.withLock {
            coroutineScope {
                val reporter = launch {
                    publishProgress(progress) { phase, fraction ->
                        if (inForeground) deps.notifications.update(phase, fraction, UpdateNotifications.Job.PATTERNS)
                    }
                }
                try {
                    work()
                } finally {
                    reporter.cancel()
                }
            }
        }
    }

    private suspend fun work(): Result {
        try {
            // Safe here: the lock is held, so no pattern import is in progress.
            withContext(Dispatchers.IO) { deps.installer.cleanupStale() }
            deps.patterns.start()
        } catch (e: IOException) {
            return retryOrFail(e, "Could not open antenna patterns")
        }
        if (!deps.towersDb.isFile) return Result.retry() // no tower data yet to select devices by

        val downloaded = withContext(Dispatchers.IO) { if (zip.isFile) zip.length() else 0L }
        if (deps.freeBytes(deps.workDir) + downloaded < UpdatePolicy.HRP_MIN_FREE_BYTES) return Result.retry()

        // The CDN's edges can serve two versions at once: a version change between HEAD and GET (or
        // between resumed requests) discards the bytes, and the download starts again from 0 with a
        // fresh HEAD, up to [VERSION_ATTEMPTS] times in this run.
        var info: RemoteFile
        var attempt = 0
        val started = System.nanoTime()
        while (true) {
            info = try {
                deps.remote.info()
            } catch (e: RemoteFormatException) {
                return Result.failure(workDataOf(UpdateWork.KEY_ERROR to "ACMA returned unexpected data: ${e.message}"))
            } catch (e: IOException) {
                return retryOrFail(e, "Could not reach ACMA")
            }
            if (deps.patterns.patterns.value?.etag == info.etag) {
                // A finished download of the installed version (left by a stopped run) is useless.
                if (deps.remote.downloaded(zip)?.etag == info.etag) deps.remote.discard(zip)
                return Result.success()
            }
            goForeground()
            try {
                zip.parentFile?.mkdirs()
                deps.remote.download(info, zip) { bytes, total ->
                    progress.value = UpdateWork.PHASE_DOWNLOAD to if (total > 0) bytes.toFloat() / total else 0f
                }
                break
            } catch (e: RemoteChangedException) {
                // Already discarded: the next attempt starts from byte 0.
                if (++attempt >= VERSION_ATTEMPTS) return retryOrFail(e, "ACMA's pattern file kept changing")
            } catch (e: IOException) {
                return retryOrFail(e, "Could not reach ACMA")
            }
        }
        Log.i(LOG_TAG, "phase=download ms=${(System.nanoTime() - started) / 1_000_000} bytes=${info.sizeBytes}")

        try {
            withContext(Dispatchers.IO) {
                deps.importer.import(
                    zip, deps.towersDb, deps.installer.building, info.etag,
                    cancel = { isStopped },
                    progress = { progress.value = PHASE_IMPORT to it },
                )
            }
        } catch (e: ImportCancelledException) {
            return Result.retry() // stopped by WorkManager; the result is ignored
        } catch (e: ZipException) {
            deps.remote.discard(zip) // Ruling BB: corrupt or spliced bytes; download again from 0
            return retryOrFail(e, "Downloaded antenna patterns are corrupt")
        } catch (e: HrpFormatException) {
            deps.remote.discard(zip)
            return Result.failure(workDataOf(UpdateWork.KEY_ERROR to "ACMA pattern format changed: ${e.message}"))
        } catch (e: IOException) {
            return retryOrFail(e, "Could not build antenna patterns") // e.g. storage full; the zip is kept
        }

        val validation = withContext(NonCancellable) {
            // A stop before this point installs nothing.
            if (isStopped) return@withContext null
            val result = withContext(Dispatchers.IO) { deps.installer.install(deps.installer.building) }
            if (result == Validation.Ok) {
                deps.patterns.reload()
                deps.remote.discard(zip)
            }
            result
        } ?: return Result.retry()

        return when (validation) {
            Validation.Ok -> Result.success()
            is Validation.Invalid -> {
                deps.remote.discard(zip)
                Result.failure(workDataOf(UpdateWork.KEY_ERROR to "New antenna patterns rejected: ${validation.reason}"))
            }
        }
    }

    private fun retryOrFail(e: IOException, prefix: String): Result =
        if (runAttemptCount >= UpdateWork.MAX_ATTEMPTS) {
            Result.failure(workDataOf(UpdateWork.KEY_ERROR to "$prefix: ${e.message ?: e.javaClass.simpleName}"))
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
}
