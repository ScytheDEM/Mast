package au.signalatlas.app.packs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import au.signalatlas.core.model.DataSettings
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import au.signalatlas.app.R
import au.signalatlas.core.geo.InsufficientStorageException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What [PackWorker] needs, supplied by the app container. */
class PackDeps(val downloader: PackDownloader, val repo: PackRepository)

/** Progress notification of a pack download (foreground service type dataSync, as the tower download). */
class PackNotifications(context: Context) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)

    fun foregroundInfo(packId: String, name: String, fraction: Float): ForegroundInfo =
        ForegroundInfo(idOf(packId), build(name, fraction), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    fun update(packId: String, name: String, fraction: Float) = manager.notify(idOf(packId), build(name, fraction))

    private fun build(name: String, fraction: Float): android.app.Notification {
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.packs_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val percent = (fraction.coerceIn(0f, 1f) * 100).toInt()
        return android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.packs_notification_title, name))
            .setContentText("$percent%")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(android.app.Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun idOf(packId: String) = BASE_ID + (packId.hashCode() and 0xFFF)

    private companion object {
        const val CHANNEL_ID = "offline_packs"
        const val BASE_ID = 6000
    }
}

/**
 * Downloads one pack. A network drop or a stop is retried with backoff and resumes where it stopped (see
 * [PackDownloader]); low storage and a metered network (when the user did not allow it) are not retried.
 */
class PackWorker(
    context: Context,
    params: WorkerParameters,
    private val deps: PackDeps,
    private val notifications: PackNotifications,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val packId = inputData.getString(KEY_PACK) ?: return Result.failure()
        val allowMetered = inputData.getBoolean(KEY_METERED, false)
        val name = deps.repo.get(packId)?.name ?: return Result.success() // deleted meanwhile
        setForeground(notifications.foregroundInfo(packId, name, 0f))
        return try {
            deps.downloader.run(packId, allowMetered) { notifications.update(packId, name, it) }
            Result.success()
        } catch (e: InsufficientStorageException) {
            deps.downloader.fail(packId, "Not enough free storage")
            Result.failure()
        } catch (e: IOException) {
            if (runAttemptCount >= MAX_ATTEMPTS) {
                deps.downloader.fail(packId, e.message ?: "Download failed")
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    companion object {
        const val KEY_PACK = "pack"
        const val KEY_METERED = "metered"
        const val MAX_ATTEMPTS = 8
        fun workName(packId: String) = "pack-$packId"
    }
}

class PackWorkerFactory(private val deps: PackDeps, private val notifications: PackNotifications) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == PackWorker::class.java.name) PackWorker(appContext, workerParameters, deps, notifications) else null
}

/** Unmetered by default; "use mobile data" relaxes it to any connected network. */
class WorkManagerPackScheduler(private val context: Context) : PackScheduler {
    override fun enqueue(packId: String, allowMetered: Boolean) {
        val request = OneTimeWorkRequestBuilder<PackWorker>()
            .setInputData(Data.Builder().putString(PackWorker.KEY_PACK, packId).putBoolean(PackWorker.KEY_METERED, allowMetered).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (allowMetered || DataSettings.mobileDataUnmetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(PackWorker.workName(packId), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(packId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(PackWorker.workName(packId))
    }
}
