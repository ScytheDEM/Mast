package au.signalatlas.feature.trip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** The trip's notifications: the ongoing status (channel `trip_status`, low) and the alerts (`coverage_alerts`, high). */
class TripNotifications(context: Context) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        if (manager.getNotificationChannel(STATUS_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(STATUS_CHANNEL, context.getString(R.string.trip_channel_status), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.trip_channel_status_description)
                },
            )
        }
        if (manager.getNotificationChannel(ALERT_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL, context.getString(R.string.trip_channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.trip_channel_alerts_description)
                    enableVibration(true)
                },
            )
        }
    }

    /** The ongoing notification for [stats] (null = just started). */
    fun ongoing(stats: TripStats?): Notification {
        ensureChannels()
        return Notification.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(context.getString(R.string.trip_notification_title) + (stats?.let { s -> listOfNotNull(s.network, s.tech).joinToString(" ").takeIf { it.isNotEmpty() }?.let { " · $it" } } ?: ""))
            .setContentText(stats?.status?.text ?: stats?.message ?: TripController.WAITING_GPS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(openApp())
            .addAction(stopAction())
            .build()
    }

    fun postOngoing(stats: TripStats?) {
        manager.notify(ONGOING_ID, ongoing(stats))
    }

    /** A heads-up alert. */
    fun postAlert(alert: Alert) {
        ensureChannels()
        val n = Notification.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(context.getString(R.string.trip_alert_title))
            .setContentText(alert.text)
            .setStyle(Notification.BigTextStyle().bigText(alert.text))
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        manager.notify(ALERT_ID, n)
    }

    fun cancelAll() {
        manager.cancel(ALERT_ID)
        manager.cancel(ONGOING_ID)
    }

    private fun openApp(): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    private fun stopAction(): Notification.Action = Notification.Action.Builder(
        null, context.getString(R.string.trip_stop_action),
        PendingIntent.getService(
            context, 1, Intent(context, TripService::class.java).setAction(TripService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    ).build()

    companion object {
        const val STATUS_CHANNEL = "trip_status"
        const val ALERT_CHANNEL = "coverage_alerts"
        const val ONGOING_ID = 0x7219
        const val ALERT_ID = 0x721A
    }
}
