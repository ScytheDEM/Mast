package au.signalatlas.car

import androidx.car.app.model.PaneTemplate
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalScreenTest {
    private val ctx = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())
    private val source = FakeSource(listOf(sim(1), sim(2, carrier = "Optus")))
    private val trip = object : TripControl {
        override val isRunning get() = source.trip.value.running
        override fun start() = source.startTrip()
        override fun stop() = source.stopTrip()
    }
    private val policy = AutoTripPolicy(trip)

    @Test fun templateHasRowsPerSimAndTripAction() {
        val template = SignalScreen(ctx, source, policy).onGetTemplate() as PaneTemplate
        val rows = template.pane!!.rows
        assertThat(rows).hasSize(3)
        assertThat(rows[0].title.toString()).isEqualTo("SIM 1 · Telstra   5G+ · n78")
        assertThat(rows[1].title.toString()).startsWith("SIM 2 · Optus")
        assertThat(template.pane!!.actions[0].title.toString()).isEqualTo("Start trip")
    }

    @Test fun tripActionStartsThenStops() {
        val screen = SignalScreen(ctx, source, policy)
        (screen.onGetTemplate() as PaneTemplate).pane!!.actions[0].onClickDelegate!!.sendClick(object : androidx.car.app.OnDoneCallback {
            override fun onSuccess(response: androidx.car.app.serialization.Bundleable?) = Unit
            override fun onFailure(response: androidx.car.app.serialization.Bundleable) = Unit
        })
        assertThat(source.starts).isEqualTo(1)
        val stop = (screen.onGetTemplate() as PaneTemplate).pane!!.actions[0]
        assertThat(stop.title.toString()).isEqualTo("Stop trip")
    }
}
