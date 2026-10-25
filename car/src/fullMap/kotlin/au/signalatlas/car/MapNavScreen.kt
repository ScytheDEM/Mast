package au.signalatlas.car

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** The fullMap root: our own map surface (navigation category) with the action strip for trip, coverage and zoom. */
class MapNavScreen(
    carContext: CarContext,
    private val source: CarDataSource,
    private val policy: AutoTripPolicy,
) : Screen(carContext) {
    private val renderer = MapSurfaceRenderer(carContext, source)

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
                renderer.start(lifecycleScope)
            }

            override fun onStop(owner: LifecycleOwner) {
                renderer.stop()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
            }
        })
        lifecycleScope.launch { source.trip.drop(1).collect { invalidate() } }
        lifecycleScope.launch { renderer.coverageOn.drop(1).collect { invalidate() } }
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    override fun onGetTemplate(): Template {
        val running = source.trip.value.running
        val coverage = renderer.coverageOn.value
        val mapStrip = ActionStrip.Builder()
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_recenter)).setOnClickListener { renderer.recenter() }.build())
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_zoom_in)).setOnClickListener { renderer.zoomBy(1.0) }.build())
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_zoom_out)).setOnClickListener { renderer.zoomBy(-1.0) }.build())
            .addAction(
                Action.Builder().setIcon(icon(R.drawable.ic_coverage))
                    .setBackgroundColor(if (coverage) CarColor.PRIMARY else CarColor.DEFAULT)
                    .setOnClickListener { renderer.toggleCoverage() }.build(),
            )
            .build()
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder().setTitle(if (running) "Stop trip" else "Start trip")
                    .setBackgroundColor(if (running) CarColor.RED else CarColor.PRIMARY)
                    .setOnClickListener { if (running) policy.manualStop() else policy.manualStart() }.build(),
            )
            .addAction(
                Action.Builder().setTitle("Signal")
                    .setOnClickListener { screenManager.push(SignalScreen(carContext, source, policy)) }.build(),
            )
            .build()
        return NavigationTemplate.Builder().setActionStrip(strip).setMapActionStrip(mapStrip).build()
    }
}
