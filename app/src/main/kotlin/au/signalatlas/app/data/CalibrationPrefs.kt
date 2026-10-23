package au.signalatlas.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The calibration switch and whether the phone-access explanation has been offered (plain preferences, on this phone only). */
interface CalibrationSettings {
    /** "Improve predictions with my signal (stays on this phone)"; on by default. */
    val enabled: StateFlow<Boolean>
    fun setEnabled(on: Boolean)

    /** True the first time, then false: the permission rationale is volunteered once, afterwards only when the user taps. */
    fun offerRationaleOnce(): Boolean
}

class CalibrationPrefs(context: Context) : CalibrationSettings {
    private val prefs = context.applicationContext.getSharedPreferences("calibration", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
    }

    override fun offerRationaleOnce(): Boolean {
        if (prefs.getBoolean(KEY_OFFERED, false)) return false
        prefs.edit().putBoolean(KEY_OFFERED, true).apply()
        return true
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_OFFERED = "rationale_offered"
    }
}
