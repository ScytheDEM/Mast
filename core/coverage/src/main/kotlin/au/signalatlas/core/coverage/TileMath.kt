package au.signalatlas.core.coverage

import au.signalatlas.core.model.GeoBounds
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** Web-Mercator tile geometry. */
internal object TileMath {
    /** Largest |latitude| of the Web-Mercator square. */
    private const val MAX_LAT = 85.0511287798

    private fun lonOf(x: Double, z: Int): Double = x / (1L shl z) * 360.0 - 180.0
    private fun latOf(y: Double, z: Int): Double =
        Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / (1L shl z)))))

    fun bounds(z: Int, x: Int, y: Int) = GeoBounds(latOf(y + 1.0, z), lonOf(x.toDouble(), z), latOf(y.toDouble(), z), lonOf(x + 1.0, z))

    fun centreLat(z: Int, y: Int): Double = latOf(y + 0.5, z)
    fun centreLon(z: Int, x: Int): Double = lonOf(x + 0.5, z)

    /** [b] grown by [metres] on every side (longitude margin sized for the higher-latitude edge). */
    fun expand(b: GeoBounds, metres: Double): GeoBounds {
        val dLat = Math.toDegrees(metres / EARTH_RADIUS_M)
        val worstLat = maxOf(kotlin.math.abs(b.south), kotlin.math.abs(b.north)) + dLat
        val dLon = dLat / cos(Math.toRadians(worstLat.coerceAtMost(89.0)))
        return GeoBounds(b.south - dLat, b.west - dLon, b.north + dLat, b.east + dLon)
    }

    /** The tile (x, y) at [z] containing the point and the pixel (0..255) inside it; null outside Web-Mercator. */
    class Located(val x: Int, val y: Int, val px: Int, val py: Int)

    fun locate(lat: Double, lon: Double, z: Int): Located? {
        if (!lat.isFinite() || !lon.isFinite() || lat > MAX_LAT || lat < -MAX_LAT) return null
        val n = (1L shl z).toDouble()
        val fx = ((lon + 180.0) / 360.0 * n).coerceIn(0.0, n - 1e-9)
        val rad = Math.toRadians(lat)
        val fy = ((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n).coerceIn(0.0, n - 1e-9)
        val tx = floor(fx)
        val ty = floor(fy)
        return Located(
            tx.toInt(), ty.toInt(),
            ((fx - tx) * 256).toInt().coerceIn(0, 255), ((fy - ty) * 256).toInt().coerceIn(0, 255),
        )
    }

    /** Haversine distance in metres. */
    /** The point [distanceM] from [lat], [lon] along [bearingDeg] (spherical earth; fine to tens of km). */
    fun offset(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        val r = 6_371_008.8
        val d = distanceM / r
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = kotlin.math.asin(kotlin.math.sin(p1) * kotlin.math.cos(d) + kotlin.math.cos(p1) * kotlin.math.sin(d) * kotlin.math.cos(b))
        val l2 = l1 + kotlin.math.atan2(kotlin.math.sin(b) * kotlin.math.sin(d) * kotlin.math.cos(p1), kotlin.math.cos(d) - kotlin.math.sin(p1) * kotlin.math.sin(p2))
        return Math.toDegrees(p2) to Math.toDegrees(l2)
    }

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
    }

    private const val EARTH_RADIUS_M = 6_371_000.0
}
