package au.signalatlas.feature.trip

import au.signalatlas.core.model.LatLon
import au.signalatlas.core.roads.RoadGraph
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One location fix of the track the look-ahead extrapolates. */
data class TrackFix(val timeMs: Long, val lat: Double, val lon: Double)

/**
 * Track-based path prediction (M5 ruling): the position ahead is extrapolated from the last [TRACK_MS] of fixes with
 * the current heading and the turn rate fitted to them, as a circular arc for at most [ARC_LIMIT_M] and straight
 * beyond. When a [RoadGraph] is given and the position matches a road (within 30 m, heading within 45 degrees of the
 * fitted one) the path follows the road instead, straight on past where the graph ends; otherwise the arc stands.
 */
object LookAhead {
    const val TRACK_MS = 30_000L
    const val STEP_M = 50.0
    const val ARC_LIMIT_M = 1_000.0

    /** Below this the vehicle counts as not moving: no path ahead. */
    const val MIN_SPEED_MPS = 1.0

    /** Fixes closer than this to the previous kept one are noise for heading purposes. */
    private const val MIN_SPACING_M = 15.0

    /** Tightest turn believed (radius 30 m) and the turn radius above which the track counts as straight. */
    private const val MAX_CURVATURE = 1.0 / 30.0
    private const val STRAIGHT_CURVATURE = 1.0 / 5_000.0
    private const val EARTH_RADIUS_M = 6_371_000.0

    /**
     * [points] are samples every [STEP_M] metres along the predicted path, starting at the last fix (index 0, 0 m) up to
     * [lengthM]; a single point when the vehicle is not moving or the track is too short. [speedMps] is the mean speed
     * over the track window, [headingDeg] the fitted heading at the last fix (0 = north) and [curvature] the signed
     * turn per metre (positive = clockwise).
     */
    class Result(val points: List<LatLon>, val speedMps: Double, val headingDeg: Double, val curvature: Double)

    fun project(track: List<TrackFix>, lengthM: Double, roads: RoadGraph? = null): Result {
        val arc = projectArc(track, lengthM)
        if (roads == null || lengthM <= 0.0 || arc.speedMps < MIN_SPEED_MPS) return arc
        val here = arc.points.firstOrNull() ?: return arc
        val match = roads.match(here.lat, here.lon, arc.headingDeg) ?: return arc
        val path = roads.follow(match, lengthM, STEP_M)
        return Result(listOf(here) + path.points, arc.speedMps, arc.headingDeg, arc.curvature)
    }

    private fun projectArc(track: List<TrackFix>, lengthM: Double): Result {
        val last = track.lastOrNull() ?: return Result(emptyList(), 0.0, 0.0, 0.0)
        val here = LatLon(last.lat, last.lon)
        val recent = track.filter { last.timeMs - it.timeMs <= TRACK_MS }
        val cosLat = cos(Math.toRadians(last.lat))
        fun east(f: TrackFix) = Math.toRadians(f.lon - last.lon) * cosLat * EARTH_RADIUS_M
        fun north(f: TrackFix) = Math.toRadians(f.lat - last.lat) * EARTH_RADIUS_M

        // Thin to fixes at least MIN_SPACING_M apart, newest kept, so jitter does not make up a heading.
        val chainRev = ArrayList<TrackFix>()
        for (f in recent.asReversed()) {
            val prev = chainRev.lastOrNull()
            if (prev == null || hypot(east(f) - east(prev), north(f) - north(prev)) >= MIN_SPACING_M) chainRev += f
        }
        val chain = chainRev.asReversed()
        val spanS = max((last.timeMs - recent.first().timeMs) / 1000.0, 1.0)
        var length = 0.0
        for (i in 1 until chain.size) length += hypot(east(chain[i]) - east(chain[i - 1]), north(chain[i]) - north(chain[i - 1]))
        val speed = length / spanS
        if (chain.size < 2 || speed < MIN_SPEED_MPS) return Result(listOf(here), speed, 0.0, 0.0)

        // Segment headings against distance along the chain (unwrapped), then a least-squares line: slope = curvature.
        val s = ArrayList<Double>()
        val h = ArrayList<Double>()
        var run = 0.0
        for (i in 1 until chain.size) {
            val dx = east(chain[i]) - east(chain[i - 1])
            val dy = north(chain[i]) - north(chain[i - 1])
            val seg = hypot(dx, dy)
            var a = atan2(dx, dy)
            if (h.isNotEmpty()) a = h.last() + wrap(a - h.last())
            s += run + seg / 2
            h += a
            run += seg
        }
        var kappa = 0.0
        var heading = h.last()
        if (h.size >= 3) {
            val sm = s.average()
            val hm = h.average()
            var num = 0.0
            var den = 0.0
            for (i in h.indices) {
                num += (s[i] - sm) * (h[i] - hm)
                den += (s[i] - sm) * (s[i] - sm)
            }
            val slope = if (den > 0) num / den else 0.0
            kappa = if (abs(slope) < STRAIGHT_CURVATURE) 0.0 else slope.coerceIn(-MAX_CURVATURE, MAX_CURVATURE)
            heading = if (kappa == 0.0) h.last() else hm + kappa * (run - sm)
        }

        val arcLen = if (kappa == 0.0) 0.0 else min(ARC_LIMIT_M, PI / abs(kappa))
        val h1 = heading + kappa * arcLen
        val out = ArrayList<LatLon>()
        var d = 0.0
        while (d <= lengthM + 1e-6) {
            val e: Double
            val n: Double
            if (d <= arcLen && kappa != 0.0) {
                e = (cos(heading) - cos(heading + kappa * d)) / kappa
                n = (sin(heading + kappa * d) - sin(heading)) / kappa
            } else {
                val e0 = if (kappa == 0.0) 0.0 else (cos(heading) - cos(h1)) / kappa
                val n0 = if (kappa == 0.0) 0.0 else (sin(h1) - sin(heading)) / kappa
                val rest = d - (if (kappa == 0.0) 0.0 else arcLen)
                e = e0 + rest * sin(h1)
                n = n0 + rest * cos(h1)
            }
            out += LatLon(
                last.lat + Math.toDegrees(n / EARTH_RADIUS_M),
                last.lon + Math.toDegrees(e / (EARTH_RADIUS_M * cosLat.coerceAtLeast(1e-6))),
            )
            d += STEP_M
        }
        return Result(out, speed, (Math.toDegrees(heading) % 360 + 360) % 360, kappa)
    }

    private fun wrap(a: Double): Double {
        var x = a
        while (x > PI) x -= 2 * PI
        while (x < -PI) x += 2 * PI
        return x
    }
}
