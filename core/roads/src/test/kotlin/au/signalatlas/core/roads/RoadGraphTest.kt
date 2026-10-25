package au.signalatlas.core.roads

import au.signalatlas.core.model.LatLon
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot

private const val R = 6_371_000.0
private const val LAT0 = -35.0
private const val LON0 = 148.0

/** Local metres (east, north) of the test origin to degrees. */
internal fun ll(x: Double, y: Double) =
    LatLon(LAT0 + Math.toDegrees(y / R), LON0 + Math.toDegrees(x / (R * cos(Math.toRadians(LAT0)))))

internal fun xy(p: LatLon) = Math.toRadians(p.lon - LON0) * cos(Math.toRadians(LAT0)) * R to Math.toRadians(p.lat - LAT0) * R

internal fun way(cls: RoadClass, vararg pts: Pair<Double, Double>) = RoadWay(cls, null, pts.map { ll(it.first, it.second) })

class RoadGraphTest {
    private fun graph(vararg ways: RoadWay) = RoadGraph.build(LAT0, LON0, ways.toList())

    private fun follow(g: RoadGraph, x: Double, y: Double, heading: Double, length: Double): List<Pair<Double, Double>> {
        val p = ll(x, y)
        val m = g.match(p.lat, p.lon, heading)!!
        return g.follow(m, length).points.map(::xy)
    }

    @Test fun followsABendWhereTheArcWouldGoStraight() {
        // East for 1 km, then a 90 degree bend north for 3 km.
        val g = graph(way(RoadClass.MAJOR, 0.0 to 0.0, 500.0 to 0.0, 1000.0 to 0.0, 1000.0 to 1500.0, 1000.0 to 3000.0))
        val pts = follow(g, 800.0, 0.0, 90.0, 2_000.0)
        assertThat(pts).hasSize(40)
        // 200 m to the bend, then north: the sample 1,000 m on is 800 m north of the bend.
        val (x, y) = pts[19]
        assertThat(x).isWithin(1.0).of(1000.0)
        assertThat(y).isWithin(1.0).of(800.0)
    }

    @Test fun samplesAreEvery50mAlongTheRoad() {
        val g = graph(way(RoadClass.MINOR, 0.0 to 0.0, 300.0 to 0.0, 300.0 to 400.0))
        val pts = follow(g, 100.0, 0.0, 90.0, 600.0)
        assertThat(pts).hasSize(12)
        assertThat(pts[0].first).isWithin(0.5).of(150.0)
        assertThat(pts[3].first).isWithin(0.5).of(300.0) // 200 m on: at the bend
        assertThat(pts[3].second).isWithin(0.5).of(0.0)
        assertThat(pts[11].second).isWithin(0.5).of(400.0) // 600 m on: the end of the road
    }

    @Test fun continuesStraightOnPastTheEndOfTheRoad() {
        val g = graph(way(RoadClass.MINOR, 0.0 to 0.0, 500.0 to 0.0))
        val p = ll(100.0, 0.0)
        val path = g.follow(g.match(p.lat, p.lon, 90.0)!!, 1_000.0)
        assertThat(path.roadLengthM).isWithin(1.0).of(400.0)
        val last = xy(path.points.last())
        assertThat(last.first).isWithin(1.0).of(1100.0)
        assertThat(last.second).isWithin(1.0).of(0.0)
    }

    @Test fun atAJunctionTheStraightHigherClassRoadIsFollowed() {
        // A highway east with a minor road branching north at x = 1000.
        val g = graph(
            way(RoadClass.HIGHWAY, 0.0 to 0.0, 1000.0 to 0.0, 3000.0 to 0.0),
            way(RoadClass.MINOR, 1000.0 to 0.0, 1000.0 to 2000.0),
        )
        val pts = follow(g, 500.0, 0.0, 90.0, 2_000.0)
        assertThat(pts.last().first).isWithin(1.0).of(2500.0)
        assertThat(pts.last().second).isWithin(1.0).of(0.0)
    }

    @Test fun atAForkTheSmallerTurnWinsOverAMarginallyHigherClass() {
        // The minor road continues straight; the major road leaves at 70 degrees: smaller turn, class not considered.
        val ang = Math.toRadians(70.0)
        val g = graph(
            way(RoadClass.MINOR, 0.0 to 0.0, 1000.0 to 0.0, 3000.0 to 0.0),
            way(RoadClass.MAJOR, 1000.0 to 0.0, 1000.0 + 2000 * Math.sin(Math.PI / 2 - ang) to 2000 * Math.cos(Math.PI / 2 - ang)),
        )
        val pts = follow(g, 500.0, 0.0, 90.0, 2_000.0)
        assertThat(pts.last().second).isWithin(1.0).of(0.0)
    }

    @Test fun whenTurnsAreSimilarTheHigherClassWinsAndTheSmallerTurnBreaksTies() {
        val a = Math.toRadians(10.0)
        val left = way(RoadClass.MEDIUM, 1000.0 to 0.0, 1000.0 + 2000 * Math.cos(a) to 2000 * Math.sin(a))
        val right = way(RoadClass.MAJOR, 1000.0 to 0.0, 1000.0 + 2000 * Math.cos(a) to -2000 * Math.sin(a))
        val g = graph(way(RoadClass.MAJOR, 0.0 to 0.0, 1000.0 to 0.0), left, right)
        assertThat(follow(g, 500.0, 0.0, 90.0, 1_500.0).last().second).isLessThan(-100.0) // major (south)
        val same = graph(
            way(RoadClass.MAJOR, 0.0 to 0.0, 1000.0 to 0.0),
            way(RoadClass.MAJOR, 1000.0 to 0.0, 1000.0 + 2000 * Math.cos(a) to 2000 * Math.sin(a)),
            way(RoadClass.MAJOR, 1000.0 to 0.0, 1000.0 + 2000 * Math.cos(2 * a) to -2000 * Math.sin(2 * a)),
        )
        assertThat(follow(same, 500.0, 0.0, 90.0, 1_500.0).last().second).isGreaterThan(100.0) // 10 degree turn beats 20
    }

    @Test fun aTJunctionTurnsOntoTheSmallerTurn() {
        // Road ends at a T; the right-hand (south) arm bends a little less than the left one.
        val g = graph(
            way(RoadClass.MAJOR, 0.0 to 0.0, 1000.0 to 0.0),
            way(RoadClass.MAJOR, 1000.0 to 1500.0, 1000.0 to 0.0, 1100.0 to -1500.0),
        )
        val pts = follow(g, 500.0, 0.0, 90.0, 2_000.0)
        assertThat(pts.last().second).isLessThan(-500.0)
    }

    @Test fun aRoundaboutLikeLoopTerminatesWithoutRepeating() {
        // Approach from the west into a ring of radius 40 m (24 vertices) with an exit east.
        val ring = (0..24).map { i -> val t = Math.PI + 2 * Math.PI * i / 24; 40.0 * Math.cos(t) to 40.0 * Math.sin(t) }
        val g = graph(
            way(RoadClass.MAJOR, -1000.0 to 0.0, -40.0 to 0.0),
            way(RoadClass.MAJOR, *ring.toTypedArray()),
            way(RoadClass.MAJOR, 40.0 to 0.0, 1500.0 to 0.0),
        )
        val p = ll(-500.0, 0.0)
        val path = g.follow(g.match(p.lat, p.lon, 90.0)!!, 3_000.0)
        assertThat(path.points).hasSize(60)
        // Whatever it chooses at the ring it ends (loop closed or exit taken) and carries on straight to the full length.
        assertThat(path.roadLengthM).isAtMost(3_000.0)
        assertThat(path.roadLengthM).isGreaterThan(400.0)
        val end = xy(path.points.last())
        assertThat(hypot(end.first, end.second)).isGreaterThan(1000.0)
    }

    @Test fun pureLoopStopsWhereItWouldRevisitAnEdge() {
        val ring = (0..40).map { i -> val t = 2 * Math.PI * i / 40; 200.0 * Math.cos(t) to 200.0 * Math.sin(t) }
        val g = graph(way(RoadClass.MINOR, *ring.toTypedArray()))
        val p = ll(200.0, 0.0)
        val path = g.follow(g.match(p.lat, p.lon, 0.0)!!, 50_000.0)
        assertThat(path.roadLengthM).isWithin(60.0).of(2 * Math.PI * 200.0)
        assertThat(path.points).hasSize(1000)
    }

    @Test fun matchingNeedsDistanceUnder30mAndHeadingWithin45() {
        val g = graph(way(RoadClass.MAJOR, 0.0 to 0.0, 2000.0 to 0.0))
        fun m(y: Double, heading: Double) = ll(1000.0, y).let { g.match(it.lat, it.lon, heading) }
        assertThat(m(25.0, 90.0)!!.distanceM).isWithin(0.5).of(25.0)
        assertThat(m(35.0, 90.0)).isNull()
        assertThat(m(0.0, 130.0)).isNotNull()
        assertThat(m(0.0, 140.0)).isNull()
        assertThat(m(0.0, 270.0)!!.forward).isFalse() // travelling west along the same road
        assertThat(m(0.0, 90.0)!!.forward).isTrue()
        assertThat(m(0.0, 0.0)).isNull() // crossing the road
    }

    @Test fun matchPrefersTheBetterAlignedRoadAmongNearlyEqualDistances() {
        // Two roads cross at the vehicle; heading picks the one it is travelling along.
        val g = graph(way(RoadClass.MINOR, 0.0 to 0.0, 2000.0 to 0.0), way(RoadClass.MAJOR, 1000.0 to -1000.0, 1000.0 to 1000.0))
        val p = ll(1000.0, 1.0)
        val east = g.match(p.lat, p.lon, 85.0)!!
        val north = g.match(p.lat, p.lon, 5.0)!!
        assertThat(east.edge).isNotEqualTo(north.edge)
    }

    @Test fun neighbouringTilesJoinEvenWhenOneEndIsClippedMidEdge() {
        // Tile A holds the road clipped at x = 1000 (dead end); tile B holds the full segment 800..1800 in one piece.
        val a = way(RoadClass.MAJOR, 0.0 to 0.0, 400.0 to 0.0, 800.0 to 0.0, 1000.0 to 0.0)
        val b = way(RoadClass.MAJOR, 800.0 to 0.0, 1800.0 to 0.0, 2600.0 to 0.0)
        val g = graph(a, b)
        val pts = follow(g, 100.0, 0.0, 90.0, 2_000.0)
        assertThat(pts.last().first).isWithin(1.0).of(2100.0)
        assertThat(pts.last().second).isWithin(1.0).of(0.0)
        // Nothing in the overlap is doubled: the 800..1000 piece exists once.
        assertThat(g.edgeCount).isEqualTo(5)
    }

    @Test fun emptyGraphMatchesNothing() {
        val g = graph()
        val p = ll(0.0, 0.0)
        assertThat(g.match(p.lat, p.lon, 0.0)).isNull()
    }
}

class TunnelRoadTest {
    @Test fun mvtBooleanIsTunnelIsExposedByTheDecoderAndTheRoadWay() {
        val tile = TestMvt.roadsTile(listOf("kind" to "highway", "is_tunnel" to true))
        assertThat(Mvt.decode(tile).single().features.single().properties["is_tunnel"]).isEqualTo(true)
        val way = RoadTileDecoder.decode(tile, 14932, 9897).single()
        assertThat(way.roadClass).isEqualTo(RoadClass.HIGHWAY)
        assertThat(way.isTunnel).isTrue()
        val plain = TestMvt.roadsTile(listOf("kind" to "highway", "is_tunnel" to false))
        assertThat(RoadTileDecoder.decode(plain, 14932, 9897).single().isTunnel).isFalse()
        assertThat(RoadTileDecoder.decode(TestMvt.roadsTile(listOf("kind" to "highway")), 14932, 9897).single().isTunnel).isFalse()
    }

    private fun tunnelWay() = RoadWay(RoadClass.HIGHWAY, "M5", listOf(ll(1000.0, 0.0), ll(1500.0, 0.0), ll(2000.0, 0.0)), isTunnel = true)
    private fun surfaceWay() = RoadWay(RoadClass.HIGHWAY, "M5", listOf(ll(0.0, 0.0), ll(500.0, 0.0), ll(1000.0, 0.0)))
    private fun graph() = RoadGraph.build(LAT0, LON0, listOf(surfaceWay(), tunnelWay()))

    @Test fun matchReportsWhetherTheEdgeIsATunnel() {
        val g = graph()
        val a = ll(1200.0, 3.0)
        val b = ll(300.0, 3.0)
        assertThat(g.match(a.lat, a.lon, 90.0)!!.isTunnel).isTrue()
        assertThat(g.match(b.lat, b.lon, 90.0)!!.isTunnel).isFalse()
    }

    @Test fun tunnelDistanceAndNearestOnRoad() {
        val g = graph()
        val near = ll(700.0, 0.0) // 300 m before the tunnel mouth
        assertThat(g.tunnelDistanceM(near.lat, near.lon, 500.0)!!).isWithin(2.0).of(300.0)
        assertThat(g.tunnelDistanceM(near.lat, near.lon, 200.0)).isNull()
        val p = ll(1700.0, 40.0)
        val (snapped, m) = g.nearestOnRoad(p.lat, p.lon, 100.0, tunnelOnly = true)!!
        assertThat(xy(snapped).second).isWithin(0.5).of(0.0)
        assertThat(xy(snapped).first).isWithin(0.5).of(1700.0)
        assertThat(m.isTunnel).isTrue()
        assertThat(g.nearestOnRoad(p.lat, p.lon, 20.0, tunnelOnly = true)).isNull()
    }
}
