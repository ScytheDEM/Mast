package au.signalatlas.core.calibration

import au.signalatlas.core.radio.Permission
import au.signalatlas.core.radio.RadioState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Rule 3: turns location fixes plus the live radio state into stored samples, while started. The app starts it when
 * it comes to the foreground (and calibration is on and permitted) and stops it in the background; nothing is logged
 * otherwise (trip-mode logging is M5).
 *
 * For each fix: the radio state must be permitted, not in airplane mode, on a known non-roaming SIM network, with a
 * registered serving cell reporting RSRP, and fresh (updated within [MAX_RADIO_AGE_MS] of now); the fix accuracy must
 * be <= 50 m; and the fix must be >= 50 m and >= 5 s from the last stored sample. Then the cell is matched
 * ([CellMatcher]) and stored through [sink] only when the match is Likely or Confirmed.
 */
class SampleLogger(
    private val radio: StateFlow<RadioState>,
    private val fixes: Flow<Fix>,
    private val matcher: CellMatcher,
    private val sink: (Sample) -> Unit,
    private val scope: CoroutineScope,
    /** The clock of [RadioState.updatedAtElapsedMs]. */
    private val elapsedNowMs: () -> Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val lock = Any()
    private var job: Job? = null
    @Volatile private var lastStored: Fix? = null

    val isRunning: Boolean get() = synchronized(lock) { job?.isActive == true }

    /** Starts collecting fixes (idempotent). */
    fun start() = synchronized(lock) {
        if (job?.isActive == true) return
        job = scope.launch(dispatcher) { fixes.collect { process(it, radio.value) } }
    }

    /** Stops logging (idempotent); a fix being processed may still be stored. */
    fun stop() = synchronized(lock) {
        job?.cancel()
        job = null
    }

    /** One fix against [state]; the stored sample, or null. Blocking (matching queries towers and the engine). */
    internal fun process(fix: Fix, state: RadioState): Sample? {
        if (state.permission != Permission.GRANTED || state.airplane) return null
        val sim = state.sim?.takeIf { !it.roaming } ?: return null
        val network = sim.network ?: return null
        val cell = state.serving?.takeIf { it.registered } ?: return null
        val rsrp = cell.rsrp ?: return null
        if (elapsedNowMs() - state.updatedAtElapsedMs > MAX_RADIO_AGE_MS) return null
        if (fix.deadReckoned || !(fix.accuracyM <= CalibrationMath.MAX_FIX_ACCURACY_M)) return null
        if (!CalibrationMath.throttleOk(lastStored, fix)) return null
        val m = matcher.match(network, cell, fix) ?: return null
        if (m.confidence == Confidence.UNCERTAIN) return null
        val c = m.candidate
        val sample = Sample(
            fix.timeMs, fix.lat, fix.lon, fix.accuracyM, network, cell.tech, cell.channel, cell.pci, cell.cellId,
            cell.taMeters, rsrp, cell.rsrq, cell.sinr, c.sddId, c.sectorId, c.ident, c.network, c.siteLat, c.siteLon,
            c.predictedDbm, m.distanceM, m.area, m.confidence,
        )
        sink(sample)
        lastStored = fix
        return sample
    }

    companion object {
        /** A radio state older than this is not paired with a fix (the 2 s cell polling has stalled). */
        const val MAX_RADIO_AGE_MS = 10_000L
    }
}
