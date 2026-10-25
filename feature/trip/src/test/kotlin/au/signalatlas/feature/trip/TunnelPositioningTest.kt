package au.signalatlas.feature.trip

import au.signalatlas.core.beacons.SnapTarget
import au.signalatlas.core.model.LatLon
import au.signalatlas.core.roads.RoadClass
import au.signalatlas.core.roads.RoadGraph
import au.signalatlas.core.roads.RoadWay
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TunnelPositioningTest {
    /** East-west highway: open road 0..1000 m, tunnel 1000..3000 m, open road 3000..4000 m. */
    private val graph = RoadGraph.build(
        LAT0, LON0,
        listOf(
            RoadWay(RoadClass.HIGHWAY, null, listOf(fromXY(0.0, 0.0), fromXY(500.0, 0.0), fromXY(1000.0, 0.0))),
            RoadWay(RoadClass.HIGHWAY, null, (1000..3000 step 250).map { fromXY(it.toDouble(), 0.0) }, isTunnel = true),
            RoadWay(RoadClass.HIGHWAY, null, listOf(fromXY(3000.0, 0.0), fromXY(3500.0, 0.0), fromXY(4000.0, 0.0))),
        ),
    )

    private fun fixAt(t: Long, x: Double, acc: Double = 5.0, speed: Double? = 20.0): TripFix {
        val p = fromXY(x, 0.0)
        return TripFix(t, p.lat, p.lon, acc, speed)
    }

    private fun TunnelPositioning.enterTunnelAt1100(): Long {
        val t0 = 1_000_000L
        assertThat(onFix(fixAt(t0 - 5_000, 1_000.0), graph, 90.0, 20.0)).isTrue()
        assertThat(onFix(fixAt(t0, 1_100.0), graph, 90.0, 20.0)).isTrue()
        return t0
    }

    @Test fun noEstimateWhileGpsKeepsArriving() {
        val p = TunnelPositioning()
        val t0 = p.enterTunnelAt1100()
        assertThat(p.tick(t0 + 1_000, graph, null)).isNull()
        assertThat(p.inTunnel(t0 + 1_000)).isTrue()
    }

    @Test fun lostGpsInATunnelDeadReckonsAlongTheRoadAtTheLastSpeed() {
        val p = TunnelPositioning()
        val t0 = p.enterTunnelAt1100()
        val est = p.tick(t0 + 10_000, graph, null)!!
        // 20 m/s for 10 s from x = 1100.
        assertThat(toXY(LatLon(est.lat, est.lon)).first).isWithin(6.0).of(1_300.0)
        assertThat(toXY(LatLon(est.lat, est.lon)).second).isWithin(1.0).of(0.0)
        assertThat(est.accuracyM).isGreaterThan(TunnelPositioning.GOOD_ACCURACY_M)
        assertThat(est.speedMps).isWithin(1e-6).of(20.0)
        assertThat(p.active).isTrue()
        assertThat(p.inTunnel(t0 + 10_000)).isTrue()
    }

    @Test fun speedHoldsFor30sThenDecaysAndTheEstimateEndsAfterTwoMinutes() {
        val p = TunnelPositioning()
        val t0 = p.enterTunnelAt1100()
        val at30 = toXY(p.tick(t0 + 30_000, graph, null)!!.let { LatLon(it.lat, it.lon) }).first
        assertThat(at30).isWithin(6.0).of(1_700.0)
        val at60 = p.tick(t0 + 60_000, graph, null)!!
        val at90 = p.tick(t0 + 90_000, graph, null)!!
        val x60 = toXY(LatLon(at60.lat, at60.lon)).first
        val x90 = toXY(LatLon(at90.lat, at90.lon)).first
        // The decay tail adds at most speed * tau = 200 m, and it is nearly spent by 90 s.
        assertThat(x60).isLessThan(1_700.0 + 200.0 + 1.0)
        assertThat(x90 - x60).isLessThan(15.0)
        assertThat(at90.speedMps).isLessThan(2.0)
        assertThat(p.tick(t0 + 121_000, graph, null)).isNull()
        assertThat(p.active).isFalse()
    }

    @Test fun poorAccuracyFixesAreIgnoredInATunnelButNotOutside() {
        val p = TunnelPositioning()
        val t0 = p.enterTunnelAt1100()
        assertThat(p.onFix(fixAt(t0 + 3_000, 1_160.0, acc = 120.0), graph, 90.0, 20.0)).isFalse()
        assertThat(p.tick(t0 + 3_000, graph, null)).isNotNull()

        val open = TunnelPositioning()
        open.onFix(fixAt(0, 200.0), graph, 90.0, 20.0)
        assertThat(open.onFix(fixAt(1_000, 260.0, acc = 120.0), graph, 90.0, 20.0)).isTrue()
        assertThat(open.tick(10_000, graph, null)).isNull() // not on a tunnel road: nothing estimated
    }

    @Test fun goodFixResumesRealGpsAndEndsTheEstimate() {
        val p = TunnelPositioning()
        val t0 = p.enterTunnelAt1100()
        p.tick(t0 + 6_000, graph, null)!!
        assertThat(p.active).isTrue()
        assertThat(p.onFix(fixAt(t0 + 7_000, 1_250.0), graph, 90.0, 20.0)).isTrue()
        assertThat(p.active).isFalse()
        assertThat(p.tick(t0 + 7_500, graph, null)).isNull()
    }

    @Test fun gpsLostJustAfterTheTunnelMouthStillCountsAsATunnelForTenSeconds() {
        val p = TunnelPositioning()
        p.onFix(fixAt(0, 1_050.0), graph, 90.0, 20.0)
        assertThat(p.inTunnel(9_000)).isTrue()
        assertThat(p.inTunnel(10_500)).isFalse()
        val surface = TunnelPositioning()
        surface.onFix(fixAt(0, 300.0), graph, 90.0, 20.0)
        assertThat(surface.inTunnel(1_000)).isFalse()
    }

    @Test fun beaconSnapPullsTheEstimateTowardTheBeaconAlongTheRoad() {
        val p = TunnelPositioning()
        val t0 = p.enterTunnelAt1100()
        val beaconAt = fromXY(1_500.0, 0.0)
        // Without a beacon the estimate at +10 s is near x = 1300; a full-strength beacon at 1500 pulls it there.
        val snapped = p.tick(t0 + 10_000, graph, SnapTarget("b", -55, beaconAt, 1.0))!!
        assertThat(toXY(LatLon(snapped.lat, snapped.lon)).first).isWithin(6.0).of(1_500.0)
        // The offset sticks: the next second is 20 m further on from there.
        val next = p.tick(t0 + 11_000, graph, null)!!
        assertThat(toXY(LatLon(next.lat, next.lon)).first).isWithin(6.0).of(1_520.0)
        // A half-strength pull moves halfway.
        val half = TunnelPositioning()
        half.enterTunnelAt1100()
        val h = half.tick(t0 + 10_000, graph, SnapTarget("b", -70, beaconAt, 0.5))!!
        assertThat(toXY(LatLon(h.lat, h.lon)).first).isWithin(8.0).of(1_400.0)
    }
}
