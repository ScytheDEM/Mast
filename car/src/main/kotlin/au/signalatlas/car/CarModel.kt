package au.signalatlas.car

import au.signalatlas.core.model.Network
import au.signalatlas.core.radio.CarrierAggregation
import au.signalatlas.core.radio.DisplayLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** One active SIM as the car shows it. [slot] is 1-based in the order the phone lists the SIMs. */
data class CarSim(
    val subId: Int,
    val slot: Int,
    val carrier: String,
    val network: Network?,
    val label: DisplayLabel?,
    val band: String?,
    val ca: CarrierAggregation?,
    val rsrpDbm: Int?,
    val sinrDb: Int?,
    val towerDistanceM: Double?,
    val towerBearingDeg: Double?,
    val towerLat: Double?,
    val towerLon: Double?,
    val isData: Boolean,
    val neighbours: Int = 0,
    /** What the tower prediction says the signal is here, dBm. */
    val predictedDbm: Int? = null,
    /** Why there is no signal to show ("No SIM", "Airplane mode", "Roaming"…); null when connected. */
    val note: String? = null,
)

data class CarFix(val lat: Double, val lon: Double, val headingDeg: Double?, val speedMps: Double?)

data class CarSite(val lat: Double, val lon: Double)

data class CarTripState(val running: Boolean, val banner: String? = null)

/** What the phone app gives the car module; implemented in :app and installed in [CarRuntime]. */
interface CarDataSource {
    val sims: StateFlow<List<CarSim>>
    val fix: StateFlow<CarFix?>
    val trip: StateFlow<CarTripState>

    /** Trip alert texts, e.g. "Leaving Telstra coverage in 1.8 km · dead zone 6 km". */
    val alerts: Flow<String>

    /** The tile URL template of the coverage overlay; null when there is none to show. */
    val coverageTemplate: Flow<String?>

    /** The clean map style with its basemap filled in; null when no basemap is available (offline, never fetched). */
    suspend fun styleJson(dark: Boolean): String?

    /** Towers near a point, for the map's dots. */
    fun sitesNear(lat: Double, lon: Double): List<CarSite>

    /** The car UI is visible: start (true) or stop (false) the radio and location reading. */
    fun setActive(active: Boolean)

    fun startTrip()
    fun stopTrip()
}

/** Where the car service finds the phone app's data (the app container installs it; this module cannot see :app). */
object CarRuntime {
    @Volatile var source: CarDataSource? = null
}
