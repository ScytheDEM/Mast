package au.signalatlas.core.map

import au.signalatlas.core.model.Network
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * One sector's horizontal pattern for the map: [attenuationDb] has 360 entries, the attenuation relative to the peak (≥ 0)
 * at each compass bearing (index = bearing in degrees clockwise from true north). A null [azimuthDeg] is an omni antenna.
 * [label] is the short text drawn at the lobe's tip ("45° B3 B1 B7"); null = none.
 */
class BeamSpec(val network: Network, val azimuthDeg: Double?, val attenuationDb: FloatArray, val label: String? = null) {
    init { require(attenuationDb.size == 360) { "pattern needs 360 entries" } }
}

/** The beams of one site: drawn as translucent lobes while it is selected. */
class SiteBeams(val lat: Double, val lon: Double, val beams: List<BeamSpec>)

/**
 * GeoJSON lobes for [SiteBeams]: each beam is one polygon traced around the site every [STEP_DEG], at a radius
 * proportional to the field strength `10^(−att/20)` (so 6 dB down is half the length and the back lobe of a panel
 * antenna all but vanishes), plus one label point at its tip.
 */
object SectorBeams {
    const val STEP_DEG = 2
    /** Smallest radius fraction, so every lobe stays a closed shape around the site. */
    const val MIN_RADIUS = 0.02
    /** How far past the tip the label sits, as a fraction of the beam length. */
    const val LABEL_AT = 1.08
    /** Fill opacity of a lobe (the outline is drawn on top at [OUTLINE_OPACITY]). */
    const val FILL_OPACITY = 0.22f
    const val OUTLINE_OPACITY = 0.85f
    /** Labels show from this zoom (or always while the site's sheet is expanded). */
    const val LABEL_MIN_ZOOM = 15f
    private const val EARTH_M = 6_371_000.0

    /** About 2 km at zoom 13, doubling for every zoom level out and halving for every level in, kept between 150 m and 8 km. */
    fun lengthMetersForZoom(zoom: Double?): Double {
        val z = zoom ?: 13.0
        return (2000.0 * 2.0.pow(13.0 - z)).coerceIn(150.0, 8000.0)
    }

    /** Radius fraction (0..1) of a lobe at [attenuationDb]: relative field strength, at least [MIN_RADIUS]. */
    fun radiusFraction(attenuationDb: Float): Double = max(MIN_RADIUS, 10.0.pow(-attenuationDb / 20.0))

    /** The lobe's outline: (bearing, radius fraction) every [STEP_DEG] from north, clockwise. */
    fun outline(beam: BeamSpec): List<Pair<Int, Double>> =
        (0 until 360 step STEP_DEG).map { b -> b to radiusFraction(beam.attenuationDb[b]) }

    /** Where the label goes: the azimuth for a directional beam (north for an omni), just past the tip. */
    fun labelBearing(beam: BeamSpec): Double = beam.azimuthDeg ?: 0.0

    /**
     * Features: `{kind:"lobe", net}` polygons and `{kind:"label", net, text}` points. The lobe's ring starts and ends at
     * bearing 0.
     */
    fun toGeoJson(beams: SiteBeams, lengthM: Double): String {
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        var first = true
        fun sep() {
            if (!first) sb.append(',')
            first = false
        }
        for (beam in beams.beams) {
            val ring = outline(beam).map { (b, f) -> destination(beams.lat, beams.lon, b.toDouble(), lengthM * f) }
            sep()
            sb.append("""{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[""")
            (ring + ring.first()).forEachIndexed { i, (lat, lon) ->
                if (i > 0) sb.append(',')
                sb.append('[').append(lon).append(',').append(lat).append(']')
            }
            sb.append("""]]},"properties":{"kind":"lobe","net":"${beam.network.name}"}}""")
            val label = beam.label ?: continue
            val bearing = labelBearing(beam)
            val tip = radiusFraction(beam.attenuationDb[Math.floorMod(Math.round(bearing).toInt(), 360)])
            val (lat, lon) = destination(beams.lat, beams.lon, bearing, lengthM * tip * LABEL_AT)
            sep()
            sb.append(
                """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]},""" +
                    """"properties":{"kind":"label","net":"${beam.network.name}","text":"${escape(label)}"}}""",
            )
        }
        return sb.append("]}").toString()
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** (lat, lon) [distanceM] from the origin along the compass [bearingDeg] (spherical earth). */
    internal fun destination(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        val d = distanceM / EARTH_M
        val br = Math.toRadians(bearingDeg)
        val la = Math.toRadians(lat)
        val lo = Math.toRadians(lon)
        val lat2 = Math.asin(sin(la) * cos(d) + cos(la) * sin(d) * cos(br))
        val lon2 = lo + Math.atan2(sin(br) * sin(d) * cos(la), cos(d) - sin(la) * sin(lat2))
        return Math.toDegrees(lat2) to Math.toDegrees(lon2)
    }
}
