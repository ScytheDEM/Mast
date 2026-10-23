package au.signalatlas.core.calibration

import au.signalatlas.core.model.CalibrationRules
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The M4 calibration rules 1-6 as pure functions. tools/calibration/reference.py is the reference (vectors in
 * src/test/resources/calibration/vectors.json); change both together.
 */
object CalibrationMath {
    // Rule 1
    const val FREQ_TOLERANCE_MHZ = 0.15
    const val CANDIDATE_RADIUS_KM = 40.0
    const val MAX_FIX_ACCURACY_M = 50.0
    // Rule 2
    private const val TA_STEP_M = 150.0
    private const val TA_DB_PER_STEP = 3.0
    private const val OUT_OF_RANGE_PENALTY = 100.0
    const val LIKELY_MARGIN = 3.0
    const val CONFIRM_AFTER = 5
    // Rule 3
    const val THROTTLE_M = 50.0
    const val THROTTLE_MS = 5000L
    // Rule 4
    private const val PRIOR_SIGMA_DB = 8.0
    private const val MEAS_SIGMA_DB = 6.0
    const val OFFSET_CLAMP_DB = 20.0
    const val SLOPE_MIN_N = 30
    private const val SLOPE_MIN_SPREAD = 3.0
    private const val SLOPE_PRIOR_SIGMA = 10.0
    // Rule 6
    private const val SIGMA_FLOOR_DB = 4.0

    private const val EARTH_R = 6371008.8

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
        return 2.0 * EARTH_R * asin(min(1.0, sqrt(a)))
    }

    /** Rule 1: within +-0.15 MHz of the channel's frequency and 40 km of the fix. */
    fun isCandidate(carrierFreqMhz: Double, channelFreqMhz: Double, distanceM: Double): Boolean =
        abs(carrierFreqMhz - channelFreqMhz) <= FREQ_TOLERANCE_MHZ + 1e-9 && distanceM <= CANDIDATE_RADIUS_KM * 1000.0

    /** Rule 2 (lower is better): |P - M| + (TA ? |d - TA| / 150 m * 3 dB : 0) + (d > R_max ? 100 : 0). */
    fun matchScore(predictedDbm: Double, measuredDbm: Double, distanceM: Double, taM: Double?, rMaxKm: Double): Double {
        var s = abs(predictedDbm - measuredDbm)
        if (taM != null) s += abs(distanceM - taM) / TA_STEP_M * TA_DB_PER_STEP
        if (distanceM > rMaxKm * 1000.0) s += OUT_OF_RANGE_PENALTY
        return s
    }

    /** Rule 2: [Confidence.LIKELY] when the best beats the second by >= 3 points (or has no rival); null when empty. */
    fun confidence(scores: List<Double>): Confidence? {
        if (scores.isEmpty()) return null
        val s = scores.sorted()
        return if (s.size == 1 || s[1] - s[0] >= LIKELY_MARGIN) Confidence.LIKELY else Confidence.UNCERTAIN
    }

    /** Rule 3: >= 50 m from the last stored sample and >= 5 s after it (whichever comes later). */
    fun throttleOk(last: Fix?, now: Fix): Boolean =
        last == null || (now.timeMs - last.timeMs >= THROTTLE_MS && distanceM(last.lat, last.lon, now.lat, now.lon) >= THROTTLE_M)

    /** Rule 4 offset: posterior mean with prior N(0, 8^2) and sigma_m 6 dB, clamped to +-20 dB. */
    fun offsetOnly(n: Int, sumR: Double): Double {
        if (n == 0) return 0.0
        val mu = (sumR / (MEAS_SIGMA_DB * MEAS_SIGMA_DB)) / (1.0 / (PRIOR_SIGMA_DB * PRIOR_SIGMA_DB) + n / (MEAS_SIGMA_DB * MEAS_SIGMA_DB))
        return mu.coerceIn(-OFFSET_CLAMP_DB, OFFSET_CLAMP_DB)
    }

    fun median(xs: DoubleArray): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sortedArray()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }

    /** x = log10(d / d_median), unclamped (see reference.py `slope_regressor`). */
    fun regressor(dKm: Double, dMedianKm: Double): Double = log10(max(dKm, 1e-6) / dMedianKm)

    /**
     * Rule 4 for one sector's samples ([residuals] r = M - P, [distancesKm]): the offset alone, or, with n >= 30 and
     * max(d) / min(d) >= 3, offset and slope jointly (MAP with priors N(0, 8^2) and N(0, 10^2), sigma_m 6 dB); the
     * offset is clamped to +-20 dB either way.
     */
    fun fitSector(residuals: DoubleArray, distancesKm: DoubleArray): SectorFit {
        require(residuals.size == distancesKm.size) { "residuals and distances differ in length" }
        val n = residuals.size
        val dMed = median(distancesKm)?.takeIf { it > 0.0 } ?: 1.0
        val sumR = residuals.sum()
        val spreadOk = n > 0 && distancesKm.max() >= SLOPE_MIN_SPREAD * max(distancesKm.min(), 1e-3)
        if (n < SLOPE_MIN_N || !spreadOk) return SectorFit(n, offsetOnly(n, sumR), 0.0, dMed, false)
        val w = 1.0 / (MEAS_SIGMA_DB * MEAS_SIGMA_DB)
        var sx = 0.0
        var sxx = 0.0
        var sxr = 0.0
        for (i in 0 until n) {
            val x = regressor(distancesKm[i], dMed)
            sx += x
            sxx += x * x
            sxr += x * residuals[i]
        }
        val a11 = n * w + 1.0 / (PRIOR_SIGMA_DB * PRIOR_SIGMA_DB)
        val a12 = sx * w
        val a22 = sxx * w + 1.0 / (SLOPE_PRIOR_SIGMA * SLOPE_PRIOR_SIGMA)
        val b1 = sumR * w
        val b2 = sxr * w
        val det = a11 * a22 - a12 * a12
        val mu = (b1 * a22 - a12 * b2) / det
        val b = (a11 * b2 - a12 * b1) / det
        return SectorFit(n, mu.coerceIn(-OFFSET_CLAMP_DB, OFFSET_CLAMP_DB), b, dMed, true)
    }

    /** Rule 6: max(4, sigma * sqrt(10 / (10 + n))). */
    fun sigmaEff(sigmaDb: Double, n: Int): Double = max(SIGMA_FLOOR_DB, sigmaDb * sqrt(10.0 / (10.0 + n)))
}

/** A sector's rule-4 estimate. [slopeDbPerDecade] is 0 unless [slopeFitted]. */
data class SectorFit(val n: Int, val offsetDb: Double, val slopeDbPerDecade: Double, val dMedianKm: Double, val slopeFitted: Boolean) {
    /** The correction applied at [dKm] (0 below 3 samples): mu + b * log10(d / d_median). */
    fun correctionDb(dKm: Double): Double =
        if (n < CalibrationRules.MIN_APPLIED_SAMPLES) 0.0
        else offsetDb + slopeDbPerDecade * CalibrationMath.regressor(dKm, dMedianKm)
}
