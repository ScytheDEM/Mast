package au.signalatlas.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.location.LocationManagerCompat
import au.signalatlas.app.data.LocationSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Location through the platform [LocationManager] only (no Play Services): the freshest last
 * known fix from the fused, GPS and network providers, then updates from the first enabled of
 * network / GPS / fused between [start] and [stop] (the screen's started state).
 * [enabled] reports whether location services are on; [refresh] re-reads it (e.g. on resume).
 */
class DeviceLocationSource(context: Context) : LocationSource {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(LocationManager::class.java)
    private val _last = MutableStateFlow<Pair<Double, Double>?>(null)
    override val last: StateFlow<Pair<Double, Double>?> = _last.asStateFlow()
    private val _enabled = MutableStateFlow(isLocationOn())
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var listening = false
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = publish(location)
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
            val providers = manager.allProviders.filter { manager.isProviderEnabled(it) }
            listOfNotNull(fusedProvider(), LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { it in providers }
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.elapsedRealtimeNanos }
                ?.let(::publish)
            // Network first (cheap). Without it, GPS: a BALANCED fused request alone never powers GPS,
            // so on a device with no network provider it would wait for a fix forever.
            val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, fusedProvider())
                .firstOrNull { it != null && it in providers }
                ?: return
            manager.requestLocationUpdates(provider, UPDATE_INTERVAL_MS, UPDATE_DISTANCE_M, listener, Looper.getMainLooper())
            listening = true
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        } catch (e: IllegalArgumentException) {
            // Provider disappeared.
        }
    }

    fun stop() {
        if (!listening) return
        manager?.removeUpdates(listener)
        listening = false
    }

    private fun isLocationOn(): Boolean = manager != null && LocationManagerCompat.isLocationEnabled(manager)

    private fun fusedProvider(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else null

    private fun publish(location: Location) {
        _last.value = location.latitude to location.longitude
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 10_000L
        const val UPDATE_DISTANCE_M = 25f
    }
}
