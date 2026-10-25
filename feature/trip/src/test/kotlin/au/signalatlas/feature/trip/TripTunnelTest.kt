package au.signalatlas.feature.trip

import au.signalatlas.core.beacons.BeaconLearner
import au.signalatlas.core.beacons.BeaconScanner
import au.signalatlas.core.beacons.BeaconSnapper
import au.signalatlas.core.beacons.BeaconSighting
import au.signalatlas.core.beacons.BeaconRepository
import au.signalatlas.core.beacons.KnownBeacon
import au.signalatlas.core.calibration.Fix
import au.signalatlas.core.coverage.PointPrediction
import au.signalatlas.core.model.LatLon
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import au.signalatlas.core.radio.RadioState
import au.signalatlas.core.roads.RoadClass
import au.signalatlas.core.roads.RoadGraphProvider
import au.signalatlas.core.roads.RoadSource
import au.signalatlas.core.roads.RoadWay
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test

class TripTunnelTest {
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)
    private val published = ArrayList<Fix>()
    private var now = 1_000_000L

    @After fun tearDown() { job.cancel() }

    private val ways = listOf(
        RoadWay(RoadClass.HIGHWAY, null, (0..1000 step 250).map { fromXY(it.toDouble(), 0.0) }),
        RoadWay(RoadClass.HIGHWAY, null, (1000..3000 step 250).map { fromXY(it.toDouble(), 0.0) }, isTunnel = true),
        RoadWay(RoadClass.HIGHWAY, null, (3000..6000 step 250).map { fromXY(it.toDouble(), 0.0) }),
    )
    private val source = object : RoadSource {
        override suspend fun roads(x: Int, y: Int) = ways
    }

    private class FakeScanner : BeaconScanner {
        var starts = 0
        var stops = 0
        var listener: ((BeaconSighting) -> Unit)? = null
        override var scanning = false
        override fun start(onSighting: (BeaconSighting) -> Unit) { starts++; scanning = true; listener = onSighting }
        override fun stop() { stops++; scanning = false }
    }

    private val store = object : BeaconRepository {
        private val map = HashMap<String, KnownBeacon>()
        override fun get(id: String) = map[id]
        override fun put(b: KnownBeacon) { map[b.id] = b }
    }
    private val scanner = FakeScanner()
    private val beaconsEnabled = MutableStateFlow(true)
    private val support = BeaconSupport(beaconsEnabled, scanner, BeaconSnapper(store), BeaconLearner(store))
    private var elapsed = 5_000L

    private fun controller(beacons: BeaconSupport? = support) = TripController(
        scope,
        { pts, _, _ -> pts.map { PointPrediction(-90f, 8f, 0.95f, Network.TELSTRA, Tech.LTE) } },
        MutableStateFlow(RadioState.EMPTY), MutableStateFlow(emptyMap()), MutableStateFlow(null), MemoryTripSettings(), { Network.TELSTRA },
        TripHooks({}, {}, {}, {}, { published += it }, {}, {}),
        clockMs = { now }, elapsedMs = { elapsed }, evalIntervalMs = 3_600_000, deadReckonIntervalMs = 3_600_000,
        roads = RoadGraphProvider(source, scope, radiusM = 3_000.0), beacons = beacons,
    )

    /** East at 20 m/s from x = [fromX] for [seconds], one fix a second with 5 m accuracy. */
    private fun TripController.drive(fromX: Double, seconds: Int, acc: Double = 5.0) {
        for (t in 0..seconds) {
            val p = fromXY(fromX + 20.0 * t, 0.0)
            now += 1000
            onLocation(TripFix(now, p.lat, p.lon, acc, 20.0))
        }
    }

    private fun xOf(f: Fix) = toXY(LatLon(f.lat, f.lon)).first

    @Test fun tunnelMatchSetsTheFlagAndLostGpsPublishesDeadReckonedFixes() {
        val c = controller()
        c.start()
        c.drive(500.0, 10) // approach on the open road
        c.onLocation(TripFix(now, fromXY(0.0, 0.0).lat, fromXY(0.0, 0.0).lon, 5.0, 20.0)) // warm the graph at the origin
        assertThat(c.tunnel.value).isFalse()
        c.drive(1_000.0, 5) // inside the tunnel, GPS fine: matched to the tunnel road
        assertThat(c.tunnel.value).isTrue()
        published.clear()

        now += 8_000 // GPS goes quiet for 8 s
        c.deadReckonOnce()
        val est = published.single()
        assertThat(est.deadReckoned).isTrue()
        assertThat(est.accuracyM).isGreaterThan(50.0)
        // Last fix at x = 1100, 20 m/s, 8 s since: about 1260.
        assertThat(xOf(est)).isWithin(15.0).of(1_260.0)
        assertThat(c.tunnel.value).isTrue()
        assertThat((c.state.value as TripState.Running).stats.estimated).isFalse() // stats refresh with the next evaluation
        kotlinx.coroutines.runBlocking { c.evaluateOnce() }
        val stats = (c.state.value as TripState.Running).stats
        assertThat(stats.estimated).isTrue()
        assertThat(stats.inTunnel).isTrue()
        assertThat(stats.message).isNull()

        // Real GPS returns: published as a normal fix again, estimate off.
        published.clear()
        val back = fromXY(1_400.0, 0.0)
        now += 4_000
        c.onLocation(TripFix(now, back.lat, back.lon, 8.0, 20.0))
        assertThat(published.single().deadReckoned).isFalse()
        c.deadReckonOnce()
        assertThat(published).hasSize(1)
    }

    @Test fun poorFixesInsideATunnelAreNotPublished() {
        val c = controller()
        c.start()
        c.drive(1_000.0, 5)
        published.clear()
        val p = fromXY(1_200.0, 0.0)
        now += 1_000
        c.onLocation(TripFix(now, p.lat, p.lon, 90.0, 20.0))
        assertThat(published).isEmpty()
    }

    @Test fun noRoadsMeansNoDeadReckoning() {
        val c = TripController(
            scope, { pts, _, _ -> pts.map { PointPrediction(-90f, 8f, 0.95f, Network.TELSTRA, Tech.LTE) } },
            MutableStateFlow(RadioState.EMPTY), MutableStateFlow(emptyMap()), MutableStateFlow(null), MemoryTripSettings(), { Network.TELSTRA },
            TripHooks({}, {}, {}, {}, { published += it }, {}, {}), clockMs = { now }, elapsedMs = { elapsed }, evalIntervalMs = 3_600_000,
        )
        c.start()
        c.drive(1_000.0, 5)
        published.clear()
        now += 10_000
        c.deadReckonOnce()
        assertThat(published).isEmpty()
        assertThat(c.tunnel.value).isFalse()
    }

    @Test fun beaconScanningRunsOnlyNearATunnelWithTheSettingOnDuringATrip() {
        val c = controller()
        c.drive(0.0, 1) // not running: ignored
        c.start()
        c.drive(0.0, 2) // 0 m: 1 km from the tunnel mouth
        c.deadReckonOnce()
        assertThat(scanner.scanning).isFalse()
        c.drive(600.0, 2) // 600 m: within 500 m of the mouth at 1000
        c.deadReckonOnce()
        assertThat(scanner.scanning).isTrue()
        assertThat(scanner.starts).isEqualTo(1)
        beaconsEnabled.value = false
        c.deadReckonOnce()
        assertThat(scanner.scanning).isFalse()
        beaconsEnabled.value = true
        c.deadReckonOnce()
        assertThat(scanner.scanning).isTrue()
        c.stop()
        assertThat(scanner.scanning).isFalse()
    }

    @Test fun beaconsAreLearnedFromGoodGpsAndSnapTheEstimateInTheTunnel() {
        val c = controller()
        c.start()
        c.drive(700.0, 1)
        c.deadReckonOnce() // starts the scan
        // Learning: strong sighting while the fix is good (5 m).
        c.drive(900.0, 1)
        scanner.listener!!.invoke(BeaconSighting("eurl:t1", -60, elapsed))
        assertThat(store.get("eurl:t1")).isNotNull()
        assertThat(store.get("eurl:t1")!!.source).isEqualTo(KnownBeacon.Source.LEARNED)
        // A sighting with a poor fix is not learned.
        c.drive(1_000.0, 1, acc = 40.0)
        scanner.listener!!.invoke(BeaconSighting("eurl:t2", -60, elapsed))
        assertThat(store.get("eurl:t2")).isNull()

        // Known imported beacon at x = 1800 heard at -55 dBm while dead-reckoning from ~1,100.
        store.put(KnownBeacon("eurl:t3", fromXY(1_800.0, 40.0), KnownBeacon.Source.IMPORTED))
        c.drive(1_000.0, 5)
        now += 6_000
        scanner.listener!!.invoke(BeaconSighting("eurl:t3", -55, elapsed))
        published.clear()
        c.deadReckonOnce()
        // Unsnapped it would be near 1,100 + 120 = 1,220; the beacon (projected onto the road) pulls it to 1,800.
        assertThat(xOf(published.single())).isWithin(15.0).of(1_800.0)
    }
}
