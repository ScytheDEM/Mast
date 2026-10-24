package au.signalatlas.core.map

import au.signalatlas.core.model.Network
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * One sector's horizontal pattern for the map: [attenuationDb] has 360 entries, the attenuation relative to the peak (≥ 0)
 * at each compass bearing (index = bearing in degrees clockwise from north). A null [azimuthDeg] is an omni antenna.
 */
class BeamSpec(val network: Network, val azimuthDeg: Double?, val attenuationDb: FloatArray) {
    init { require(attenuationDb.size == 360) { "pattern needs 360 entries" } }
}

/** The beams of one site: drawn as translucent wedges while its sheet is open. */
class SiteBeams(val lat: Double, val lon: Double, val beams: List<BeamSpec>)

/** GeoJSON wedges for [SiteBeams]. Each sector is cut into [SLICE_DEG] slices so the opacity can follow the pattern. */
object SectorBeams {
    const val SLICE_DEG = 5
    /** Attenuation at which the wedge has shrunk to nothing. */
    const val FLOOR_DB = 30f
    /** Half-width of a directional wedge, degrees either side of the azimuth. */
    const val HALF_SPAN_DEG = 75
    private const val MAX_OPACITY = 0.5f
    private const val MIN_RADIUS = 0.12
    private const val EARTH_M = 6_371_000.0

    /** About 2 km at zoom 13, doubling for every zoom level out and halving for every level in, kept between 150 m and 8 km. */
    fun lengthMetersForZoom(zoom: Double?): Double {
        val z = zoom ?: 13.0
        return (2000.0 * 2.0.pow(13.0 - z)).coerceIn(150.0, 8000.0)
    }

    /** Radius fraction (0..1) of a wedge at [attenuationDb]. */
    fun radiusFraction(attenuationDb: Float): Double = max(MIN_RADIUS, 1.0 - attenuationDb / FLOOR_DB)

    /** Opacity of a slice at [attenuationDb]. */
    fun opacity(attenuationDb: Float): Float = (MAX_OPACITY * (1f - attenuationDb / FLOOR_DB)).coerceIn(0.06f, MAX_OPACITY)

    /** Features `{net, op}` polygons: site, arc point at the slice start, arc point at its end. */
    fun toGeoJson(beams: SiteBeams, lengthM: Double): String {
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        var first = true
        for (beam in beams.beams) {
            val (from, to) = beam.azimuthDeg
                ?.let { az -> (az - HALF_SPAN_DEG) to (az + HALF_SPAN_DEG) } ?: (0.0 to 360.0)
            var a = from
            while (a < to - 1e-9) {
                val b = minOf(a + SLICE_DEG, to)
                val mid = (a + b) / 2
                val att = beam.attenuationDb[Math.floorMod(Math.round(mid).toInt(), 360)]
                val r = lengthM * radiusFraction(att)
                val p1 = destination(beams.lat, beams.lon, a, r)
                val p2 = destination(beams.lat, beams.lon, b, r)
                if (!first) sb.append(',')
                first = false
                sb.append(
                    """{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[${beams.lon},${beams.lat}],[${p1.second},${p1.first}],""" +
                        """[${p2.second},${p2.first}],[${beams.lon},${beams.lat}]]]},"properties":{"net":"${beam.network.name}","op":${opacity(att)}}}""",
                )
                a = b
            }
        }
        return sb.append("]}").toString()
    }

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
