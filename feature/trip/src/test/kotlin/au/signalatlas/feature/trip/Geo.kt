package au.signalatlas.feature.trip

import au.signalatlas.core.model.LatLon
import kotlin.math.cos
import kotlin.math.hypot

internal const val EARTH_R = 6_371_000.0
internal const val LAT0 = -33.87
internal const val LON0 = 151.21

/** Local metres (east, north) from the origin to lat/lon. */
internal fun toXY(p: LatLon): Pair<Double, Double> =
    Math.toRadians(p.lon - LON0) * cos(Math.toRadians(LAT0)) * EARTH_R to Math.toRadians(p.lat - LAT0) * EARTH_R

internal fun fromXY(x: Double, y: Double) =
    LatLon(LAT0 + Math.toDegrees(y / EARTH_R), LON0 + Math.toDegrees(x / (EARTH_R * cos(Math.toRadians(LAT0)))))

internal fun fix(tMs: Long, x: Double, y: Double) = fromXY(x, y).let { TrackFix(tMs, it.lat, it.lon) }

internal fun dist(a: LatLon, bx: Double, by: Double): Double {
    val (ax, ay) = toXY(a)
    return hypot(ax - bx, ay - by)
}
