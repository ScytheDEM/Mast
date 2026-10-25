package au.signalatlas.car

import androidx.car.app.CarContext
import androidx.car.app.Screen

/** fullMap flavour: the map surface with the signal panel is the root; the Signal screen is one tap away. */
internal fun rootScreen(ctx: CarContext, source: CarDataSource, policy: AutoTripPolicy): Screen =
    MapNavScreen(ctx, source, policy)
