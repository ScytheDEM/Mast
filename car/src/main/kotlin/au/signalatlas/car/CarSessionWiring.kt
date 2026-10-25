package au.signalatlas.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Session
import androidx.car.app.AppManager
import androidx.car.app.connection.CarConnection
import androidx.car.app.model.Alert
import androidx.car.app.model.CarText
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * The session's side effects: the radio runs while the car UI is visible, trip alerts become car Alerts, and the
 * projection connection starts and stops the trip through [policy].
 *
 * Speech is not added here: a running trip already speaks its alerts through the trip service's TextToSpeech, which
 * uses the navigation-guidance audio usage that Android Auto routes to the car speakers and ducks media for.
 */
class CarSessionWiring(
    private val session: Session,
    private val carContext: CarContext,
    private val source: CarDataSource,
    private val policy: AutoTripPolicy,
) {
    private var alertId = 0

    fun attach() {
        session.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = source.setActive(true)
            override fun onStop(owner: LifecycleOwner) = source.setActive(false)
        })
        session.lifecycleScope.launch { source.alerts.collect(::show) }
        CarConnection(carContext).type.observe(session) { policy.onConnection(it) }
    }

    private fun show(text: String) {
        if (carContext.carAppApiLevel >= 5) {
            val alert = Alert.Builder(++alertId, CarText.create(text), ALERT_MS).build()
            carContext.getCarService(AppManager::class.java).showAlert(alert)
        } else {
            CarToast.makeText(carContext, text, CarToast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val ALERT_MS = 8_000L
    }
}
