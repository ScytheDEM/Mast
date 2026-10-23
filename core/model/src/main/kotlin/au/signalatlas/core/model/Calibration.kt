package au.signalatlas.core.model

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * One sector's learned correction (M4 Calibration rules 4 and 6), as `:core:calibration` publishes it and coverage
 * applies it. [network] is the carriers' `Network` ordinal; the site position lets consumers find the sectors of an
 * area without the tower DB. [slopeDbPerDecade] is 0 until the slope applies (rule 4).
 */
data class SectorCorrection(
    val sectorId: Int,
    val network: Int,
    val siteLat: Double,
    val siteLon: Double,
    val n: Int,
    val offsetDb: Double,
    val slopeDbPerDecade: Double,
    val dMedianKm: Double,
) {
    /** Rule 6's factor: sigma_eff = max(4, sigma * sigmaScale). */
    val sigmaScale: Double get() = CalibrationRules.sigmaScale(n)

    /** Applied to tiles only from [CalibrationRules.MIN_APPLIED_SAMPLES] samples (Review Focus 2). */
    val isApplied: Boolean get() = n >= CalibrationRules.MIN_APPLIED_SAMPLES
}

/**
 * Rule 5: one 0.001 deg x 0.001 deg cell of a network's residual grid: the mean residual after the sector corrections
 * ([meanDb]) over [n] samples. Cell (latIdx, lonIdx) covers latitudes [latIdx, latIdx + 1) * 0.001 deg, likewise
 * longitude.
 */
data class ResidualCell(val network: Int, val latIdx: Int, val lonIdx: Int, val n: Int, val meanDb: Double) {
    /** Applied only where n >= 3, weighted n / (n + 5). */
    val isApplied: Boolean get() = n >= CalibrationRules.MIN_APPLIED_SAMPLES
    val appliedDb: Double get() = if (isApplied) CalibrationRules.residualWeight(n) * meanDb else 0.0
}

/**
 * Everything calibration currently knows, immutable. [version] increases on every publish (at most every 30 s while
 * samples arrive, immediately after a delete or reset), so consumers can tell snapshots apart cheaply.
 */
data class CalibrationSnapshot(
    val version: Long,
    val sectors: Map<Int, SectorCorrection>,
    val residuals: List<ResidualCell>,
) {
    companion object {
        val EMPTY = CalibrationSnapshot(0L, emptyMap(), emptyList())
    }
}

/** Shared constants and the small formulas both the calibration module and coverage use (M4 Calibration rules). */
object CalibrationRules {
    /** Rules 4-5 / Review Focus 2: corrections apply from this many samples. */
    const val MIN_APPLIED_SAMPLES = 3
    const val CELL_DEG = 0.001
    private const val RESIDUAL_K = 5.0
    private const val SIGMA_N0 = 10.0

    /** Index of the residual cell containing [deg] (latitude or longitude): floor(deg * 1000). */
    fun cellIndex(deg: Double): Int = floor(deg * 1000.0).toInt()

    fun residualWeight(n: Int): Double = n / (n + RESIDUAL_K)

    /** Rule 6: sqrt(10 / (10 + n)). */
    fun sigmaScale(n: Int): Double = sqrt(SIGMA_N0 / (SIGMA_N0 + n))
}
