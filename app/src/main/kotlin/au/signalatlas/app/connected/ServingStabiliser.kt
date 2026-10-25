package au.signalatlas.app.connected

import kotlin.math.roundToInt

/**
 * Calms the info bar. The radio reports carrier aggregation and NSA secondaries coming and going every second or two, so
 * the raw serving band set and 4G/5G flicker; RSRP jitters by a few dB per reading. Per SIM:
 *
 * - **Bands and tech** (hysteresis): a new band set / tech replaces the shown one only once it has been reported for
 *   [holdMs] or in [confirmReadings] consecutive readings. Anything else (status changes, carrier, tower) passes straight
 *   through, and the first reading after a status change is shown at once.
 * - **RSRP**: an exponential moving average (weight [alpha] on the new reading), shown at most once per [rsrpIntervalMs].
 *
 * Only the bar uses this; the details sheet shows the raw cells. Not thread-safe: call [update] from one thread.
 */
class ServingStabiliser(
    private val holdMs: Long = 4_000,
    private val confirmReadings: Int = 3,
    private val alpha: Double = 0.3,
    private val rsrpIntervalMs: Long = 1_000,
) {
    private data class Serving(val bandList: List<String>, val bands: String?, val fiveG: Boolean)

    private class Sim(var shown: Serving) {
        var pending: Serving? = null
        var pendingSince = 0L
        var pendingCount = 0
        var ema: Double? = null
        var shownRsrp: Int? = null
        var rsrpAt = 0L
    }

    private val sims = HashMap<Int, Sim>()

    /** [ui] (the primary SIM, with its [ConnectedUi.otherSims]) as the bar should show it at [nowMs] (monotonic). */
    fun update(ui: ConnectedUi, nowMs: Long): ConnectedUi {
        val seen = HashSet<Int>()
        val primary = sim(ui, 0, nowMs, seen)
        val others = ui.otherSims.mapIndexed { i, o -> sim(o, i + 1, nowMs, seen) }
        sims.keys.retainAll(seen)
        return primary.copy(otherSims = others)
    }

    private fun sim(ui: ConnectedUi, index: Int, now: Long, seen: MutableSet<Int>): ConnectedUi {
        val key = ui.subId ?: -1 - index
        seen += key
        if (ui.status != ConnectedStatus.CONNECTED && ui.status != ConnectedStatus.UNTRACKED) {
            sims.remove(key)
            return ui
        }
        val reading = Serving(ui.bandList, ui.bands, ui.fiveG)
        val s = sims.getOrPut(key) { Sim(reading) }
        when {
            reading == s.shown -> s.pending = null
            reading == s.pending -> {
                s.pendingCount++
                if (s.pendingCount >= confirmReadings || now - s.pendingSince >= holdMs) {
                    s.shown = reading
                    s.pending = null
                }
            }
            else -> {
                s.pending = reading
                s.pendingSince = now
                s.pendingCount = 1
            }
        }
        val raw = ui.rsrpDbm
        if (raw == null) {
            s.ema = null
            s.shownRsrp = null
        } else {
            val ema = s.ema?.let { it + alpha * (raw - it) } ?: raw.toDouble()
            s.ema = ema
            if (s.shownRsrp == null || now - s.rsrpAt >= rsrpIntervalMs) {
                s.shownRsrp = ema.roundToInt()
                s.rsrpAt = now
            }
        }
        return ui.copy(bandList = s.shown.bandList, bands = s.shown.bands, fiveG = s.shown.fiveG, rsrpDbm = s.shownRsrp)
    }
}
