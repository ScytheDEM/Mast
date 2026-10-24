package au.signalatlas.core.coverage

import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The prediction at one point of a path ([CoverageTileService.predictAlong]). [rsrpDbm] is null where no carrier of the
 * selection is in range; [probability] is rule 8's P(RSRP + shadowing >= threshold) for the threshold asked for.
 */
data class PointPrediction(
    val rsrpDbm: Float?,
    val sigmaDb: Float,
    val probability: Float,
    val bestNetwork: Network?,
    val bestTech: Tech?,
) {
    companion object {
        val NONE = PointPrediction(null, AlongPath.SIGMA_DB, 0f, null, null)
    }
}

internal object AlongPath {
    /** Shadowing sigma of the quick model (Hata/COST-231 rows use 8 dB; the engine does not report it per point). */
    const val SIGMA_DB = 8f
    const val DEFAULT_THRESHOLD_DBM = -118f

    /** Cached points are keyed by this grid (about 44 m): a path re-evaluated every 2 s mostly hits the cache. */
    const val CELL_DEG = 0.0004
    const val MAX_CELLS = 20_000

    /** A prediction before the threshold is applied. */
    class Raw(val rsrpDbm: Float?, val network: Network?, val tech: Tech?) {
        companion object {
            val NONE = Raw(null, null, null)
        }
    }

    fun toPrediction(raw: Raw, thresholdDbm: Float): PointPrediction {
        val rsrp = raw.rsrpDbm ?: return PointPrediction.NONE
        return PointPrediction(rsrp, SIGMA_DB, probability(rsrp, thresholdDbm, SIGMA_DB), raw.network, raw.tech)
    }

    /** Rule 8: P(shadowing >= thr - rsrp) for shadowing ~ N(0, sigma). */
    fun probability(rsrpDbm: Float, thresholdDbm: Float, sigmaDb: Float): Float {
        val z = (thresholdDbm - rsrpDbm) / sigmaDb.toDouble()
        return (0.5 * erfc(z / sqrt(2.0))).toFloat().coerceIn(0f, 1f)
    }

    /** Abramowitz-Stegun 7.1.26 (absolute error < 1.5e-7). */
    private fun erfc(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(x))
        val poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
        val erfAbs = 1.0 - poly * exp(-x * x)
        return if (x >= 0) 1.0 - erfAbs else 1.0 + erfAbs
    }

    fun cellKey(lat: Double, lon: Double): Long {
        val y = Math.floorDiv((lat * 1e7).toLong(), (CELL_DEG * 1e7).toLong())
        val x = Math.floorDiv((lon * 1e7).toLong(), (CELL_DEG * 1e7).toLong())
        return (y shl 32) xor (x and 0xFFFFFFFFL)
    }
}
