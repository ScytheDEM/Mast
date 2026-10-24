package au.signalatlas.app.data

import android.content.Context
import au.signalatlas.feature.trip.TechFloor
import au.signalatlas.feature.trip.TripConfig
import au.signalatlas.feature.trip.TripSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Trip alert settings in plain preferences (this phone only). Unknown stored values fall back to the defaults. */
class TripPrefs(context: Context) : TripSettings {
    private val prefs = context.applicationContext.getSharedPreferences("trip", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(read())
    override val config: StateFlow<TripConfig> = _config.asStateFlow()

    override fun update(transform: (TripConfig) -> TripConfig) {
        val next = sanitise(transform(_config.value))
        prefs.edit()
            .putInt(KEY_WINDOW, next.windowSeconds).putInt(KEY_MIN_DEAD, next.minDeadZoneM)
            .putString(KEY_FLOOR, next.floor.name).putBoolean(KEY_VOICE, next.voice).apply()
        _config.value = next
    }

    private fun read() = sanitise(
        TripConfig(
            prefs.getInt(KEY_WINDOW, 90), prefs.getInt(KEY_MIN_DEAD, 300),
            TechFloor.entries.firstOrNull { it.name == prefs.getString(KEY_FLOOR, null) } ?: TechFloor.LOSE_ALL,
            prefs.getBoolean(KEY_VOICE, false),
        ),
    )

    private fun sanitise(c: TripConfig) = c.copy(
        windowSeconds = c.windowSeconds.takeIf { it in TripConfig.WINDOWS } ?: 90,
        minDeadZoneM = c.minDeadZoneM.takeIf { it in TripConfig.MIN_DEAD_ZONES } ?: 300,
    )

    private companion object {
        const val KEY_WINDOW = "window_s"
        const val KEY_MIN_DEAD = "min_dead_m"
        const val KEY_FLOOR = "floor"
        const val KEY_VOICE = "voice"
    }
}
