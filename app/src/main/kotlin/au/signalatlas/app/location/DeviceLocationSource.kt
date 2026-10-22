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
import au.signalatlas.app.data.LocationSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Location through the platform [LocationManager] only (no Play Services): the freshest last
 * known fix from the fused, GPS and network providers, then network-provider updates between
 * [start] and [stop] (the screen's started state).
 */
class DeviceLocationSource(context: Context) : LocationSource {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(LocationManager::class.java)
    private val _last = MutableStateFlow<Pair<Double, Double>?>(null)
    override val last: StateFlow<Pair<Double, Double>?> = _last.asStateFlow()

    private var listening = false
    private val listener = LocationListener { publish(it) }

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Idempotent; does nothing without a location permission. Main thread. */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    fun start() {
        if (listening || manager == null || !hasPermission()) return
        try {
            lastKnownProviders().mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.elapsedRealtimeNanos }
                ?.let(::publish)
            val provider = listOf(LocationManager.NETWORK_PROVIDER, fusedProvider(), LocationManager.GPS_PROVIDER)
                .firstOrNull { it != null && it in manager.allProviders }
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

    private fun lastKnownProviders(): List<String> =
        listOfNotNull(fusedProvider(), LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { it in manager!!.allProviders }

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
