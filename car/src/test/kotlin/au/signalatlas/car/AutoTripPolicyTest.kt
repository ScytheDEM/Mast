package au.signalatlas.car

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutoTripPolicyTest {
    private class Trip : TripControl {
        override var isRunning = false
        var starts = 0
        var stops = 0
        override fun start() { isRunning = true; starts++ }
        override fun stop() { isRunning = false; stops++ }
    }

    private val trip = Trip()
    private val policy = AutoTripPolicy(trip)

    @Test fun projectionStartsAndDisconnectStops() {
        policy.onConnection(AutoTripPolicy.PROJECTION)
        assertThat(trip.isRunning).isTrue()
        policy.onConnection(AutoTripPolicy.NOT_CONNECTED)
        assertThat(trip.isRunning).isFalse()
    }

    @Test fun manualStartSurvivesDisconnect() {
        policy.onConnection(AutoTripPolicy.PROJECTION)
        policy.manualStop()
        policy.manualStart()
        policy.onConnection(AutoTripPolicy.NOT_CONNECTED)
        assertThat(trip.isRunning).isTrue()
        assertThat(trip.stops).isEqualTo(1)
    }

    @Test fun tripAlreadyRunningIsLeftAlone() {
        trip.isRunning = true
        policy.onConnection(AutoTripPolicy.PROJECTION)
        policy.onConnection(AutoTripPolicy.NOT_CONNECTED)
        assertThat(trip.isRunning).isTrue()
        assertThat(trip.starts).isEqualTo(0)
    }

    @Test fun nativeConnectionDoesNothing() {
        policy.onConnection(1)
        assertThat(trip.starts).isEqualTo(0)
    }

    @Test fun repeatedProjectionDoesNotRestart() {
        policy.onConnection(AutoTripPolicy.PROJECTION)
        policy.onConnection(AutoTripPolicy.PROJECTION)
        assertThat(trip.starts).isEqualTo(1)
    }

    @Test fun disconnectAfterTripEndedByUserStopsNothing() {
        policy.onConnection(AutoTripPolicy.PROJECTION)
        trip.isRunning = false
        policy.onConnection(AutoTripPolicy.NOT_CONNECTED)
        assertThat(trip.stops).isEqualTo(0)
    }
}
