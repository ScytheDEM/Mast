package au.signalatlas.core.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.ForegroundInfo

/** The progress notification shown while TowerUpdateWorker runs in the foreground. */
class UpdateNotifications(context: Context) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)

    /** minSdk is 29, so the dataSync service type is always set. */
    fun foregroundInfo(phase: String, fraction: Float): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, notification(phase, fraction), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    /** Refreshes the notification already shown by the foreground service. */
    fun update(phase: String, fraction: Float) {
        manager.notify(NOTIFICATION_ID, notification(phase, fraction))
    }

    private fun notification(phase: String, fraction: Float): Notification {
        ensureChannel()
        val percent = (fraction.coerceIn(0f, 1f) * 100).toInt()
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(TITLE)
            .setContentText(phase)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "tower_updates"
        const val CHANNEL_NAME = "Tower data updates"
        const val TITLE = "Updating tower data"
        const val NOTIFICATION_ID = 0x5A7A
    }
}
