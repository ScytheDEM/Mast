package au.signalatlas.core.update

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Step 1 of an update: check ACMA and download the zip (resumable) into `download/`. Without
 * [UpdateWork.KEY_FORCE] it stops early when the live DB already has ACMA's stamp. The finished zip
 * and its ETag sidecar stay for [AcmaImportWorker], which gets stamp, ETag and size as input.
 *
 * Results: network trouble retries (failure after [UpdateWork.MAX_ATTEMPTS] attempts); a malformed
 * ACMA response fails with output [UpdateWork.KEY_ERROR].
 */
class AcmaDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
    deps: UpdateDeps,
) : UpdateWorker(appContext, params, deps) {

    override suspend fun work(): Result {
        val info = try {
            // Safe here: the update lock is held, so no import is in progress.
            withContext(Dispatchers.IO) { deps.installer.cleanupStale() }
            deps.repo.refresh()
            deps.remote.info()
        } catch (e: RemoteFormatException) {
            return Result.failure(workDataOf(UpdateWork.KEY_ERROR to "ACMA returned unexpected data: ${e.message}"))
        } catch (e: IOException) {
            return retryOrFail(e, "Could not reach ACMA")
        }
        if (isUpToDate(info.extractStamp)) {
            // A finished download of data that is already live (e.g. left by a stopped run) is useless.
            if (deps.remote.downloadedEtag(zip) == info.etag) deps.remote.discard(zip)
            return Result.success(workDataOf(UpdateWork.KEY_UP_TO_DATE to true))
        }

        goForeground()
        val started = System.nanoTime()
        try {
            zip.parentFile?.mkdirs()
            deps.remote.download(info, zip) { bytes, total ->
                progress.value = UpdateWork.PHASE_DOWNLOAD to if (total > 0) bytes.toFloat() / total else 0f
            }
        } catch (e: IOException) {
            return retryOrFail(e, "Could not reach ACMA") // RemoteChangedException already discarded the partial file.
        }
        Log.i(UpdateWork.LOG_TAG_DOWNLOAD, "phase=download ms=${(System.nanoTime() - started) / 1_000_000} bytes=${info.sizeBytes}")

        val out = Data.Builder()
            .putString(UpdateWork.KEY_STAMP, info.extractStamp)
            .putString(UpdateWork.KEY_ETAG, info.etag)
            .putLong(UpdateWork.KEY_SIZE, info.sizeBytes)
            .putBoolean(UpdateWork.KEY_FORCE, force)
            .apply { baseStamp?.let { putString(UpdateWork.KEY_BASE_STAMP, it) } }
            .build()
        if (inputData.getBoolean(UpdateWork.KEY_THEN_IMPORT, false)) {
            // KEEP: an automatic chain already pending imports the same zip itself.
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                UpdateManager.AUTO_WORK,
                ExistingWorkPolicy.KEEP,
                UpdateWork.importRequest(expedited = false, input = out),
            )
        }
        return Result.success(out)
    }
}
