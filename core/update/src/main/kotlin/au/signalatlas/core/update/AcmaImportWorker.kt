package au.signalatlas.core.update

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import au.signalatlas.core.acma.ImportCancelledException
import au.signalatlas.core.acma.csv.AcmaFormatException
import au.signalatlas.core.data.Validation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.zip.ZipException

/**
 * Step 2 of an update: import the zip [AcmaDownloadWorker] left in `download/` into
 * `towers.db.building`, then validated atomic install and live store reload. It has no network
 * constraint, so connectivity changes never stop it; a stop (or retry) re-runs only the import.
 *
 * Once it decides to install (not stopped), install and reload run to completion together, so a
 * stop can never leave a newly installed DB unloaded. The current DB stays installed and open on
 * any failure. A zip that is unusable or a DB that fails validation fails with
 * [UpdateWork.KEY_ERROR] and discards the zip.
 */
class AcmaImportWorker(
    appContext: Context,
    params: WorkerParameters,
    deps: UpdateDeps,
) : UpdateWorker(appContext, params, deps) {

    override suspend fun work(): Result {
        if (inputData.getBoolean(UpdateWork.KEY_UP_TO_DATE, false)) return Result.success()
        val stamp = inputData.getString(UpdateWork.KEY_STAMP)
        val etag = inputData.getString(UpdateWork.KEY_ETAG)
        val size = inputData.getLong(UpdateWork.KEY_SIZE, -1L)
        if (stamp == null || etag == null || size <= 0) return Result.success() // nothing was downloaded
        val info = RemoteInfo(stamp, etag, size)

        try {
            withContext(Dispatchers.IO) { deps.installer.cleanupStale() }
            deps.repo.refresh()
        } catch (e: IOException) {
            return retryOrFail(e, "Could not open tower data")
        }
        // Another run installed this data while we waited for the lock.
        if (isUpToDate(stamp)) return Result.success()
        if (!deps.remote.isComplete(info, zip)) {
            val other = deps.remote.downloadedEtag(zip)
            // A newer download replaced ours; the run that made it imports it.
            if (other != null && other != etag) return Result.success()
            return Result.failure(workDataOf(UpdateWork.KEY_ERROR to "The downloaded tower data is missing. Try again."))
        }

        goForeground()
        try {
            withContext(Dispatchers.IO) {
                deps.importer.import(
                    zip, building, stamp, etag,
                    progress = { phase, fraction -> progress.value = phase to fraction },
                    cancel = { isStopped },
                )
            }
        } catch (e: ImportCancelledException) {
            return Result.retry() // stopped by WorkManager; the result is ignored
        } catch (e: ZipException) {
            return fail("Downloaded file is not a valid zip: ${e.message}")
        } catch (e: AcmaFormatException) {
            return fail("ACMA data format changed: ${e.message}")
        } catch (e: IOException) {
            return retryOrFail(e, "Could not build tower data") // e.g. storage full; the zip is kept
        }

        val validation = withContext(NonCancellable) {
            // A stop (or a cancelled manual update) before this point installs nothing.
            if (isStopped) return@withContext null
            val result = withContext(Dispatchers.IO) { deps.installer.install(building) }
            if (result == Validation.Ok) {
                deps.repo.reload()
                deps.remote.discard(zip)
            }
            result
        } ?: return Result.retry()

        return when (validation) {
            Validation.Ok -> Result.success()
            is Validation.Invalid -> fail("New tower data rejected: ${validation.reason}")
        }
    }
}
