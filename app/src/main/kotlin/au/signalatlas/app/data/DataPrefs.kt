package au.signalatlas.app.data

import android.content.Context
import au.signalatlas.core.model.CoverageQuality
import au.signalatlas.core.model.DataSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DataConfig(
    val quality: CoverageQuality = CoverageQuality.BALANCED,
    val mobileDataUnmetered: Boolean = false,
    val patternTerrain: Boolean = false,
)

/**
 * Coverage quality and "treat mobile data as unmetered", stored on this phone and mirrored into [DataSettings] (which
 * the download rules read). [onChanged] runs after each change, so jobs and tiles can pick the new rules up.
 */
class DataPrefs(context: Context, private val onChanged: () -> Unit) {
    private val prefs = context.applicationContext.getSharedPreferences("data", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(read())
    val config: StateFlow<DataConfig> = _config.asStateFlow()

    init {
        DataSettings.update(_config.value.quality, _config.value.mobileDataUnmetered, _config.value.patternTerrain)
    }

    fun update(transform: (DataConfig) -> DataConfig) {
        val next = transform(_config.value)
        if (next == _config.value) return
        prefs.edit().putString(KEY_QUALITY, next.quality.name).putBoolean(KEY_MOBILE, next.mobileDataUnmetered)
            .putBoolean(KEY_PATTERN_TERRAIN, next.patternTerrain).apply()
        _config.value = next
        DataSettings.update(next.quality, next.mobileDataUnmetered, next.patternTerrain)
        onChanged()
    }

    private fun read() = DataConfig(
        CoverageQuality.entries.firstOrNull { it.name == prefs.getString(KEY_QUALITY, null) } ?: CoverageQuality.BALANCED,
        prefs.getBoolean(KEY_MOBILE, false),
        prefs.getBoolean(KEY_PATTERN_TERRAIN, false),
    )

    private companion object {
        const val KEY_QUALITY = "coverage_quality"
        const val KEY_MOBILE = "mobile_data_unmetered"
        const val KEY_PATTERN_TERRAIN = "pattern_terrain"
    }
}
