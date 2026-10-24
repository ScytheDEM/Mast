package au.signalatlas.app.connected

import au.signalatlas.app.data.CalibrationSettings
import au.signalatlas.core.calibration.CellMapStore
import au.signalatlas.core.calibration.CellMapping
import au.signalatlas.core.calibration.CellMatcher
import au.signalatlas.core.calibration.Fix
import au.signalatlas.core.radio.RadioState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Runs the connected-tower feature while the map screen is started: the cell polling ([startRadio]), the info bar's
 * live tower match and, when the calibration switch is on, the sample logger. [stop] (map screen stopped) ends all
 * three, so nothing polls or logs in the background (Review Focus 4).
 *
 * The info bar's matcher reads the learned cell mappings but never writes them ([ReadOnlyCellMap]): only the logger
 * teaches the mapping, so a cell is not counted twice per sample.
 */
class ConnectedController(
    private val radioState: StateFlow<RadioState>,
    private val fixes: StateFlow<Fix?>,
    private val matcher: CellMatcher,
    private val settings: CalibrationSettings,
    private val scope: CoroutineScope,
    private val startRadio: () -> Unit,
    private val stopRadio: () -> Unit,
    private val startLogger: () -> Unit,
    private val stopLogger: () -> Unit,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    /** Every active SIM's state (dual SIM); empty or single-entry = one SIM. */
    private val radioStates: StateFlow<Map<Int, RadioState>> = MutableStateFlow(emptyMap()),
    /** The SIM that carries mobile data; null = unknown (the primary is [radioState]'s). */
    private val dataSubId: StateFlow<Int?> = MutableStateFlow(null),
) {
    private val lock = Any()
    private var foreground = false
    private var trackJob: Job? = null

    private val _ui = MutableStateFlow(ConnectedUi.IDLE)
    val ui: StateFlow<ConnectedUi> = _ui.asStateFlow()

    init {
        settings.enabled.onEach { syncLogger() }.launchIn(scope)
    }

    /** The map screen started. Idempotent. */
    fun start() = synchronized(lock) {
        if (foreground) return
        foreground = true
        startRadio()
        trackJob = scope.launch {
            combine(radioState, fixes, radioStates, dataSubId) { s, f, all, data -> Inputs(s, f, all, data) }.conflate().collect {
                _ui.value = withContext(compute) { buildAll(it) }
            }
        }
        syncLogger()
    }

    /** The map screen stopped. Idempotent. */
    fun stop() = synchronized(lock) {
        if (!foreground) return
        foreground = false
        trackJob?.cancel()
        trackJob = null
        stopRadio()
        stopLogger()
    }

    /** Permission was just granted: restarts the radio so its callbacks register with the new permission. */
    fun onPermissionsChanged() = synchronized(lock) {
        if (!foreground) return
        stopRadio()
        startRadio()
    }

    private fun syncLogger() = synchronized(lock) {
        if (foreground && settings.enabled.value) startLogger() else stopLogger()
    }

    private class Inputs(val state: RadioState, val fix: Fix?, val all: Map<Int, RadioState>, val dataSubId: Int?)

    /** The primary SIM's [ConnectedUi] with a compact one for each further active SIM. */
    private fun buildAll(i: Inputs): ConnectedUi {
        val primary = i.state.sim?.subId
        val others = i.all.values.filter { it.sim != null && it.sim?.subId != primary }
            .sortedBy { it.sim?.subId }
            .map { build(it, i.fix).copy(isData = it.sim?.subId == i.dataSubId) }
        return build(i.state, i.fix).copy(isData = i.dataSubId == null || primary == i.dataSubId || others.isEmpty(), otherSims = others)
    }

    private fun build(state: RadioState, fix: Fix?): ConnectedUi {
        val sim = state.sim?.takeIf { !it.roaming }?.network
        val cell = state.serving
        val match = if (sim != null && cell != null && fix != null) {
            // Display only: a coarse fix still names the nearest plausible tower; storing samples keeps its 50 m rule.
            matcher.match(sim, cell, fix.copy(accuracyM = min(fix.accuracyM, 50.0)))
        } else {
            null
        }
        return connectedUi(state, match, fix)
    }
}

/** Reads another [CellMapStore] and ignores writes. */
class ReadOnlyCellMap(private val source: CellMapStore) : CellMapStore {
    override fun cellMapping(key: String): CellMapping? = source.cellMapping(key)
    override fun putCellMapping(m: CellMapping) = Unit
}
