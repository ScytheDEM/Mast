package au.signalatlas.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator

/** The Android Auto entry point; the category it declares differs per flavour (see each flavour's manifest). */
class SignalAtlasCarService : CarAppService() {
    // The Play-signed Android Auto hosts are allowed; a debug build also allows any host so the DHU can connect.
    override fun createHostValidator(): HostValidator =
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = SignalAtlasCarSession()
}

class SignalAtlasCarSession : Session() {
    private var policy: AutoTripPolicy? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val source = CarRuntime.source ?: return UnavailableScreen(carContext)
        val control = object : TripControl {
            override val isRunning: Boolean get() = source.trip.value.running
            override fun start() = source.startTrip()
            override fun stop() = source.stopTrip()
        }
        val policy = AutoTripPolicy(control).also { this.policy = it }
        CarSessionWiring(this, carContext, source, policy).attach()
        return rootScreen(carContext, source, policy)
    }
}

private class UnavailableScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template =
        MessageTemplate.Builder("Open Mast on your phone once, then reconnect.")
            .setTitle("Mast").setHeaderAction(Action.APP_ICON).build()
}
