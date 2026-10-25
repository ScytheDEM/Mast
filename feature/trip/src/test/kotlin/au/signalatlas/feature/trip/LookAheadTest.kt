package au.signalatlas.feature.trip

import au.signalatlas.core.roads.RoadClass
import au.signalatlas.core.roads.RoadGraph
import au.signalatlas.core.roads.RoadWay
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class LookAheadTest {
    /** 1 Hz fixes for [seconds], position by [pos] (x east, y north) of the second. */
    private fun track(seconds: Int, pos: (Int) -> Pair<Double, Double>) =
        (0..seconds).map { t -> pos(t).let { (x, y) -> fix(1000L * t, x, y) } }

    @Test fun straightEastIsExtrapolatedEastEvery50m() {
        val r = LookAhead.project(track(30) { it * 20.0 to 0.0 }, 5_000.0)
        assertThat(r.speedMps).isWithin(0.1).of(20.0)
        assertThat(r.headingDeg).isWithin(0.5).of(90.0)
        assertThat(r.curvature).isEqualTo(0.0)
        assertThat(r.points).hasSize(101)
        val (x0, y0) = toXY(r.points[0])
        assertThat(x0).isWithin(0.01).of(600.0)
        assertThat(y0).isWithin(0.01).of(0.0)
        for (k in listOf(1, 20, 100)) {
            val (x, y) = toXY(r.points[k])
            assertThat(x).isWithin(1.0).of(600.0 + 50.0 * k)
            assertThat(y).isWithin(1.0).of(0.0)
        }
    }

    @Test fun straightNorthWestHeadsNorthWest() {
        val r = LookAhead.project(track(20) { it * -10.0 to it * 10.0 }, 2_000.0)
        assertThat(r.headingDeg).isWithin(0.5).of(315.0)
        val (x0, y0) = toXY(r.points[0])
        val (x, y) = toXY(r.points[20]) // 1 km on
        assertThat(Math.hypot(x - x0, y - y0)).isWithin(2.0).of(1_000.0)
        assertThat(x - x0).isLessThan(0.0)
        assertThat(y - y0).isGreaterThan(0.0)
    }

    @Test fun constantTurnFollowsTheArcThenGoesStraight() {
        // Clockwise circle of radius 200 m at 20 m/s, starting at the origin heading east (centre 200 m south).
        val rad = 200.0
        val w = 20.0 / rad
        val r = LookAhead.project(track(30) { t -> rad * sin(w * t) to rad * cos(w * t) - rad }, 3_000.0)
        assertThat(r.curvature).isWithin(0.0003).of(1 / rad)
        val (cx, cy) = 0.0 to -rad
        val (ex, ey) = rad * sin(w * 30) to rad * cos(w * 30) - rad
        val a0 = Math.atan2(ex - cx, ey - cy)
        // 300 m and 600 m ahead sit on the same circle (the arc is at most half a turn, 628 m here).
        for (k in listOf(6, 12)) {
            val (x, y) = toXY(r.points[k])
            assertThat(Math.hypot(x - cx, y - cy)).isWithin(4.0).of(rad)
            val expectedAngle = a0 + 50.0 * k / rad
            assertThat(x).isWithin(5.0).of(cx + rad * sin(expectedAngle))
            assertThat(y).isWithin(5.0).of(cy + rad * cos(expectedAngle))
        }
        // Beyond 1 km the path is a straight line: equal spacing and no change of direction.
        val p = r.points.map(::toXY)
        val d1 = Math.atan2(p[41].first - p[40].first, p[41].second - p[40].second)
        val d2 = Math.atan2(p[60].first - p[59].first, p[60].second - p[59].second)
        assertThat(d1).isWithin(1e-3).of(d2)
        assertThat(Math.hypot(p[60].first - p[59].first, p[60].second - p[59].second)).isWithin(0.5).of(50.0)
    }

    @Test fun stationaryOrTooShortGivesOnlyTheCurrentPosition() {
        val still = LookAhead.project(track(30) { 100.0 + 0.2 * (it % 3) to 50.0 }, 5_000.0)
        assertThat(still.points).hasSize(1)
        assertThat(still.speedMps).isLessThan(1.0)
        val slow = LookAhead.project(track(30) { it * 0.5 to 0.0 }, 5_000.0)
        assertThat(slow.points).hasSize(1)
        assertThat(LookAhead.project(listOf(fix(0, 0.0, 0.0)), 1_000.0).points).hasSize(1)
        assertThat(LookAhead.project(emptyList(), 1_000.0).points).isEmpty()
    }

    @Test fun onlyTheLast30SecondsCount() {
        // Drove north for 40 s, then east for 30 s: the heading is east.
        val north = (0..40).map { t -> fix(1000L * t, 0.0, t * 20.0) }
        val east = (1..30).map { t -> fix(40_000L + 1000L * t, t * 20.0, 800.0) }
        val r = LookAhead.project(north + east, 1_000.0)
        assertThat(r.headingDeg).isWithin(2.0).of(90.0)
    }

    @Test fun gpsJitterOnAStraightRoadStaysStraight() {
        val rnd = java.util.Random(7)
        val r = LookAhead.project(track(30) { it * 25.0 + rnd.nextGaussian() * 2 to rnd.nextGaussian() * 2 }, 5_000.0)
        val (x, y) = toXY(r.points[100])
        assertThat(Math.abs(y - toXY(r.points[0]).second)).isLessThan(400.0)
        assertThat(x).isGreaterThan(toXY(r.points[0]).first + 4_500.0)
    }

    // --- road following (M3b batch 3) ---

    private fun way(cls: RoadClass, vararg pts: Pair<Double, Double>) = RoadWay(cls, null, pts.map { fromXY(it.first, it.second) })
    private fun graph(vararg ways: RoadWay) = RoadGraph.build(LAT0, LON0, ways.toList())

    @Test fun followsTheRoadRoundABendTheArcExtrapolationMisses() {
        // Driving east at 25 m/s along a road that turns north at x = 1500.
        val g = graph(way(RoadClass.MAJOR, -500.0 to 0.0, 1500.0 to 0.0, 1500.0 to 12_000.0))
        val t = track(30) { it * 25.0 to 0.0 } // ends at x = 750
        val arc = LookAhead.project(t, 5_000.0)
        val road = LookAhead.project(t, 5_000.0, g)
        assertThat(road.points).hasSize(arc.points.size)
        assertThat(road.points[0]).isEqualTo(arc.points[0])
        // Speed, heading and curvature still describe the track.
        assertThat(road.speedMps).isEqualTo(arc.speedMps)
        assertThat(road.headingDeg).isEqualTo(arc.headingDeg)
        val (ax, ay) = toXY(arc.points[100])
        assertThat(ax).isGreaterThan(5_000.0)
        assertThat(ay).isWithin(1.0).of(0.0)
        // 2,000 m on = 750 m to the bend, then 1,250 m north.
        val (x, y) = toXY(road.points[40])
        assertThat(x).isWithin(1.0).of(1500.0)
        assertThat(y).isWithin(1.0).of(1250.0)
        // 5 km on: 750 m to the bend and 4,250 m north.
        val (x2, y2) = toXY(road.points[100])
        assertThat(x2).isWithin(1.0).of(1500.0)
        assertThat(y2).isWithin(2.0).of(5_000.0 - 750.0)
    }

    @Test fun fallsBackToTheArcWhenNothingMatches() {
        val t = track(30) { it * 25.0 to 0.0 }
        val arc = LookAhead.project(t, 3_000.0)
        // Too far away (100 m), crossing the heading, or no graph at all: the arc stands, point for point.
        val far = graph(way(RoadClass.MAJOR, 0.0 to 100.0, 5000.0 to 100.0))
        val across = graph(way(RoadClass.MAJOR, 750.0 to -2000.0, 750.0 to 2000.0))
        assertThat(LookAhead.project(t, 3_000.0, far).points).isEqualTo(arc.points)
        assertThat(LookAhead.project(t, 3_000.0, across).points).isEqualTo(arc.points)
        assertThat(LookAhead.project(t, 3_000.0, graph()).points).isEqualTo(arc.points)
        assertThat(LookAhead.project(t, 3_000.0, null).points).isEqualTo(arc.points)
    }

    @Test fun aStationaryVehicleStaysPutEvenOnARoad() {
        val g = graph(way(RoadClass.MAJOR, 0.0 to 0.0, 5000.0 to 0.0))
        val r = LookAhead.project(track(30) { 100.0 + 0.2 * (it % 3) to 0.0 }, 5_000.0, g)
        assertThat(r.points).hasSize(1)
    }
}
