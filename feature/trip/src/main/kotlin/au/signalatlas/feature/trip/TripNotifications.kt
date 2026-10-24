package au.signalatlas.feature.trip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build

/** The trip's notifications: the ongoing status (channel `trip_status`, low) and the alerts (`coverage_alerts`, high). */
class TripNotifications(
    context: Context,
    /** Injectable so the API 36 branch can be tested on an older Robolectric SDK. */
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val liveUpdate: LiveUpdate = PlatformLiveUpdate(context.applicationContext),
) {
    /** Applies the Android 16 Live Update presentation (progress style, chip text, promotion request). */
    fun interface LiveUpdate {
        fun apply(builder: Notification.Builder, content: TripContent)
    }

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
    fun ongoing(stats: TripStats?): Notification = ongoing(
        TripContent.of(stats, context.getString(R.string.trip_notification_title)),
    )

    fun ongoing(content: TripContent): Notification {
        ensureChannels()
        val b = Notification.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_trip)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(openApp())
            .addAction(stopAction())
        // API 36+: a Live Update (strip of the road ahead, chip text); older releases show the same text as a plain ongoing notification.
        if (sdkInt >= LIVE_UPDATE_SDK) liveUpdate.apply(b, content)
        return b.build()
    }

    fun postOngoing(stats: TripStats?) {
        manager.notify(ONGOING_ID, ongoing(stats))
    }

    /** A heads-up alert. */
    fun postAlert(alert: Alert) {
        ensureChannels()
        val n = Notification.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_trip)
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
        const val LIVE_UPDATE_SDK = 36
        const val STATUS_CHANNEL = "trip_status"
        const val ALERT_CHANNEL = "coverage_alerts"
        const val ONGOING_ID = 0x7219
        const val ALERT_ID = 0x721A
    }
}

/** The real Live Update presentation; only called on API 36+, where these classes exist. */
class PlatformLiveUpdate(private val context: Context) : TripNotifications.LiveUpdate {
    override fun apply(builder: Notification.Builder, content: TripContent) {
        if (content.segments.isNotEmpty()) {
            builder.setStyle(
                Notification.ProgressStyle()
                    .setProgressSegments(content.segments.map { Notification.ProgressStyle.Segment(it.lengthM).setColor(it.level.color) })
                    .setProgress(content.progress)
                    .setStyledByProgress(false)
                    .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.ic_trip_tracker)),
            )
        }
        content.shortCritical?.let { builder.setShortCriticalText(it) }
        // Promotion may be refused (user setting or device): the notification then shows as a normal ongoing one.
        builder.setRequestPromotedOngoing(true)
    }
}
