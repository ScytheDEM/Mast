package au.signalatlas.feature.trip

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AlertEngineTest {
    private val zone = 5_000.0..6_999.0

    /** A road: covered everywhere except the [dead] ranges (positions along it, m). */
    private fun world(vararg dead: ClosedFloatingPointRange<Double>): (Double) -> Boolean =
        { x -> dead.none { x in it.start..it.endInclusive } }

    private class Run(val engine: AlertEngine, val speed: Double = 25.0) {
        val alerts = ArrayList<Pair<Double, Alert>>()
        var last: AlertResult? = null
        var tMs = 0L

        /** Drives from [from] to [to] m, evaluating every 2 s. */
        fun drive(world: (Double) -> Boolean, from: Double, to: Double, measured: (Double) -> Int? = { if (world(it)) -90 else null }) {
            var x = from
            while (x <= to) {
                val horizon = AlertEngine.horizonM(speed, 90)
                val p = (0..(horizon / 50).toInt()).map { if (world(x + 50.0 * it)) 0.95f else 0.02f }
                val r = engine.evaluate(AlertInput(tMs, x, speed, "Telstra", "4G", measured(x), p))
                r.alerts.forEach { alerts += x to it }
                last = r
                x += speed * 2
                tMs += 2000
            }
        }

        fun kinds() = alerts.map { it.second.kind }
    }

    @Test fun approachingADeadZoneFiresOneLeavingWithDistanceAndLength() {
        val run = Run(AlertEngine())
        run.drive(world(zone), 0.0, 4_900.0)
        assertThat(run.alerts).hasSize(1)
        val (at, a) = run.alerts.single()
        assertThat(a.kind).isEqualTo(AlertKind.LEAVING)
        // Window = 25 m/s x 90 s = 2250 m: the first evaluation with the zone inside it.
        assertThat(at).isEqualTo(2_750.0)
        assertThat(a.text).isEqualTo("Leaving Telstra 4G coverage in ~2.3 km · dead zone ~2.0 km")
        assertThat(a.distanceM).isEqualTo(2_250.0)
        assertThat(a.deadLenM).isEqualTo(2_000.0)
    }

    @Test fun insideTheZoneItShowsWhenCoverageReturnsThenSaysBackInCoverageOnce() {
        val run = Run(AlertEngine())
        run.drive(world(zone), 0.0, 5_500.0)
        assertThat(run.last!!.status.covered).isFalse()
        assertThat(run.last!!.status.text).isEqualTo("Coverage returns in ~1.5 km")
        assertThat(run.last!!.status.shortText).isEqualTo("1.5 km")

        run.drive(world(zone), 5_550.0, 9_000.0)
        assertThat(run.kinds()).containsExactly(AlertKind.LEAVING, AlertKind.BACK).inOrder()
        val back = run.alerts.last()
        assertThat(back.second.text).isEqualTo("Back in coverage")
        // Covered from 7000 m; the alert needs 200 m of it.
        assertThat(back.first).isEqualTo(7_200.0)
        assertThat(run.last!!.status.text).isEqualTo("No dead zones ahead")
    }

    @Test fun beforeTheZoneStatusNamesTheNextDeadZone() {
        val run = Run(AlertEngine())
        run.drive(world(zone), 0.0, 100.0)
        assertThat(run.last!!.status.covered).isTrue()
        assertThat(run.last!!.status.text).isEqualTo("Next dead zone in 4.9 km") // 5000 - 100
    }

    @Test fun noRepeatForTheSameZoneEvenWithWobblyPredictionsAndAShortGap() {
        // The zone's predicted start moves by up to +-50 m from one evaluation to the next, and a 100 m covered gap
        // sits at its start: still one "Leaving" and one "Back".
        val engine = AlertEngine()
        val run = Run(engine)
        val rnd = java.util.Random(3)
        var x = 0.0
        while (x <= 9_000.0) {
            val shift = (rnd.nextInt(3) - 1) * 50.0
            val w = world(5_000.0 + shift..5_099.0, 5_200.0 + shift..6_999.0)
            run.drive(w, x, x)
            x += 50.0
        }
        assertThat(run.kinds()).containsExactly(AlertKind.LEAVING, AlertKind.BACK).inOrder()
    }

    @Test fun aCoveredFlickerShorterThan200mInsideTheZoneDoesNotSayBack() {
        val run = Run(AlertEngine())
        val w = world(5_000.0..5_999.0, 6_100.0..7_999.0) // 100 m of coverage inside
        run.drive(w, 0.0, 9_000.0)
        assertThat(run.kinds()).containsExactly(AlertKind.LEAVING, AlertKind.BACK).inOrder()
        assertThat(run.alerts.last().first).isEqualTo(8_200.0)
    }

    @Test fun startingInsideADeadZoneAlertsNothingOnTheWayOut() {
        val run = Run(AlertEngine())
        run.drive(world(0.0..1_999.0), 0.0, 4_000.0)
        assertThat(run.alerts).isEmpty()
    }

    @Test fun twoSeparateZonesGetOneAlertEach() {
        val run = Run(AlertEngine())
        run.drive(world(5_000.0..5_999.0, 12_000.0..13_499.0), 0.0, 15_000.0)
        assertThat(run.kinds()).containsExactly(AlertKind.LEAVING, AlertKind.BACK, AlertKind.LEAVING, AlertKind.BACK).inOrder()
        assertThat(run.alerts[0].second.text).isEqualTo("Leaving Telstra 4G coverage in ~2.3 km · dead zone ~1.0 km")
        assertThat(run.alerts[2].second.text).isEqualTo("Leaving Telstra 4G coverage in ~2.3 km · dead zone ~1.5 km")
    }

    @Test fun deadStretchesBelowTheMinimumLengthAreIgnored() {
        val short = world(5_000.0..5_249.0) // 250 m
        assertThat(Run(AlertEngine()).also { it.drive(short, 0.0, 4_000.0) }.alerts).isEmpty()
        val lenient = Run(AlertEngine { AlertConfig(minDeadM = 200.0) }).also { it.drive(short, 0.0, 4_000.0) }
        assertThat(lenient.kinds()).containsExactly(AlertKind.LEAVING)
        assertThat(lenient.alerts.single().second.text).endsWith("dead zone ~300 m") // 5 samples x 50 m = 250 m -> 300 m
        val strict = Run(AlertEngine { AlertConfig(minDeadM = 1_000.0) }).also { it.drive(world(5_000.0..5_699.0), 0.0, 4_000.0) }
        assertThat(strict.alerts).isEmpty()
    }

    @Test fun theWarningWindowScalesWithSpeedAndIsClamped() {
        assertThat(AlertEngine.windowM(0.0, 90)).isEqualTo(500.0)
        assertThat(AlertEngine.windowM(10.0, 90)).isEqualTo(900.0)
        assertThat(AlertEngine.windowM(20.0, 60)).isEqualTo(1_200.0)
        assertThat(AlertEngine.windowM(30.0, 120)).isEqualTo(3_600.0)
        assertThat(AlertEngine.windowM(100.0, 90)).isEqualTo(5_000.0)
        assertThat(AlertEngine.horizonM(25.0, 90)).isEqualTo(12_250.0)

        // Slow (5 m/s): window 500 m, so a zone 2 km away is silent and one 400 m away alerts.
        val slow = Run(AlertEngine(), speed = 5.0)
        slow.drive(world(2_000.0..3_999.0), 0.0, 1_400.0)
        assertThat(slow.alerts).isEmpty()
        slow.drive(world(2_000.0..3_999.0), 1_500.0, 1_700.0)
        assertThat(slow.kinds()).containsExactly(AlertKind.LEAVING)
        assertThat(slow.alerts.single().second.distanceM).isEqualTo(500.0)
    }

    @Test fun aFallingSignalFiresTheEarlyAlertOnceAndTheZoneAheadStillGetsItsLeaving() {
        val all = world()
        val run = Run(AlertEngine())
        // -100 dBm, then 15 dB down over 20 s, then flat at -115: predictions say covered all along.
        run.drive(all, 0.0, 3_000.0) { x -> (-100 - 15 * ((x - 1_000) / 500).coerceIn(0.0, 1.0)).toInt() }
        assertThat(run.kinds()).containsExactly(AlertKind.EARLY)
        val (at, a) = run.alerts.single()
        assertThat(a.text).isEqualTo("Signal dropping fast")
        assertThat(at).isIn(com.google.common.collect.Range.closed(1_350.0, 1_500.0)) // drop >= 10 dB and <= -112 dBm
    }

    @Test fun aSlowDriftDoesNotTriggerTheEarlyAlert() {
        val run = Run(AlertEngine())
        run.drive(world(), 0.0, 4_000.0) { x -> (-95 - x / 200).toInt() } // 12.5 dB per km: 1 dB per 4 s evaluations is < 10 dB/30 s
        assertThat(run.alerts).isEmpty()
    }

    @Test fun earlyAlertIsRateLimitedAndOnlyRearmedByRecovery() {
        val engine = AlertEngine()
        fun eval(t: Long, odo: Double, rsrp: Int) =
            engine.evaluate(AlertInput(t, odo, 25.0, "Telstra", "4G", rsrp, List(50) { 0.95f })).alerts
        assertThat(eval(0, 0.0, -100)).isEmpty()
        assertThat(eval(10_000, 250.0, -114).map { it.kind }).containsExactly(AlertKind.EARLY)
        assertThat(eval(20_000, 500.0, -100)).isEmpty() // recovered above thr + 10? -100 >= -108: re-armed
        assertThat(eval(30_000, 750.0, -114)).isEmpty() // within 60 s of the last early alert
        assertThat(eval(75_000, 1_000.0, -100)).isEmpty()
        assertThat(eval(80_000, 1_250.0, -114).map { it.kind }).containsExactly(AlertKind.EARLY)
    }

    @Test fun leavingIsRateLimitedTo60sAndHysteresisUsesPositionNotTime() {
        val engine = AlertEngine()
        val p = List(8) { 0.9f } + List(20) { 0.1f } // dead from 400 m ahead
        fun eval(t: Long, odo: Double) = engine.evaluate(AlertInput(t, odo, 25.0, "Telstra", "4G", -90, p)).alerts
        assertThat(eval(0, 0.0).map { it.kind }).containsExactly(AlertKind.LEAVING)
        assertThat(eval(2_000, 50.0)).isEmpty() // same zone (position matches within 300 m)
        assertThat(eval(30_000, 5_000.0)).isEmpty() // a new zone, but under 60 s since the last "Leaving"
        assertThat(eval(61_000, 5_100.0).map { it.kind }).containsExactly(AlertKind.LEAVING)
    }

    @Test fun lose5GUsesMinus110AndOnlyMeasuredRsrpAboveItCounts() {
        assertThat(AlertConfig().thresholdDbm).isEqualTo(-118)
        assertThat(AlertConfig(floor = TechFloor.LOSE_4G).thresholdDbm).isEqualTo(-118)
        assertThat(AlertConfig(floor = TechFloor.LOSE_5G).thresholdDbm).isEqualTo(-110)
        val engine = AlertEngine { AlertConfig(floor = TechFloor.LOSE_5G) }
        val none = List(30) { 0.1f }
        fun status(rsrp: Int?) = engine.evaluate(AlertInput(0, 0.0, 25.0, "Telstra", "5G", rsrp, none)).status
        assertThat(status(-105).covered).isTrue() // >= -110 + 3
        assertThat(status(-108).covered).isFalse()
        assertThat(status(null).covered).isFalse()
        // The default floor accepts -108 as covered (-118 + 3 = -115).
        val all = AlertEngine()
        assertThat(all.evaluate(AlertInput(0, 0.0, 25.0, "Telstra", "4G", -108, none)).status.covered).isTrue()
        assertThat(all.evaluate(AlertInput(0, 0.0, 25.0, "Telstra", "4G", -116, none)).status.covered).isFalse()
    }

    @Test fun lose5GTextsUseTheTechLabelGiven() {
        val engine = AlertEngine { AlertConfig(floor = TechFloor.LOSE_5G) }
        val p = List(5) { 0.9f } + List(20) { 0.1f }
        val a = engine.evaluate(AlertInput(0, 0.0, 25.0, "Optus", "5G", -90, p)).alerts.single()
        assertThat(a.text).isEqualTo("Leaving Optus 5G coverage in ~300 m · dead zone ~1.0 km")
    }

    @Test fun textsRoundTo100mBelowAKilometreAndTo0_1kmAbove() {
        val cases = mapOf(
            0.0 to "100 m", 40.0 to "100 m", 149.0 to "100 m", 150.0 to "200 m", 849.0 to "800 m", 850.0 to "900 m",
            949.0 to "900 m", 950.0 to "1.0 km", 1_049.0 to "1.0 km", 1_840.0 to "1.8 km", 1_850.0 to "1.9 km", 6_200.0 to "6.2 km",
            12_345.0 to "12.3 km",
        )
        for ((m, text) in cases) assertThat(TripText.distance(m)).isEqualTo(text)
        assertThat(TripText.spoken(1_800.0)).isEqualTo("1.8 kilometres")
        assertThat(TripText.spoken(800.0)).isEqualTo("800 metres")
        assertThat(TripText.spoken(1_000.0)).isEqualTo("1 kilometre")
        assertThat(TripText.spoken(3_000.0)).isEqualTo("3 kilometres")
    }

    @Test fun noCoverageAheadWhileInsideTheZoneIsReported() {
        val engine = AlertEngine()
        val r = engine.evaluate(AlertInput(0, 0.0, 25.0, "Telstra", "4G", null, List(100) { 0.0f }))
        assertThat(r.status.text).isEqualTo("No coverage ahead")
        assertThat(r.status.shortText).isNull()
        // A covered stretch under 300 m does not count as returning.
        val p = List(10) { 0.0f } + List(5) { 0.9f } + List(100) { 0.0f }
        assertThat(engine.evaluate(AlertInput(1, 0.0, 25.0, "Telstra", "4G", null, p)).status.returnsInM).isNull()
    }
}
