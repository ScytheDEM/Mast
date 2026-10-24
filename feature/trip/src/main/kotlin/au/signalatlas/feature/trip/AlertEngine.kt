package au.signalatlas.feature.trip

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class AlertKind { LEAVING, EARLY, BACK }

/** One alert to deliver. [distanceM] and [deadLenM] are the unrounded values the text was made from (voice reuses them). */
data class Alert(val kind: AlertKind, val text: String, val distanceM: Double? = null, val deadLenM: Double? = null)

/** What the engine knows after one evaluation, for the ongoing notification and the trip banner. */
data class TripStatus(
    /** Covered here (P >= 0.5, or the measured RSRP is above the threshold by 3 dB). */
    val covered: Boolean,
    /** While covered: start of the next qualifying dead stretch ahead, and its length (>= the path's end if cut off). */
    val nextDeadM: Double?,
    val nextDeadLenM: Double?,
    /** While in a dead stretch: where a covered stretch of at least 300 m starts, within 10 km. */
    val returnsInM: Double?,
    /** P(coverage) every 50 m ahead (index 0 = here), for the coverage strip. */
    val ahead: List<Float>,
    val windowM: Double,
) {
    /** "Next dead zone in 6.2 km", "No dead zones ahead", "Coverage returns in ~1.2 km" or "No coverage ahead". */
    val text: String
        get() = when {
            covered -> nextDeadM?.let { "Next dead zone in ${TripText.distance(it)}" } ?: "No dead zones ahead"
            else -> returnsInM?.let { "Coverage returns in ~${TripText.distance(it)}" } ?: "No coverage ahead"
        }

    /** The status-bar chip text: the distance to the next change, or null when there is none. */
    val shortText: String?
        get() = (if (covered) nextDeadM else returnsInM)?.let { TripText.distance(it) }

    companion object {
        val NONE = TripStatus(true, null, null, null, emptyList(), 0.0)
    }
}

data class AlertInput(
    val nowMs: Long,
    /** Distance driven since the trip started (m): hysteresis and "back in coverage" are in these units. */
    val odometerM: Double,
    val speedMps: Double,
    /** For the text: "Telstra", and the tech label "4G" or "5G". */
    val network: String,
    val tech: String,
    /** Measured serving RSRP of the relevant cells (NR only for "lose 5G"); null = none / stale. */
    val measuredRsrp: Int?,
    /** P(coverage) every [AlertEngine.STEP_M] m along the look-ahead, index 0 = here. */
    val probabilities: List<Float>,
)

class AlertResult(val alerts: List<Alert>, val status: TripStatus)

/** Distances in the alert texts. */
object TripText {
    /** < 1 km: 100 m steps (at least 100 m); else 0.1 km. */
    fun distance(m: Double): String {
        val tenths = (m / 100).roundToInt()
        return if (tenths < 10) "${max(1, tenths) * 100} m" else "${tenths / 10}.${tenths % 10} km"
    }

    /** For speech: "1.8 kilometres", "800 metres", "1 kilometre". */
    fun spoken(m: Double): String {
        val tenths = (m / 100).roundToInt()
        if (tenths < 10) return "${max(1, tenths) * 100} metres"
        return if (tenths == 10) "1 kilometre" else if (tenths % 10 == 0) "${tenths / 10} kilometres" else "${tenths / 10}.${tenths % 10} kilometres"
    }
}

/**
 * Plan "Alert rules" 1-8, pure Kotlin: call [evaluate] every 2 s with the look-ahead's coverage probabilities. Holds
 * the hysteresis state (alerted stretches, rate limits, the early trigger's arming).
 */
class AlertEngine(private val config: () -> AlertConfig = { AlertConfig() }) {
    private val lastFiredMs = HashMap<AlertKind, Long>()
    private val alertedStartsOdo = ArrayList<Double>()
    private val history = ArrayDeque<Pair<Long, Int>>()

    /** A "Leaving" alert has been given and the dead stretch has not been entered yet; valid until [pendingUntilOdo]. */
    private var pending = false
    private var pendingUntilOdo = 0.0
    /** We entered a dead stretch we had alerted about; "Back in coverage" is due. */
    private var deadAlerted = false
    private var coveredSinceOdo: Double? = null
    private var earlyArmed = true

    fun evaluate(i: AlertInput): AlertResult {
        val cfg = config()
        val thr = cfg.thresholdDbm
        val windowM = windowM(i.speedMps, cfg.windowSeconds)
        val p = i.probabilities
        val covered = (p.firstOrNull() ?: 0f) >= COVERED_P || (i.measuredRsrp != null && i.measuredRsrp >= thr + COVERED_MARGIN_DB)

        i.measuredRsrp?.let { history.addLast(i.nowMs to it) }
        while (history.isNotEmpty() && i.nowMs - history.first().first > EARLY_WINDOW_MS) history.removeFirst()

        val stretches = deadStretches(p, cfg.minDeadM)
        val alerts = ArrayList<Alert>()
        alertedStartsOdo.removeAll { it < i.odometerM - FORGET_BEHIND_M }

        if (covered) {
            if (pending && i.odometerM > pendingUntilOdo) pending = false

            // Rule 4: leaving coverage. Not again while we are in a dead stretch we already alerted about (a brief
            // covered patch inside it is not "back in coverage" until rule 6 says so).
            val first = stretches.firstOrNull()
            if (first != null && !deadAlerted && first.startM <= windowM && !isAlerted(i.odometerM + first.startM) && rateOk(AlertKind.LEAVING, i.nowMs)) {
                val text = "Leaving ${i.network} ${i.tech} coverage in ~${TripText.distance(first.startM)} · dead zone ~${TripText.distance(first.lenM)}"
                alerts += Alert(AlertKind.LEAVING, text, first.startM, first.lenM)
                fire(AlertKind.LEAVING, i.nowMs)
                alertedStartsOdo += i.odometerM + first.startM
                pending = true
                pendingUntilOdo = i.odometerM + first.startM + HYSTERESIS_M
            }

            // Rule 5: early trigger on a fast drop of the measured signal.
            val rsrp = i.measuredRsrp
            if (rsrp != null) {
                if (rsrp >= thr + EARLY_REARM_DB) earlyArmed = true
                val drop = (history.maxOfOrNull { it.second } ?: rsrp) - rsrp
                val recentLeaving = lastFiredMs[AlertKind.LEAVING]?.let { i.nowMs - it < MIN_REPEAT_MS } == true
                if (earlyArmed && drop >= EARLY_DROP_DB && rsrp <= thr + EARLY_NEAR_DB && !recentLeaving && rateOk(AlertKind.EARLY, i.nowMs)) {
                    alerts += Alert(AlertKind.EARLY, "Signal dropping fast")
                    fire(AlertKind.EARLY, i.nowMs)
                    earlyArmed = false
                    pending = true
                    pendingUntilOdo = i.odometerM + max(windowM, ARC_FORGET_M) + HYSTERESIS_M
                }
            }

            // Rule 6: back in coverage after 200 m.
            if (deadAlerted) {
                val since = coveredSinceOdo ?: i.odometerM.also { coveredSinceOdo = it }
                if (i.odometerM - since >= BACK_AFTER_M && rateOk(AlertKind.BACK, i.nowMs)) {
                    alerts += Alert(AlertKind.BACK, "Back in coverage")
                    fire(AlertKind.BACK, i.nowMs)
                    deadAlerted = false
                    coveredSinceOdo = null
                    earlyArmed = true
                }
            } else {
                coveredSinceOdo = null
            }
        } else {
            coveredSinceOdo = null
            if (pending) {
                deadAlerted = true
                pending = false
            }
        }

        val status = if (covered) {
            val next = stretches.firstOrNull()
            TripStatus(true, next?.startM, next?.lenM, null, p, windowM)
        } else {
            TripStatus(false, null, null, returnsInM(p), p, windowM)
        }
        return AlertResult(alerts, status)
    }

    private fun isAlerted(startOdo: Double) = alertedStartsOdo.any { kotlin.math.abs(it - startOdo) <= HYSTERESIS_M }

    private fun rateOk(kind: AlertKind, nowMs: Long) = lastFiredMs[kind]?.let { nowMs - it >= MIN_REPEAT_MS } != false

    private fun fire(kind: AlertKind, nowMs: Long) {
        lastFiredMs[kind] = nowMs
    }

    private class Stretch(val startM: Double, val lenM: Double)

    /** Rule 3: runs of P < 0.5 at least [minM] long. A run cut off by the path's end counts with the length seen. */
    private fun deadStretches(p: List<Float>, minM: Double): List<Stretch> {
        val out = ArrayList<Stretch>()
        var i = 0
        while (i < p.size) {
            if (p[i] < COVERED_P) {
                var j = i
                while (j < p.size && p[j] < COVERED_P) j++
                val len = (j - i) * STEP_M
                if (len >= minM) out += Stretch(i * STEP_M, len)
                i = j
            } else {
                i++
            }
        }
        return out
    }

    /** Rule 8: where a covered stretch of at least [RETURN_MIN_M] starts, within [RETURN_HORIZON_M]. */
    private fun returnsInM(p: List<Float>): Double? {
        var i = 0
        while (i < p.size) {
            if (p[i] >= COVERED_P) {
                var j = i
                while (j < p.size && p[j] >= COVERED_P) j++
                if ((j - i) * STEP_M >= RETURN_MIN_M && i * STEP_M <= RETURN_HORIZON_M) return i * STEP_M
                i = j
            } else {
                i++
            }
        }
        return null
    }

    companion object {
        const val STEP_M = 50.0
        const val COVERED_P = 0.5f
        const val COVERED_MARGIN_DB = 3
        const val HYSTERESIS_M = 300.0
        const val MIN_REPEAT_MS = 60_000L
        const val EARLY_WINDOW_MS = 30_000L
        const val EARLY_DROP_DB = 10
        const val EARLY_NEAR_DB = 6
        const val EARLY_REARM_DB = 10
        const val BACK_AFTER_M = 200.0
        const val RETURN_MIN_M = 300.0
        const val RETURN_HORIZON_M = 10_000.0
        /** How far past the sampled stretch the "Leaving" bookkeeping stays relevant. */
        private const val FORGET_BEHIND_M = 2_000.0
        private const val ARC_FORGET_M = 1_000.0
        const val MIN_WINDOW_M = 500.0
        const val MAX_WINDOW_M = 5_000.0

        /** Rule 1: the warning window, speed x [windowSeconds] clamped to 500 m..5 km. */
        fun windowM(speedMps: Double, windowSeconds: Int): Double = min(MAX_WINDOW_M, max(MIN_WINDOW_M, speedMps * windowSeconds))

        /** Rule 1: how far the look-ahead is sampled: the window plus 10 km. */
        fun horizonM(speedMps: Double, windowSeconds: Int): Double = windowM(speedMps, windowSeconds) + RETURN_HORIZON_M
    }
}

/** Alert rules' thresholds from the Settings. */
data class AlertConfig(
    val windowSeconds: Int = 90,
    val minDeadM: Double = 300.0,
    val floor: TechFloor = TechFloor.LOSE_ALL,
) {
    /** -118 dBm; -110 dBm for "lose 5G" (NR carriers only). */
    val thresholdDbm: Int get() = if (floor == TechFloor.LOSE_5G) -110 else -118

    constructor(c: TripConfig) : this(c.windowSeconds, c.minDeadZoneM.toDouble(), c.floor)
}
