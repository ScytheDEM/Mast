package au.signalatlas.feature.trip

import au.signalatlas.core.beacons.BeaconLearner
import au.signalatlas.core.beacons.BeaconScanner
import au.signalatlas.core.beacons.BeaconSnapper
import au.signalatlas.core.beacons.BeaconSighting
import au.signalatlas.core.calibration.Fix
import au.signalatlas.core.coverage.CoverageSelection
import au.signalatlas.core.coverage.PointPrediction
import au.signalatlas.core.model.LatLon
import au.signalatlas.core.roads.RoadGraphProvider
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import au.signalatlas.core.radio.ObservedCell
import au.signalatlas.core.radio.Permission
import au.signalatlas.core.radio.RadioState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One location fix as the trip service receives it ([speedMps] null = the provider reported none). */
data class TripFix(val timeMs: Long, val lat: Double, val lon: Double, val accuracyM: Double, val speedMps: Double?)

/** The part of the coverage service the trip needs ([au.signalatlas.core.coverage.CoverageTileService.predictAlong]). */
fun interface PathPredictor {
    suspend fun predict(points: List<LatLon>, selection: CoverageSelection, thresholdDbm: Float): List<PointPrediction>
}

/** What the controller asks of the app: the radio and sample logger it shares with the map, fixes for the logger, the service. */
class TripHooks(
    val startRadio: () -> Unit,
    val stopRadio: () -> Unit,
    val startLogger: () -> Unit,
    val stopLogger: () -> Unit,
    /** A fix for the sample logger (calibration keeps logging while driving). */
    val publishFix: (Fix) -> Unit,
    /** Starts the foreground service; called from the UI (foreground) only. */
    val launchService: () -> Unit,
    val haltService: () -> Unit,
)

data class TripStats(
    val startedAtMs: Long,
    val distanceM: Double = 0.0,
    val network: String? = null,
    val tech: String? = null,
    val status: TripStatus? = null,
    val alertCount: Int = 0,
    val stationary: Boolean = false,
    /** Why there is no status yet: "Waiting for GPS…", "Choose a network on the map". */
    val message: String? = null,
    /** The position is dead-reckoned along a tunnel road (GPS lost); the map shows the "estimated" dot. */
    val estimated: Boolean = false,
    /** Map-matched to a tunnel road (or GPS lost just after matching onto one). */
    val inTunnel: Boolean = false,
) {
    /** "Trip · Telstra 4G · next dead zone in 6.2 km", "Trip · Telstra 4G · No dead zones ahead". */
    val banner: String
        get() {
            val label = listOfNotNull(network, tech).joinToString(" · ").ifEmpty { null }
            val detail = status?.text?.let { if (status.covered && status.nextDeadM != null) it.replaceFirstChar(Char::lowercase) else it }
                ?: message
            return listOfNotNull("Trip", label, detail).joinToString(" · ")
        }
}

sealed interface TripState {
    data object Idle : TripState
    data class Running(val stats: TripStats) : TripState
}

/** BLE beacon positioning for tunnels: the scanner, the learned/imported positions and the "use beacons" setting. */
class BeaconSupport(
    val enabled: StateFlow<Boolean>,
    val scanner: BeaconScanner,
    val snapper: BeaconSnapper,
    val learner: BeaconLearner,
)

/** Stationary after [afterMs] of GPS speed under [SPEED_MPS]; moving again as soon as a fix is faster. */
class StationaryDetector(private val afterMs: Long = 60_000L) {
    private var slowSince: Long? = null

    fun onSpeed(timeMs: Long, speedMps: Double): Boolean {
        if (speedMps >= SPEED_MPS) {
            slowSince = null
            return false
        }
        val since = slowSince ?: timeMs.also { slowSince = it }
        return timeMs - since >= afterMs
    }

    fun reset() {
        slowSince = null
    }

    companion object {
        const val SPEED_MPS = 1.0
    }
}

/**
 * The trip: while running, the location service feeds [onLocation]; every [evalIntervalMs] the controller looks ahead
 * along the track, asks the coverage service for the predictions, runs the [AlertEngine] and publishes the status
 * ([state]) and the alerts ([alerts]) for the service to deliver. Evaluation runs on [scope] (a background dispatcher),
 * never on the main thread. Starting runs the radio monitor and the calibration sample logger; stopping stops both.
 */
class TripController(
    private val scope: CoroutineScope,
    private val predictor: PathPredictor,
    private val radioState: StateFlow<RadioState>,
    private val radioStates: StateFlow<Map<Int, RadioState>>,
    private val dataSubId: StateFlow<Int?>,
    private val settings: TripSettings,
    /** The network of the map's coverage selection, for a phone whose SIM network is unknown. */
    private val fallbackNetwork: () -> Network?,
    private val hooks: TripHooks,
    private val clockMs: () -> Long = System::currentTimeMillis,
    /** The clock of [RadioState.updatedAtElapsedMs]. */
    private val elapsedMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val evalIntervalMs: Long = EVAL_INTERVAL_MS,
    /** Roads around the vehicle for the look-ahead to follow; null (or not yet loaded) means the arc extrapolation. */
    private val roads: RoadGraphProvider? = null,
    /** Beacon positioning in tunnels; null = off. */
    private val beacons: BeaconSupport? = null,
    private val deadReckonIntervalMs: Long = DEAD_RECKON_INTERVAL_MS,
) {
    private val lock = Any()
    private val _state = MutableStateFlow<TripState>(TripState.Idle)
    val state: StateFlow<TripState> = _state.asStateFlow()

    private val _stationary = MutableStateFlow(false)
    /** Whether the service should ask for fixes every 5 s instead of every second. */
    val stationary: StateFlow<Boolean> = _stationary.asStateFlow()

    private val _alerts = MutableSharedFlow<Alert>(extraBufferCapacity = 16)
    val alerts: SharedFlow<Alert> = _alerts.asSharedFlow()

    private val _tunnel = MutableStateFlow(false)

    /** Whether the vehicle is on a tunnel road (the DAS "tunnel coverage" rule). False outside a trip. */
    val tunnel: StateFlow<Boolean> = _tunnel.asStateFlow()

    private val positioning = TunnelPositioning()
    private var drLoop: Job? = null
    private var lastRealFix: TripFix? = null

    private val detector = StationaryDetector()
    private val track = ArrayDeque<TrackFix>()
    private var last: TripFix? = null
    private var odometerM = 0.0
    private var loop: Job? = null
    private var alertCount = 0
    private var startedAtMs = 0L
    private var engineInstance = AlertEngine { AlertConfig(settings.config.value) }

    val isRunning: Boolean get() = _state.value is TripState.Running

    /** Starts the trip (map button or Quick Settings tile tap: the app is in the foreground). Idempotent. */
    fun start() {
        synchronized(lock) {
            if (isRunning) return
            track.clear()
            last = null
            odometerM = 0.0
            alertCount = 0
            detector.reset()
            positioning.reset()
            lastRealFix = null
            _tunnel.value = false
            _stationary.value = false
            startedAtMs = clockMs()
            engineInstance = AlertEngine { AlertConfig(settings.config.value) }
            _state.value = TripState.Running(TripStats(startedAtMs, message = WAITING_GPS))
            hooks.startRadio()
            hooks.startLogger()
            loop = scope.launch {
                while (isActive) {
                    evaluateOnce()
                    delay(evalIntervalMs)
                }
            }
            drLoop = scope.launch {
                while (isActive) {
                    delay(deadReckonIntervalMs)
                    deadReckonOnce()
                }
            }
        }
        hooks.launchService()
    }

    /** Stops the trip and everything it started. Idempotent. */
    fun stop() {
        if (!end()) return
        hooks.haltService()
    }

    /** The service went away (destroyed by the system or stopped): end the trip without stopping the service again. */
    fun onServiceGone() {
        end()
    }

    private fun end(): Boolean = synchronized(lock) {
        if (!isRunning) return false
        loop?.cancel()
        loop = null
        drLoop?.cancel()
        drLoop = null
        beacons?.scanner?.stop()
        beacons?.snapper?.clear()
        _tunnel.value = false
        hooks.stopLogger()
        hooks.stopRadio()
        _stationary.value = false
        _state.value = TripState.Idle
        true
    }

    /** A fix from the location service. */
    fun onLocation(fix: TripFix) {
        synchronized(lock) {
            if (!isRunning) return
            val graph = roads?.graphNear(fix.lat, fix.lon, clockMs())
            val heading = LookAhead.project(track.toList(), 0.0).headingDeg
            val use = positioning.onFix(fix, graph, heading, derivedSpeed())
            lastRealFix = fix
            _tunnel.value = positioning.inTunnel(fix.timeMs)
            if (!use) return // a poor fix in a tunnel: the estimate carries on
            last?.let { prev ->
                val d = distanceM(prev.lat, prev.lon, fix.lat, fix.lon)
                if (d >= MIN_STEP_M && fix.accuracyM <= MAX_ODOMETER_ACCURACY_M) odometerM += d
            }
            last = fix
            track.addLast(TrackFix(fix.timeMs, fix.lat, fix.lon))
            while (track.isNotEmpty() && fix.timeMs - track.first().timeMs > TRACK_KEEP_MS) track.removeFirst()
            val speed = fix.speedMps ?: derivedSpeed()
            _stationary.value = detector.onSpeed(fix.timeMs, speed)
        }
        hooks.publishFix(Fix(fix.lat, fix.lon, fix.accuracyM, fix.timeMs))
    }

    /** One dead-reckoning step (internal for tests): publishes an estimated position when GPS is lost in a tunnel. Never throws. */
    internal fun deadReckonOnce() {
        try {
            val now = clockMs()
            val fix: TripFix
            synchronized(lock) {
                if (!isRunning) return
                val ref = last ?: return
                val graph = roads?.graphNear(ref.lat, ref.lon, now)
                updateScanning(graph, ref)
                val target = beacons?.snapper?.target(elapsedMs()) { p ->
                    graph?.nearestOnRoad(p.lat, p.lon, BEACON_PROJECT_M, tunnelOnly = true)?.first
                }
                val est = positioning.tick(now, graph, target)
                _tunnel.value = positioning.inTunnel(now)
                if (est == null) return
                fix = est
                last?.let { prev ->
                    val d = distanceM(prev.lat, prev.lon, fix.lat, fix.lon)
                    if (d >= MIN_STEP_M) odometerM += d
                }
                last = fix
                track.addLast(TrackFix(fix.timeMs, fix.lat, fix.lon))
                while (track.isNotEmpty() && fix.timeMs - track.first().timeMs > TRACK_KEEP_MS) track.removeFirst()
            }
            hooks.publishFix(Fix(fix.lat, fix.lon, fix.accuracyM, fix.timeMs, deadReckoned = true))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Positioning is best-effort; the next tick tries again.
        }
    }

    /** BLE scanning runs only with the setting on, during a trip, within [BEACON_NEAR_TUNNEL_M] of a tunnel road. */
    private fun updateScanning(graph: au.signalatlas.core.roads.RoadGraph?, at: TripFix) {
        val b = beacons ?: return
        val want = isRunning && b.enabled.value && graph?.tunnelDistanceM(at.lat, at.lon, BEACON_NEAR_TUNNEL_M) != null
        if (want && !b.scanner.scanning) {
            b.scanner.start { s -> onBeacon(s) }
        } else if (!want && b.scanner.scanning) {
            b.scanner.stop()
            b.snapper.clear()
        }
    }

    private fun onBeacon(s: BeaconSighting) {
        val b = beacons ?: return
        b.snapper.onSighting(s)
        val fix = synchronized(lock) { lastRealFix?.takeIf { clockMs() - it.timeMs <= BEACON_FIX_AGE_MS } } ?: return
        b.learner.learn(s, LatLon(fix.lat, fix.lon), fix.accuracyM)
    }


    private fun derivedSpeed(): Double = LookAhead.project(track.toList(), 0.0).speedMps

    /** One evaluation (internal for tests). Never throws. */
    internal suspend fun evaluateOnce() {
        val fix: TripFix
        val trackNow: List<TrackFix>
        val odo: Double
        val eng: AlertEngine
        synchronized(lock) {
            if (!isRunning) return
            fix = last ?: run { publish(message = WAITING_GPS); return }
            trackNow = track.toList()
            odo = odometerM
            eng = engineInstance
        }
        if (clockMs() - fix.timeMs > STALE_FIX_MS) {
            publish(message = WAITING_GPS)
            return
        }
        try {
            val cfg = settings.config.value
            val sim = pickState()
            val network = sim?.sim?.takeIf { !it.roaming }?.network ?: fallbackNetwork()
            if (network == null) {
                publish(message = CHOOSE_NETWORK)
                return
            }
            val speed = fix.speedMps ?: LookAhead.project(trackNow, 0.0).speedMps
            val path = LookAhead.project(trackNow, AlertEngine.horizonM(speed, cfg.windowSeconds), roads?.graphNear(fix.lat, fix.lon, clockMs()))
            val points = path.points.ifEmpty { listOf(LatLon(fix.lat, fix.lon)) }
            val alertCfg = AlertConfig(cfg)
            val selection = CoverageSelection(
                network, if (cfg.floor == TechFloor.LOSE_5G) setOf(Tech.NR) else setOf(Tech.LTE, Tech.NR), null, false,
                CoverageSelection.View.RSRP,
            )
            val predictions = predictor.predict(points, selection, alertCfg.thresholdDbm.toFloat())
            val measured = measuredRsrp(cfg.floor, sim)
            val tech = techLabel(cfg.floor, predictions.firstOrNull(), sim)
            val result = eng.evaluate(
                AlertInput(clockMs(), odo, speed, networkName(network), tech, measured, predictions.map { it.probability }),
            )
            result.alerts.forEach { _alerts.tryEmit(it) }
            synchronized(lock) {
                if (!isRunning) return
                alertCount += result.alerts.size
                _state.value = TripState.Running(
                    TripStats(
                        startedAtMs, odometerM, networkName(network), floorLabel(cfg.floor), result.status, alertCount, _stationary.value,
                        estimated = positioning.active, inTunnel = _tunnel.value,
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            publish(message = "Coverage unavailable")
        }
    }

    private fun publish(message: String) {
        synchronized(lock) {
            if (!isRunning) return
            _state.value = TripState.Running(
                TripStats(
                    startedAtMs, odometerM, alertCount = alertCount, stationary = _stationary.value, message = message,
                    estimated = positioning.active, inTunnel = _tunnel.value,
                ),
            )
        }
    }

    /** The data SIM's state in a dual-SIM phone, else the primary's. */
    private fun pickState(): RadioState? {
        val data = dataSubId.value
        return (data?.let { radioStates.value[it] } ?: radioState.value).takeIf { it.permission == Permission.GRANTED || it.sim != null }
    }

    private fun measuredRsrp(floor: TechFloor, st: RadioState?): Int? {
        st ?: return null
        if (st.permission != Permission.GRANTED || st.airplane || elapsedMs() - st.updatedAtElapsedMs > MAX_RADIO_AGE_MS) return null
        val cells: List<ObservedCell> = (listOfNotNull(st.serving) + st.nrSecondaries).filter { it.rsrp != null }
        return if (floor == TechFloor.LOSE_5G) {
            cells.filter { it.tech == Tech.NR }.maxOfOrNull { it.rsrp!! }
        } else {
            st.serving?.takeIf { it.registered }?.rsrp ?: cells.filter { it.registered }.maxOfOrNull { it.rsrp!! }
        }
    }

    private fun techLabel(floor: TechFloor, first: PointPrediction?, st: RadioState?): String = when {
        floor == TechFloor.LOSE_5G -> "5G"
        first?.bestTech == Tech.NR -> "5G"
        first?.bestTech == Tech.LTE -> "4G"
        st?.serving?.tech == Tech.NR -> "5G"
        else -> "4G"
    }

    companion object {
        const val EVAL_INTERVAL_MS = 2_000L
        const val DEAD_RECKON_INTERVAL_MS = 1_000L
        /** BLE scanning only within this distance of a tunnel road. */
        const val BEACON_NEAR_TUNNEL_M = 500.0
        private const val BEACON_PROJECT_M = 60.0
        private const val BEACON_FIX_AGE_MS = 3_000L
        const val WAITING_GPS = "Waiting for GPS…"
        const val CHOOSE_NETWORK = "Choose a network on the map"
        private const val STALE_FIX_MS = 15_000L
        private const val TRACK_KEEP_MS = 45_000L
        private const val MIN_STEP_M = 3.0
        private const val MAX_ODOMETER_ACCURACY_M = 100.0
        private const val MAX_RADIO_AGE_MS = 10_000L

        /** The tech floor as shown in titles: "all data", "4G+", "5G". */
        fun floorLabel(f: TechFloor): String = when (f) {
            TechFloor.LOSE_5G -> "5G"
            TechFloor.LOSE_4G -> "4G+"
            TechFloor.LOSE_ALL -> "all data"
        }

        fun networkName(n: Network): String = when (n) {
            Network.TELSTRA -> "Telstra"
            Network.OPTUS -> "Optus"
            Network.TPG -> "TPG"
            Network.OPTUS_TPG_JV -> "Optus/TPG"
            Network.NBN -> "NBN"
        }

        internal fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
            return 2 * 6_371_000.0 * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}

/** Where the service finds the controller (set by the app container; feature modules cannot see the app). */
object TripRuntime {
    @Volatile var controller: TripController? = null
    @Volatile var voiceEnabled: () -> Boolean = { false }
}
