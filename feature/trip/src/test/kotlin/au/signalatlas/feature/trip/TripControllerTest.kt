package au.signalatlas.feature.trip

import au.signalatlas.core.calibration.Fix
import au.signalatlas.core.coverage.CoverageSelection
import au.signalatlas.core.coverage.PointPrediction
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import au.signalatlas.core.radio.ObservedCell
import au.signalatlas.core.radio.Permission
import au.signalatlas.core.radio.RadioState
import au.signalatlas.core.radio.SimInfo
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

class TripControllerTest {
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)
    private val calls = ArrayList<String>()
    private val published = ArrayList<Fix>()
    private var now = 1_000_000L
    private val selections = ArrayList<CoverageSelection>()
    private val thresholds = ArrayList<Float>()

    private val radio = MutableStateFlow(
        RadioState.EMPTY.copy(
            sim = SimInfo(1, Network.TELSTRA, "Telstra", "50501", false),
            serving = ObservedCell(Tech.LTE, 100, 800.0, "B28", 1, 2, 3, -90, null, null, null, true),
            updatedAtElapsedMs = 5_000,
        ),
    )
    private val settings = MemoryTripSettings()
    private var fallback: Network? = null

    /** Dead between 4 and 5.5 km east of the origin. */
    private val predictor = PathPredictor { points, sel, thr ->
        selections += sel
        thresholds += thr
        points.map { p ->
            val dead = toXY(p).first in 4_000.0..5_500.0
            PointPrediction(if (dead) -130f else -90f, 8f, if (dead) 0.02f else 0.95f, Network.TELSTRA, Tech.LTE)
        }
    }

    private fun controller() = TripController(
        scope, predictor, radio, MutableStateFlow(emptyMap()), MutableStateFlow(null), settings, { fallback },
        TripHooks(
            { calls += "radio+" }, { calls += "radio-" }, { calls += "logger+" }, { calls += "logger-" },
            { published += it }, { calls += "service+" }, { calls += "service-" },
        ),
        clockMs = { now }, elapsedMs = { 6_000 }, evalIntervalMs = 3_600_000,
    )

    @After fun tearDown() { job.cancel() }

    /** Drives east at 25 m/s for seconds [from]..[to], with GPS speed. */
    private fun TripController.drive(to: Int, from: Int = 0, speed: Double? = 25.0) {
        for (t in from..to) {
            val p = fromXY(t * 25.0, 0.0)
            now = 1_000_000L + t * 1000L
            onLocation(TripFix(now, p.lat, p.lon, 5.0, speed))
        }
    }

    private fun evaluate(c: TripController) = runBlocking { c.evaluateOnce() }
    private fun stats(c: TripController) = (c.state.value as TripState.Running).stats

    @Test fun startRunsRadioLoggerAndServiceAndStopUndoesThem() {
        val c = controller()
        assertThat(c.state.value).isEqualTo(TripState.Idle)
        c.start()
        assertThat(calls).containsExactly("radio+", "logger+", "service+").inOrder()
        assertThat(stats(c).message).isEqualTo(TripController.WAITING_GPS)
        c.start() // idempotent
        assertThat(calls).hasSize(3)
        c.stop()
        assertThat(c.state.value).isEqualTo(TripState.Idle)
        assertThat(calls.drop(3)).containsExactly("logger-", "radio-", "service-").inOrder()
        c.stop()
        assertThat(calls).hasSize(6)
    }

    @Test fun serviceGoneEndsTheTripWithoutStoppingTheServiceAgain() {
        val c = controller()
        c.start()
        calls.clear()
        c.onServiceGone()
        assertThat(c.state.value).isEqualTo(TripState.Idle)
        assertThat(calls).containsExactly("logger-", "radio-").inOrder()
    }

    @Test fun fixesGoToTheCalibrationLoggerAndAreIgnoredWhenIdle() {
        val c = controller()
        c.onLocation(TripFix(now, -33.0, 151.0, 5.0, 10.0))
        assertThat(published).isEmpty()
        c.start()
        c.onLocation(TripFix(now, -33.0, 151.0, 7.0, 10.0))
        assertThat(published).containsExactly(Fix(-33.0, 151.0, 7.0, now))
    }

    @Test fun approachingADeadZoneAlertsAndTheBannerNamesIt() {
        val c = controller()
        val alerts = ArrayList<Alert>()
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) { c.alerts.collect { alerts += it } }
        c.start()
        c.drive(40)
        evaluate(c)
        val s = stats(c)
        assertThat(s.network).isEqualTo("Telstra")
        assertThat(s.tech).isEqualTo("all data")
        assertThat(s.distanceM).isWithin(30.0).of(1_000.0)
        // Window 25 m/s x 90 s = 2250 m; the dead stretch starts 3 km ahead (at 1000 m now): not yet.
        assertThat(alerts).isEmpty()
        assertThat(s.banner).startsWith("Trip · Telstra · all data · next dead zone in 3.")

        // 800 m further on the stretch is 2.2 km ahead: inside the window.
        c.drive(72, from = 41)
        evaluate(c)
        assertThat(alerts.map { it.kind }).containsExactly(AlertKind.LEAVING)
        assertThat(alerts.single().text).startsWith("Leaving Telstra 4G coverage in ~2.")
        assertThat(stats(c).alertCount).isEqualTo(1)
        evaluate(c)
        assertThat(alerts).hasSize(1) // no repeat
        collector.cancel()
        // The selection and threshold follow the SIM and the settings.
        assertThat(selections.last().network).isEqualTo(Network.TELSTRA)
        assertThat(selections.last().techs).containsExactly(Tech.LTE, Tech.NR)
        assertThat(thresholds.last()).isEqualTo(-118f)
    }

    @Test fun lose5GAsksForNrCarriersAtMinus110() {
        settings.update { it.copy(floor = TechFloor.LOSE_5G) }
        val c = controller()
        c.start()
        c.drive(40)
        evaluate(c)
        assertThat(selections.last().techs).containsExactly(Tech.NR)
        assertThat(thresholds.last()).isEqualTo(-110f)
        assertThat(stats(c).tech).isEqualTo("5G")
    }

    @Test fun noFixOrAStaleFixWaitsForGps() {
        val c = controller()
        c.start()
        evaluate(c)
        assertThat(stats(c).message).isEqualTo(TripController.WAITING_GPS)
        c.drive(5)
        now += 20_000
        evaluate(c)
        assertThat(stats(c).message).isEqualTo(TripController.WAITING_GPS)
    }

    @Test fun withoutAnyNetworkItAsksForOneAndTheMapSelectionIsTheFallback() {
        radio.value = RadioState.EMPTY.copy(permission = Permission.MISSING_PHONE)
        val c = controller()
        c.start()
        c.drive(40)
        evaluate(c)
        assertThat(stats(c).message).isEqualTo(TripController.CHOOSE_NETWORK)
        fallback = Network.OPTUS
        evaluate(c)
        assertThat(stats(c).network).isEqualTo("Optus")
        assertThat(selections.last().network).isEqualTo(Network.OPTUS)
    }

    @Test fun aPredictorFailureIsReportedNotThrown() {
        val c = TripController(
            scope, { _, _, _ -> error("engine") }, radio, MutableStateFlow(emptyMap()), MutableStateFlow(null), settings, { null },
            TripHooks({}, {}, {}, {}, {}, {}, {}), clockMs = { now }, elapsedMs = { 6_000 }, evalIntervalMs = 3_600_000,
        )
        c.start()
        c.drive(10)
        evaluate(c)
        assertThat(stats(c).message).isEqualTo("Coverage unavailable")
    }

    @Test fun stationaryAfter60sUnderOneMetrePerSecondAndMovingAgainAtOnce() {
        val c = controller()
        c.start()
        for (t in 0..59) c.onLocation(TripFix(1_000_000L + t * 1000L, -33.0, 151.0, 5.0, 0.2))
        assertThat(c.stationary.value).isFalse() // 59 s
        c.onLocation(TripFix(1_060_000L, -33.0, 151.0, 5.0, 0.2))
        assertThat(c.stationary.value).isTrue()
        c.onLocation(TripFix(1_065_000L, -33.0, 151.0, 5.0, 4.0))
        assertThat(c.stationary.value).isFalse()
        c.onLocation(TripFix(1_070_000L, -33.0, 151.0, 5.0, 0.2))
        assertThat(c.stationary.value).isFalse() // the 60 s count starts again
    }

    @Test fun stationaryDetectorNeedsContinuousSlowSpeed() {
        val d = StationaryDetector()
        assertThat(d.onSpeed(0, 0.5)).isFalse()
        assertThat(d.onSpeed(30_000, 0.9)).isFalse()
        assertThat(d.onSpeed(40_000, 3.0)).isFalse()
        assertThat(d.onSpeed(50_000, 0.5)).isFalse()
        assertThat(d.onSpeed(109_000, 0.5)).isFalse()
        assertThat(d.onSpeed(110_000, 0.5)).isTrue()
    }

    @Test fun theBannerForQuietAndDeadStates() {
        val covered = TripStats(0, 0.0, "Telstra", "4G", TripStatus(true, null, null, null, emptyList(), 0.0))
        assertThat(covered.banner).isEqualTo("Trip · Telstra · 4G · No dead zones ahead")
        val dead = TripStats(0, 0.0, "Telstra", "4G", TripStatus(false, null, null, 1_500.0, emptyList(), 0.0))
        assertThat(dead.banner).isEqualTo("Trip · Telstra · 4G · Coverage returns in ~1.5 km")
        assertThat(TripStats(0, message = "Waiting for GPS…").banner).isEqualTo("Trip · Waiting for GPS…")
    }
}
