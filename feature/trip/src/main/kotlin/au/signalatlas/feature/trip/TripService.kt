package au.signalatlas.feature.trip

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The foreground service (type location) that keeps a trip alive with the screen off: it requests fixes from the
 * platform [LocationManager] (every second while moving, every 5 s once stationary for 60 s), hands them to the
 * [TripController], and shows the ongoing notification and the alerts the controller publishes. It stops when the trip
 * does, whoever stopped it (notification action, map button or tile).
 */
class TripService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var notifications: TripNotifications? = null
    private var manager: LocationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var observers: List<Job> = emptyList()
    private var requestedIntervalMs = 0L
    private var started = false
    @Volatile private var stopped = false
    private var voice: TripVoice? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            TripRuntime.controller?.onLocation(
                TripFix(
                    location.time, location.latitude, location.longitude,
                    if (location.hasAccuracy()) location.accuracy.toDouble() else NO_ACCURACY_M,
                    if (location.hasSpeed()) location.speed.toDouble() else null,
                ),
            )
        }

        // Required on API 29 (the platform's defaults only exist from API 30).
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        @Deprecated("Never called on API 29+")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = TripRuntime.controller
        if (intent?.action == ACTION_STOP) {
            controller?.stop()
            shutdown()
            return START_NOT_STICKY
        }
        if (controller == null || !controller.isRunning) {
            // Restarted without a running trip (process death): nothing to do. The foreground call is still owed.
            val n = (notifications ?: TripNotifications(this).also { notifications = it })
            startForeground(TripNotifications.ONGOING_ID, n.ongoing(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        started = true
        val n = TripNotifications(this).also { notifications = it }
        startForeground(
            TripNotifications.ONGOING_ID, n.ongoing((controller.state.value as? TripState.Running)?.stats),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "signalatlas:trip")?.apply { acquire(MAX_TRIP_MS) }
        manager = getSystemService(LocationManager::class.java)
        voice = TripVoice(this, { TripRuntime.voiceEnabled() })
        requestUpdates(MOVING_INTERVAL_MS)
        observers = listOf(
            scope.launch {
                controller.state.collect { s ->
                    when (s) {
                        is TripState.Running -> n.postOngoing(s.stats)
                        TripState.Idle -> shutdown()
                    }
                }
            },
            scope.launch { controller.alerts.collect { onAlert(n, it) } },
            scope.launch {
                controller.stationary.collect { requestUpdates(if (it) STATIONARY_INTERVAL_MS else MOVING_INTERVAL_MS) }
            },
        )
        return START_NOT_STICKY
    }

    /** Alerts post a heads-up notification. */
    private fun onAlert(n: TripNotifications, alert: Alert) {
        n.postAlert(alert)
        voice?.speak(alert)
    }

    @SuppressLint("MissingPermission") // the app asks for location before it starts a trip
    @Synchronized
    private fun requestUpdates(intervalMs: Long) {
        val m = manager ?: return
        if (stopped || intervalMs == requestedIntervalMs) return
        try {
            m.removeUpdates(listener)
            val provider = provider(m) ?: return
            m.requestLocationUpdates(provider, intervalMs, 0f, listener, Looper.getMainLooper())
            requestedIntervalMs = intervalMs
        } catch (e: SecurityException) {
            TripRuntime.controller?.stop() // permission revoked: end the trip
        } catch (e: IllegalArgumentException) {
            // The provider disappeared; the next interval change tries again.
        }
    }

    private fun provider(m: LocationManager): String? {
        val enabled = m.allProviders.filter { m.isProviderEnabled(it) }
        // GPS first: a 1 Hz fused request is not guaranteed to power the GPS.
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { it in enabled }
    }

    /**
     * Deterministic stop, from the stop action, a trip that ended elsewhere (map button, tile) or the system: no more
     * location requests, no evaluation or collectors, the notifications gone, and the service itself stopped.
     */
    @Synchronized
    private fun shutdown() {
        if (!stopped) {
            stopped = true
            manager?.removeUpdates(listener)
            requestedIntervalMs = 0
            observers.forEach { it.cancel() }
            scope.cancel()
            voice?.shutdown()
            wakeLock?.takeIf { it.isHeld }?.release()
            TripRuntime.controller?.onServiceGone()
            notifications?.cancelAll()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "au.signalatlas.feature.trip.STOP"
        const val MOVING_INTERVAL_MS = 1_000L
        const val STATIONARY_INTERVAL_MS = 5_000L
        private const val NO_ACCURACY_M = 10_000.0
        private const val MAX_TRIP_MS = 12 * 60 * 60 * 1000L

        /** Starts the foreground service: only from the app in the foreground (button, tile tap). */
        fun start(context: Context) {
            context.applicationContext.startForegroundService(Intent(context.applicationContext, TripService::class.java))
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context.applicationContext, TripService::class.java))
        }
    }
}
