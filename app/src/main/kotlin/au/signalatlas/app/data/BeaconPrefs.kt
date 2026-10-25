package au.signalatlas.app.data

import android.content.Context
import android.net.Uri
import au.signalatlas.core.beacons.CsvImportResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The "Use Bluetooth beacons in tunnels" setting (plain preferences, this phone only; off by default). */
class BeaconPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("beacons", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
    }
}

/** What the beacon section of the trip settings needs: the switch, how many beacons are known and the CSV import. */
class BeaconSettings(
    val enabled: StateFlow<Boolean>,
    val setEnabled: (Boolean) -> Unit,
    val count: suspend () -> Int,
    /** Reads the picked CSV (`id,lat,lon`) into beacons.db; null when the file cannot be read. */
    val importCsv: suspend (Uri) -> CsvImportResult?,
)
