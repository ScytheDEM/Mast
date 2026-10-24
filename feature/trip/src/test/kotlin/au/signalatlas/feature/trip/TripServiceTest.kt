package au.signalatlas.feature.trip

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import au.signalatlas.core.radio.RadioState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TripServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val job = Job()
    private lateinit var controller: TripController
    private val calls = ArrayList<String>()

    @Before fun setUp() {
        shadowOf(context.getSystemService(LocationManager::class.java)).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        controller = TripController(
            CoroutineScope(Dispatchers.Unconfined + job), { p, _, _ -> p.map { au.signalatlas.core.coverage.PointPrediction.NONE } },
            MutableStateFlow(RadioState.EMPTY), MutableStateFlow(emptyMap()), MutableStateFlow(null), MemoryTripSettings(), { null },
            TripHooks({}, {}, {}, {}, {}, { calls += "launch" }, { calls += "halt" }),
            evalIntervalMs = 3_600_000, elapsedMs = { 0 },
        )
        TripRuntime.controller = controller
    }

    @After fun tearDown() {
        TripRuntime.controller = null
        job.cancel()
    }

    private fun listeners() = shadowOf(context.getSystemService(LocationManager::class.java)).let { it.getRequestLocationUpdateListeners() }

    @Test fun startRequestsLocationAndTheStopActionRemovesItAndStopsTheService() {
        controller.start()
        val svc = Robolectric.buildService(TripService::class.java, Intent(context, TripService::class.java))
        svc.create().startCommand(0, 1)
        assertThat(listeners()).hasSize(1)
        assertThat(shadowOf(svc.get()).isForegroundStopped).isFalse()

        svc.get().onStartCommand(Intent(context, TripService::class.java).setAction(TripService.ACTION_STOP), 0, 2)
        assertThat(listeners()).isEmpty()
        assertThat(controller.isRunning).isFalse()
        assertThat(shadowOf(svc.get()).isStoppedBySelf).isTrue()
        assertThat(shadowOf(svc.get()).isForegroundStopped).isTrue()
    }

    @Test fun aTripStoppedElsewhereStopsTheServiceAndItsLocationRequest() {
        controller.start()
        val svc = Robolectric.buildService(TripService::class.java, Intent(context, TripService::class.java))
        svc.create().startCommand(0, 1)
        assertThat(listeners()).hasSize(1)
        controller.stop() // the tile or the map button
        val end = System.currentTimeMillis() + 3_000
        while (!shadowOf(svc.get()).isStoppedBySelf && System.currentTimeMillis() < end) Thread.sleep(10)
        assertThat(shadowOf(svc.get()).isStoppedBySelf).isTrue()
        assertThat(listeners()).isEmpty()
    }

    @Test fun destroyingTheServiceRemovesUpdatesAndEndsTheTrip() {
        controller.start()
        val svc = Robolectric.buildService(TripService::class.java, Intent(context, TripService::class.java))
        svc.create().startCommand(0, 1)
        svc.destroy()
        assertThat(listeners()).isEmpty()
        assertThat(controller.isRunning).isFalse()
        // Stationary fixes after stopping cannot re-register a request.
        assertThat(listeners()).isEmpty()
    }

    @Test fun aServiceStartedWithoutARunningTripStopsAtOnce() {
        val svc = Robolectric.buildService(TripService::class.java, Intent(context, TripService::class.java))
        svc.create().startCommand(0, 1)
        assertThat(shadowOf(svc.get()).isStoppedBySelf).isTrue()
        assertThat(listeners()).isEmpty()
    }
}
