package au.signalatlas.car

import androidx.car.app.CarContext
import androidx.car.app.Screen

/** play flavour: template-only, the Signal status screen is the root. */
internal fun rootScreen(ctx: CarContext, source: CarDataSource, policy: AutoTripPolicy): Screen =
    SignalScreen(ctx, source, policy)
