package au.signalatlas.app.das

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import au.signalatlas.app.MainActivity
import au.signalatlas.app.R
import au.signalatlas.app.connected.ConnectedUi
import au.signalatlas.app.data.TowerSnapshot
import au.signalatlas.core.model.SiteKind
import au.signalatlas.core.radio.RadioState
import au.signalatlas.feature.trip.TripController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.cos
import kotlin.math.hypot

/** One "DAS detected" announcement: [key] is what the 30-minute suppression counts by (a site, or the unnamed tunnel). */
data class DasEvent(val key: String, val text: String, val kind: SiteKind, val siteId: Int?)

/**
 * Decides when to announce a distributed antenna system (DAS):
 * - the matched serving tower is a DAS site within [BUILDING_SITE_RADIUS_M] of you: "DAS detected: {site name} ({networks})";
 * - or the vehicle is on a tunnel road (road map-matching, or GPS lost just after matching onto one) with RSRP >=
 *   [TUNNEL_MIN_RSRP]: "DAS detected: tunnel coverage", plus " · {site name}" when the serving site is a tunnel DAS site
 *   within [TUNNEL_SITE_RADIUS_M] of the vehicle.
 * Each key is announced at most once per [suppressMs] (30 min).
 */
class DasDetector(
    private val towers: () -> TowerSnapshot?,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val suppressMs: Long = SUPPRESS_MS,
) {
    private val announced = HashMap<String, Long>()

    @Synchronized
    fun evaluate(servingSiteId: Int?, rsrpDbm: Int?, inTunnel: Boolean, position: Pair<Double, Double>?): DasEvent? {
        val serving = servingSiteId?.let { towers()?.site(it) }
        val event = if (inTunnel && rsrpDbm != null && rsrpDbm >= TUNNEL_MIN_RSRP) {
            val site = serving?.takeIf {
                it.kind == SiteKind.DAS_TUNNEL && position != null && distanceM(position.first, position.second, it.lat, it.lon) <= TUNNEL_SITE_RADIUS_M
            }
            DasEvent(
                site?.let { "site:${it.id}" } ?: "tunnel",
                "DAS detected: tunnel coverage" + (site?.let { " · ${it.name}" } ?: ""),
                SiteKind.DAS_TUNNEL, site?.id,
            )
        } else if (serving != null && serving.kind.isDas &&
            // You are inside a DAS building, so a known position far from it means the tower match is wrong.
            (position == null || distanceM(position.first, position.second, serving.lat, serving.lon) <= BUILDING_SITE_RADIUS_M)
        ) {
            val networks = serving.networks.sortedBy { it.ordinal }.joinToString(", ") { TripController.networkName(it) }
            DasEvent("site:${serving.id}", "DAS detected: ${serving.name} ($networks)", serving.kind, serving.id)
        } else {
            return null
        }
        val now = clockMs()
        val last = announced[event.key]
        if (last != null && now - last < suppressMs) return null
        announced[event.key] = now
        return event
    }

    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        hypot(Math.toRadians(lon2 - lon1) * cos(Math.toRadians(lat1)), Math.toRadians(lat2 - lat1)) * 6_371_000.0

    companion object {
        const val SUPPRESS_MS = 30 * 60_000L
        const val TUNNEL_MIN_RSRP = -105
        const val TUNNEL_SITE_RADIUS_M = 1_000.0
        const val BUILDING_SITE_RADIUS_M = 400.0
    }
}

/** Where a [DasEvent] is announced outside the app (a low-importance notification). */
fun interface DasNotifier {
    fun notify(event: DasEvent)

    companion object {
        val None = DasNotifier { }
    }
}

/** Notification channel `das_detected`, low importance: no sound, no heads-up. */
class AndroidDasNotifier(context: Context) : DasNotifier {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)

    override fun notify(event: DasEvent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.das_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.das_channel_description)
                },
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_cell_tower)
            .setContentTitle(context.getString(R.string.das_notification_title))
            .setContentText(event.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            // Notifications blocked: the in-app banner still shows.
        }
    }

    companion object {
        const val CHANNEL = "das_detected"
        private const val NOTIFICATION_ID = 0x4441
    }
}

/**
 * Feeds the [DasDetector] from the live state (the matched serving site from the info bar, the trip's tunnel flag, the
 * radio's RSRP and the position) and publishes each announcement as an in-app [banner] and a [DasNotifier] notification.
 */
class DasMonitor(
    scope: CoroutineScope,
    ui: StateFlow<ConnectedUi>,
    tunnel: StateFlow<Boolean>,
    radio: StateFlow<RadioState>,
    position: StateFlow<Pair<Double, Double>?>,
    private val detector: DasDetector,
    private val notifier: DasNotifier,
) {
    private val _banner = MutableStateFlow<DasEvent?>(null)
    val banner: StateFlow<DasEvent?> = _banner.asStateFlow()

    init {
        combine(ui, tunnel, radio, position) { u, t, r, p -> Inputs(u, t, r, p) }.conflate().onEach { i ->
            detector.evaluate(i.ui.tower?.siteId, i.ui.rsrpDbm ?: i.radio.serving?.rsrp, i.tunnel, i.position)?.let { e ->
                _banner.value = e
                notifier.notify(e)
            }
        }.launchIn(scope)
    }

    fun dismiss() {
        _banner.value = null
    }

    private class Inputs(val ui: ConnectedUi, val tunnel: Boolean, val radio: RadioState, val position: Pair<Double, Double>?)
}
