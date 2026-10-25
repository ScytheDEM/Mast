package au.signalatlas.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The signal status of every SIM as a PaneTemplate (both flavours). Refreshed every 2 s, within the template limits. */
class SignalScreen(
    carContext: CarContext,
    private val source: CarDataSource,
    private val policy: AutoTripPolicy,
) : Screen(carContext) {
    init {
        lifecycleScope.launch {
            combine(source.sims, source.trip) { s, t -> s to t }.collect {
                invalidate()
                delay(REFRESH_MS) // collect keeps only the latest while delayed, which is what the host limit needs
            }
        }
    }

    override fun onGetTemplate(): Template {
        val trip = source.trip.value
        val pane = Pane.Builder()
        for (row in SignalPaneModel.rows(source.sims.value, trip)) {
            pane.addRow(Row.Builder().setTitle(row.title).apply { row.lines.take(2).forEach(::addText) }.build())
        }
        pane.addAction(
            Action.Builder()
                .setTitle(if (trip.running) "Stop trip" else "Start trip")
                .setBackgroundColor(if (trip.running) CarColor.RED else CarColor.PRIMARY)
                .setOnClickListener { if (trip.running) policy.manualStop() else policy.manualStart() }
                .build(),
        )
        return PaneTemplate.Builder(pane.build()).setHeaderAction(Action.APP_ICON).setTitle("Signal").build()
    }

    companion object {
        const val REFRESH_MS = 2_000L
    }
}
