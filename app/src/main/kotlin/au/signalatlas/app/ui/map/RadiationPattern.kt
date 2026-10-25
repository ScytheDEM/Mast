package au.signalatlas.app.ui.map

import au.signalatlas.app.data.HrpPattern
import au.signalatlas.app.data.PatternAccess
import au.signalatlas.core.map.BeamSpec
import au.signalatlas.core.map.SiteBeams
import au.signalatlas.core.model.Carrier
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Sector
import au.signalatlas.core.model.SiteDetail
import au.signalatlas.core.model.Tech
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where a plotted pattern comes from. */
enum class PatternSource { ACMA, TYPICAL }

/**
 * One physical sector of a site, as drawn: every ACMA "sector" row (one per antenna model, height and licence) of one
 * network that points the same way is one panel direction, so they are drawn as one lobe. [attenuationDb] has 360
 * entries, the attenuation relative to the peak (≥ 0) at each compass bearing (index = degrees clockwise from true
 * north); for an ACMA lobe it is the envelope (smallest attenuation per bearing) of its carriers' measured patterns.
 * [azimuthDeg] null = omni. [bands] are the distinct bands it carries, 4G first, each in frequency order.
 */
class SectorPlot(
    /** Stable key, e.g. "TELSTRA-10" or "OPTUS-omni" (test tags, list keys). */
    val key: String,
    val network: Network,
    val azimuthDeg: Double?,
    val bands: List<String>,
    val source: PatternSource,
    val attenuationDb: FloatArray,
    /** The ACMA sector rows merged into this lobe. */
    val sectorIds: List<Int>,
) {
    /** Points of the pattern on the unit circle (north up, x right, y up) every [stepDeg]; see [polarPoints]. */
    fun points(stepDeg: Int = 2, floorDb: Float = FLOOR_DB): List<PolarPoint> = polarPoints(attenuationDb, stepDeg, floorDb)

    fun toBeam(omniLabel: String): BeamSpec = BeamSpec(network, azimuthDeg, attenuationDb, label = mapLabel(this, omniLabel))
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
 * A measured pattern whose deepest point is less than this below its peak is flat: ACMA files some directional devices
 * as a single 0–360° row of one POWER (e.g. Barton 101160's antenna 40081 at 80°/200°/320°). Drawn as is, such a
 * device turns a panel antenna into a circle, so a directional sector ignores it.
 */
const val FLAT_PATTERN_DB = 3f

/** A measured pattern whose peak is further than this from the licensed azimuth does not describe that sector. */
const val MAX_PEAK_OFFSET_DEG = 60.0

/**
 * ACMA pattern attenuation by compass bearing. The bytes are attenuation in 0.25 dB steps at each degree clockwise of the
 * device's [HrpPattern.peakBearing], so bearing `b` reads byte `(b - peakBearing) mod 360`, exactly as the coverage
 * engine reads it. ACMA's angles are compass bearings (true north), not angles off boresight: at Barton 101160 every
 * directional device peaks within 12° of its licensed azimuth.
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

/** True if [hrp] can stand for a sector pointing at [azimuthDeg] (null = omni: any pattern will do). */
internal fun usableFor(hrp: HrpPattern, azimuthDeg: Double?): Boolean {
    if (azimuthDeg == null) return true
    val deepest = hrp.attenuation.maxOf { it.toInt() and 0xFF } * HRP_STEP_DB
    return deepest >= FLAT_PATTERN_DB && angleDiff(hrp.peakBearing.toDouble(), azimuthDeg) <= MAX_PEAK_OFFSET_DEG
}

/**
 * One lobe per physical sector of [detail]: its sectors grouped by network and azimuth (to the degree), ordered by network
 * then azimuth (omni last). A lobe uses the envelope of its carriers' usable ACMA patterns, else the parametric pattern
 * of its first sector with antenna data.
 */
fun sectorPlots(detail: SiteDetail, patterns: PatternAccess): List<SectorPlot> =
    detail.sectors
        .groupBy { it.network to it.azimuthDeg?.let { az -> Math.floorMod(az.roundToInt(), 360) } }
        .entries
        .sortedWith(compareBy({ it.key.first.ordinal }, { it.key.second ?: Int.MAX_VALUE }))
        .map { (key, sectors) ->
            val (network, az) = key
            val azimuth = sectors.first().azimuthDeg
            val carriers = sectors.flatMap { it.carriers }
            val measured = carriers.mapNotNull { patterns.hrp(it.sddId) }.filter { usableFor(it, azimuth) }
            val (source, att) = if (measured.isNotEmpty()) {
                PatternSource.ACMA to envelope(measured.map(::hrpAttenuation))
            } else {
                PatternSource.TYPICAL to typical(sectors.firstOrNull { it.antenna != null } ?: sectors.first())
            }
            SectorPlot(
                key = "${network.name}-${az ?: "omni"}",
                network = network,
                azimuthDeg = azimuth,
                bands = bandOrder(carriers),
                source = source,
                attenuationDb = att,
                sectorIds = sectors.map(Sector::id),
            )
        }

/** The smallest attenuation per bearing: where any of the patterns radiates. */
internal fun envelope(patterns: List<FloatArray>): FloatArray = FloatArray(360) { b -> patterns.minOf { it[b] } }

/** Distinct bands, 4G before 5G, each tech by lowest frequency: "B28, B3, B1, B7, n78". */
internal fun bandOrder(carriers: List<Carrier>): List<String> =
    carriers.groupBy { it.band.band }
        .entries
        .sortedWith(compareBy({ it.value.first().band.tech != Tech.LTE }, { e -> e.value.minOf { it.freqHz } }))
        .map { it.key }

private fun typical(s: Sector) = parametricAttenuation(s.azimuthDeg, s.antenna?.hBeamwidthDeg, s.antenna?.frontToBackDb)

/** The lobe's short label on the map: "45° B3 B1 B7" or "Omni B28" (bands abbreviated past [MAP_LABEL_BANDS]). */
internal fun mapLabel(plot: SectorPlot, omniLabel: String): String {
    val head = plot.azimuthDeg?.let { "${trim(it, 0)}°" } ?: omniLabel
    val shown = plot.bands.take(MAP_LABEL_BANDS)
    val more = plot.bands.size - shown.size
    return (listOf(head) + shown + listOfNotNull(if (more > 0) "+$more" else null)).joinToString(" ")
}

private const val MAP_LABEL_BANDS = 4

/** "Telstra · 45° · B3 · B1 · B7 · n78" (the sheet's per-sector line; [networkName] already resolved). */
fun sectorBandsLine(networkName: String, plot: SectorPlot, omniLabel: String): String =
    (listOf(networkName, plot.azimuthDeg?.let { "${trim(it, 0)}°" } ?: omniLabel) + plot.bands).joinToString(" · ")

/** The beams of the site at ([lat], [lon]) for the map. */
fun siteBeams(lat: Double, lon: Double, plots: List<SectorPlot>, omniLabel: String): SiteBeams? =
    plots.takeIf { it.isNotEmpty() }?.let { SiteBeams(lat, lon, it.map { p -> p.toBeam(omniLabel) }) }
