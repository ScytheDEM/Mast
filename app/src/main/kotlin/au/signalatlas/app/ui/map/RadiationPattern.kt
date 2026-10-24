package au.signalatlas.app.ui.map

import au.signalatlas.app.data.HrpPattern
import au.signalatlas.app.data.PatternAccess
import au.signalatlas.core.map.BeamSpec
import au.signalatlas.core.map.SiteBeams
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Sector
import au.signalatlas.core.model.SiteDetail
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Where a plotted pattern comes from. */
enum class PatternSource { ACMA, TYPICAL }

/**
 * One sector's horizontal pattern: [attenuationDb] has 360 entries, the attenuation relative to the peak (≥ 0) at each
 * compass bearing (index = bearing in degrees clockwise from north). [azimuthDeg] null = omni.
 */
class SectorPlot(
    val sectorId: Int,
    val network: Network,
    val azimuthDeg: Double?,
    val source: PatternSource,
    val attenuationDb: FloatArray,
) {
    /** Points of the pattern on the unit circle (north up, x right, y up) every [stepDeg]; see [polarPoints]. */
    fun points(stepDeg: Int = 2, floorDb: Float = FLOOR_DB): List<PolarPoint> = polarPoints(attenuationDb, stepDeg, floorDb)

    fun toBeam(): BeamSpec = BeamSpec(network, azimuthDeg, attenuationDb)
}

/** A point of the polar plot: [bearingDeg] with radius [r] in 0..1 (1 = peak, 0 = [FLOOR_DB] or more down); x east, y north. */
data class PolarPoint(val bearingDeg: Int, val r: Float) {
    val x: Float get() = (r * sin(Math.toRadians(bearingDeg.toDouble()))).toFloat()
    val y: Float get() = (r * cos(Math.toRadians(bearingDeg.toDouble()))).toFloat()
}

const val FLOOR_DB = 30f
private const val HRP_STEP_DB = 0.25f
const val DEFAULT_H_BEAMWIDTH_DEG = 65.0
const val DEFAULT_FRONT_TO_BACK_DB = 25.0

/**
 * ACMA pattern attenuation by compass bearing. The bytes are attenuation in 0.25 dB steps at each degree clockwise of the
 * device's [HrpPattern.peakBearing], so bearing `b` reads byte `(b - peakBearing) mod 360`, exactly as the coverage
 * engine reads it.
 */
fun hrpAttenuation(pattern: HrpPattern): FloatArray = FloatArray(360) { bearing ->
    val k = Math.floorMod(bearing - pattern.peakBearing, 360)
    (pattern.attenuation[k].toInt() and 0xFF) * HRP_STEP_DB
}

/** The 3GPP TR 36.814 horizontal pattern `min(12 (θ/θ3dB)², Am)`, by compass bearing. */
fun parametricAttenuation(azimuthDeg: Double?, hBeamwidthDeg: Double?, frontToBackDb: Double?): FloatArray {
    if (azimuthDeg == null) return FloatArray(360)
    val bw = hBeamwidthDeg?.takeIf { it > 0 } ?: DEFAULT_H_BEAMWIDTH_DEG
    val am = frontToBackDb?.takeIf { it > 0 } ?: DEFAULT_FRONT_TO_BACK_DB
    return FloatArray(360) { bearing ->
        val off = angleDiff(bearing.toDouble(), azimuthDeg)
        min(12.0 * (off / bw) * (off / bw), am).toFloat()
    }
}

/** Smallest absolute difference between two bearings, 0..180. */
internal fun angleDiff(a: Double, b: Double): Double {
    val d = abs(a - b) % 360.0
    return if (d > 180.0) 360.0 - d else d
}

/** Radius 1 at 0 dB down to 0 at [floorDb] or more, linear in dB. */
fun polarPoints(attenuationDb: FloatArray, stepDeg: Int = 2, floorDb: Float = FLOOR_DB): List<PolarPoint> =
    (0 until 360 step stepDeg).map { b -> PolarPoint(b, (1f - attenuationDb[b] / floorDb).coerceIn(0f, 1f)) }

/** The plots of every sector of [detail]: the ACMA pattern of the first carrier that has one, else the parametric one. */
fun sectorPlots(detail: SiteDetail, patterns: PatternAccess): List<SectorPlot> =
    detail.sectors.sortedWith(compareBy({ it.network.ordinal }, { it.azimuthDeg ?: -1.0 })).map { sector ->
        val hrp = sector.carriers.firstNotNullOfOrNull { patterns.hrp(it.sddId) }
        if (hrp != null) {
            SectorPlot(sector.id, sector.network, sector.azimuthDeg, PatternSource.ACMA, hrpAttenuation(hrp))
        } else {
            SectorPlot(sector.id, sector.network, sector.azimuthDeg, PatternSource.TYPICAL, typical(sector))
        }
    }

private fun typical(s: Sector) = parametricAttenuation(s.azimuthDeg, s.antenna?.hBeamwidthDeg, s.antenna?.frontToBackDb)

/** The beams of the site at ([lat], [lon]) for the map. */
fun siteBeams(lat: Double, lon: Double, plots: List<SectorPlot>): SiteBeams? =
    plots.takeIf { it.isNotEmpty() }?.let { SiteBeams(lat, lon, it.map(SectorPlot::toBeam)) }
