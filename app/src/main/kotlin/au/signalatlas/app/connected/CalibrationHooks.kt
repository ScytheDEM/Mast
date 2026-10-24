package au.signalatlas.app.connected

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the cellular details need of the calibration feature: the switch and the number of samples on a tower. */
class CalibrationHooks(
    val enabled: StateFlow<Boolean>,
    /** Samples stored for the sector with this identity (blocking: call off the main thread). */
    val samplesFor: (ident: String) -> Int,
    val setEnabled: (Boolean) -> Unit,
) {
    companion object {
        val None = CalibrationHooks(MutableStateFlow(false), { 0 }, { })
    }
}
