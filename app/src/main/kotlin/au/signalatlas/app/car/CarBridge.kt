package au.signalatlas.app.car

import android.content.Context
import au.signalatlas.app.connected.ConnectedController
import au.signalatlas.app.connected.ConnectedStatus
import au.signalatlas.app.connected.ConnectedUi
import au.signalatlas.app.connected.bearingDeg
import au.signalatlas.car.CarDataSource
import au.signalatlas.car.CarFix
import au.signalatlas.car.CarSim
import au.signalatlas.car.CarSite
import au.signalatlas.car.CarTripState
import au.signalatlas.core.calibration.Fix
import au.signalatlas.core.coverage.CoverageSelection
import au.signalatlas.core.map.StyleLoader
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import au.signalatlas.core.model.TowerFilter
import au.signalatlas.core.radio.RadioState
import au.signalatlas.feature.trip.TripController
import au.signalatlas.feature.trip.TripState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Adapts the phone app's live state to the car module (see [CarDataSource]). */
class CarBridge(
    private val app: Context,
    private val scope: CoroutineScope,
    private val connected: Lazy<ConnectedController>,
    private val radioStates: StateFlow<Map<Int, RadioState>>,
    private val fixes: StateFlow<Fix?>,
    private val startLocation: () -> Unit,
    private val stopLocation: () -> Unit,
    private val controller: TripController,
    private val nearest: (lat: Double, lon: Double) -> List<Pair<Double, Double>>,
    private val basemapKey: suspend () -> String?,
    private val selection: MutableStateFlow<CoverageSelection?>,
    private val tileTemplate: () -> String?,
) : CarDataSource {
    override val sims: StateFlow<List<CarSim>> = MutableStateFlow(emptyList())
    private val simsOut get() = sims as MutableStateFlow<List<CarSim>>

    override val fix: StateFlow<CarFix?> = MutableStateFlow(null)
    private val fixOut get() = fix as MutableStateFlow<CarFix?>

    override val trip: StateFlow<CarTripState> = controller.state
        .map { st -> if (st is TripState.Running) CarTripState(true, st.stats.banner) else CarTripState(false) }
        .stateIn(scope, SharingStarted.Eagerly, CarTripState(false))

    override val alerts: Flow<String> = controller.alerts.map { it.text }

    override val coverageTemplate: Flow<String?> = flow {
        if (selection.value == null) {
            val network = sims.value.firstNotNullOfOrNull { it.network }
            if (network != null) {
                val ours = CoverageSelection(network, Tech.entries.toSet(), null, false, CoverageSelection.View.RSRP)
                selection.value = ours
                carSelection = ours
            }
        }
        emit(tileTemplate())
    }

    @Volatile private var carSelection: CoverageSelection? = null
    private var fixJob: Job? = null
    private var infoJob: Job? = null

    override suspend fun styleJson(dark: Boolean): String? {
        val key = basemapKey() ?: return null
        return StyleLoader.load(app, dark, key)
    }

    override fun sitesNear(lat: Double, lon: Double): List<CarSite> = nearest(lat, lon).map { CarSite(it.first, it.second) }

    override fun setActive(active: Boolean) {
        if (active) {
            startLocation()
            // Off the main thread and only while the car is shown: the first use opens calibration.db.
            infoJob = scope.launch(Dispatchers.Default) {
                val controller = connected.value
                controller.start()
                combine(controller.ui, radioStates, fixes) { ui, states, _ ->
                    ui.allSims.mapIndexed { i, s -> carSim(i + 1, s, states[s.subId]) }
                }.collect { simsOut.value = it }
            }
            fixJob = scope.launch {
                var prev: CarFix? = null
                fixes.filterNotNull().collect { f ->
                    val p = prev
                    val moved = p != null && distance(p.lat, p.lon, f.lat, f.lon) > MIN_MOVE_M
                    val heading = if (moved) bearingDeg(p!!.lat, p.lon, f.lat, f.lon) else p?.headingDeg
                    val dt = if (p != null) 2.0 else null
                    val speed = if (moved && dt != null) distance(p!!.lat, p.lon, f.lat, f.lon) / dt else p?.speedMps
                    // Keep the previous anchor until the car has moved, so the heading is not noise at a standstill.
                    prev = if (moved || p == null) CarFix(f.lat, f.lon, heading, speed) else p
                    fixOut.value = CarFix(f.lat, f.lon, heading, speed)
                }
            }
        } else {
            fixJob?.cancel(); fixJob = null
            infoJob?.cancel(); infoJob = null
            if (connected.isInitialized()) connected.value.stop()
            stopLocation()
            carSelection?.let { ours -> if (selection.value == ours) selection.value = null }
            carSelection = null
        }
    }

    override fun startTrip() { runCatching { controller.start() } }
    override fun stopTrip() { controller.stop() }

    private fun carSim(slot: Int, ui: ConnectedUi, state: RadioState?): CarSim {
        val note = when (ui.status) {
            ConnectedStatus.NEEDS_PHONE -> "Allow phone access in Signal Atlas"
            ConnectedStatus.NEEDS_LOCATION -> "Allow location in Signal Atlas"
            ConnectedStatus.AIRPLANE -> "Airplane mode"
            ConnectedStatus.NO_SIM -> "No SIM"
            ConnectedStatus.ROAMING -> "Roaming"
            ConnectedStatus.UNTRACKED -> "Network not tracked"
            ConnectedStatus.SEARCHING -> "Searching…"
            ConnectedStatus.CONNECTED -> null
        }
        val serving = ui.highlight?.serving
        return CarSim(
            subId = ui.subId ?: slot, slot = slot,
            carrier = ui.carrier ?: ui.network?.let(::networkName) ?: "SIM $slot",
            network = ui.network, label = state?.displayLabel, band = ui.bandList.firstOrNull(), ca = state?.ca,
            rsrpDbm = ui.rsrpDbm, sinrDb = ui.cells?.serving?.sinr,
            towerDistanceM = ui.tower?.distanceM, towerBearingDeg = ui.tower?.bearingDeg,
            towerLat = serving?.first, towerLon = serving?.second, isData = ui.isData,
            neighbours = ui.cells?.neighbours?.size ?: 0, predictedDbm = ui.predictedDbm, note = note,
        )
    }

    private fun networkName(n: Network) = when (n) {
        Network.TELSTRA -> "Telstra"
        Network.OPTUS -> "Optus"
        Network.TPG -> "Vodafone"
        Network.OPTUS_TPG_JV -> "Optus / TPG"
        Network.NBN -> "NBN"
    }

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, r)
        return r[0].toDouble()
    }

    private companion object {
        const val MIN_MOVE_M = 8.0
    }
}
