package au.signalatlas.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import au.signalatlas.app.data.LocationSource
import au.signalatlas.core.calibration.Fix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Location through the platform [LocationManager] only (no Play Services).
 *
 * [last] only ever holds a fresh fix: a last-known fix older than [MAX_FIX_AGE_MS] (e.g. an
 * emulator's or a phone's position from another city hours ago) is ignored. Between [start] and
 * [stop] (the screen's started state) it follows high-accuracy updates from the fused provider
 * (API 31+), else GPS, else network. [currentFix] asks for one new fix (the "My location" button).
 * [enabled] reports whether location services are on; [refresh] re-reads it (e.g. on resume).
 */
class DeviceLocationSource(
    context: Context,
    /** Monotonic clock in nanoseconds, comparable with [Location.getElapsedRealtimeNanos]. */
    private val nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : LocationSource {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(LocationManager::class.java)
    private val _last = MutableStateFlow<Pair<Double, Double>?>(null)
    override val last: StateFlow<Pair<Double, Double>?> = _last.asStateFlow()
    private val _fixes = MutableStateFlow<Fix?>(null)

    /** The same fresh fixes as [last], with their horizontal accuracy (calibration needs <= 50 m) and wall-clock time. */
    val fixes: StateFlow<Fix?> = _fixes.asStateFlow()
    private val _estimated = MutableStateFlow(false)
    override val estimated: StateFlow<Boolean> = _estimated.asStateFlow()
    private val _enabled = MutableStateFlow(isLocationOn())
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var listening = false
    /** Monotonic time of the fix in [last], for [freshFix]. */
    @Volatile private var lastAtNanos = 0L
    private val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) {
            if (isFresh(location)) publish(location)
        }
        override fun onProviderEnabled(provider: String) = refresh()
        override fun onProviderDisabled(provider: String) = refresh()
    }

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Re-reads whether location services are on. */
    fun refresh() {
        _enabled.value = isLocationOn()
    }

    /** Idempotent; does nothing without permission or with location services off. Main thread. */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    fun start() {
        refresh()
        if (listening || manager == null || !hasPermission() || !_enabled.value) return
        try {
            val providers = enabledProviders()
            providers.mapNotNull { manager.getLastKnownLocation(it) }
                .filter { isFresh(it) }
                .maxByOrNull { it.elapsedRealtimeNanos }
                ?.let(::publish)
            val provider = providers.firstOrNull() ?: return
            // High accuracy: a balanced fused request may never power GPS, and network fixes are coarse.
            val request = LocationRequestCompat.Builder(UPDATE_INTERVAL_MS)
                .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
                .setMinUpdateDistanceMeters(UPDATE_DISTANCE_M)
                .build()
            LocationManagerCompat.requestLocationUpdates(manager, provider, request, listener, Looper.getMainLooper())
            listening = true
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        } catch (e: IllegalArgumentException) {
            // Provider disappeared.
        }
    }

    /** A fix from the trip service (which runs its own location request): published like a provider fix, so the sample logger sees it. */
    fun feed(fix: Fix) {
        lastAtNanos = nowNanos()
        _fixes.value = fix
        _last.value = fix.lat to fix.lon
        _estimated.value = fix.deadReckoned
    }

    fun stop() {
        if (!listening) return
        manager?.let { LocationManagerCompat.removeUpdates(it, listener) }
        listening = false
    }

    /** The fix in [last] if it is at most [MAX_FIX_AGE_MS] old, else null. */
    fun freshFix(): Pair<Double, Double>? =
        _last.value?.takeIf { nowNanos() - lastAtNanos <= MAX_FIX_AGE_MS * 1_000_000 }

    /**
     * Waits (at most [timeoutMs]) for one new fix from the best enabled provider, publishes and
     * returns it; null without permission, with location off, or on timeout. A cached fix older
     * than [MAX_FIX_AGE_MS] is never the answer (unlike `getCurrentLocation`, which may return one).
     */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    suspend fun currentFix(timeoutMs: Long = CURRENT_FIX_TIMEOUT_MS): Pair<Double, Double>? {
        refresh()
        val m = manager
        if (m == null || !hasPermission() || !_enabled.value) return null
        val provider = enabledProviders().firstOrNull() ?: return null
        val location = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                val once = object : LocationListenerCompat {
                    override fun onLocationChanged(location: Location) {
                        if (!isFresh(location) || !cont.isActive) return
                        LocationManagerCompat.removeUpdates(m, this)
                        cont.resume(location)
                    }
                    override fun onProviderDisabled(provider: String) {
                        refresh()
                        LocationManagerCompat.removeUpdates(m, this)
                        if (cont.isActive) cont.resume(null)
                    }
                }
                cont.invokeOnCancellation { LocationManagerCompat.removeUpdates(m, once) }
                try {
                    val request = LocationRequestCompat.Builder(CURRENT_FIX_INTERVAL_MS)
                        .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
                        .build()
                    LocationManagerCompat.requestLocationUpdates(m, provider, request, once, Looper.getMainLooper())
                } catch (e: SecurityException) {
                    cont.resume(null) // permission revoked between the check and the call
                } catch (e: IllegalArgumentException) {
                    cont.resume(null) // provider disappeared
                }
            }
        } ?: return null
        publish(location)
        return location.latitude to location.longitude
    }

    /** Fused (API 31+), GPS, network: those that exist and are enabled, best first. */
    private fun enabledProviders(): List<String> {
        val m = manager ?: return emptyList()
        val enabled = m.allProviders.filter { m.isProviderEnabled(it) }
        return listOfNotNull(fusedProvider(), LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { it in enabled }
    }

    private fun isFresh(location: Location) = nowNanos() - location.elapsedRealtimeNanos <= MAX_FIX_AGE_MS * 1_000_000

    private fun isLocationOn(): Boolean = manager != null && LocationManagerCompat.isLocationEnabled(manager)

    private fun fusedProvider(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else null

    private fun publish(location: Location) {
        lastAtNanos = location.elapsedRealtimeNanos
        _fixes.value = Fix(
            location.latitude, location.longitude,
            if (location.hasAccuracy()) location.accuracy.toDouble() else NO_ACCURACY_M, location.time,
        )
        _last.value = location.latitude to location.longitude
        _estimated.value = false
    }

    companion object {
        /** Older last-known fixes are never used for centring or Nearby. */
        const val MAX_FIX_AGE_MS = 2 * 60_000L
        const val CURRENT_FIX_TIMEOUT_MS = 30_000L
        /** Stand-in for a fix that reports no accuracy: far over every accuracy limit. */
        const val NO_ACCURACY_M = 10_000.0
        private const val UPDATE_INTERVAL_MS = 10_000L
        private const val CURRENT_FIX_INTERVAL_MS = 1_000L
        private const val UPDATE_DISTANCE_M = 25f
    }
}
