package au.signalatlas.app.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.signalatlas.app.data.CalibrationData
import au.signalatlas.app.data.CalibrationSettings
import au.signalatlas.core.model.CalibrationSnapshot
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.SectorCorrection
import au.signalatlas.core.model.SectorIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One calibrated sector: its learned offset, sample count and the measured-vs-predicted points behind it. */
data class SectorRowUi(
    val ident: String,
    val network: Network,
    val siteId: Int?,
    val azimuthDeg: Double?,
    val n: Int,
    val offsetDb: Double,
    /** Applied to the map from 3 samples (Review Focus 2). */
    val applied: Boolean,
    /** (predicted, measured) dBm. */
    val points: List<Pair<Double, Double>>,
)

data class CalibrationUiState(
    val enabled: Boolean = true,
    val sampleCount: Int = 0,
    val sectors: List<SectorRowUi> = emptyList(),
)

/** The Calibration screen: the switch, what has been learned, CSV export, per-sector reset and Delete all. */
class CalibrationViewModel(
    private val data: CalibrationData,
    private val settings: CalibrationSettings,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(CalibrationUiState(enabled = settings.enabled.value))
    val state: StateFlow<CalibrationUiState> = _state.asStateFlow()

    init {
        settings.enabled.onEach { on -> _state.update { it.copy(enabled = on) } }.launchIn(viewModelScope)
        data.snapshot.onEach { reload(it) }.launchIn(viewModelScope)
    }

    fun setEnabled(on: Boolean) = settings.setEnabled(on)

    fun resetSector(ident: String) = viewModelScope.launch {
        withContext(io) { data.resetSector(ident) }
        reload(data.snapshot.value)
    }

    fun deleteAll() = viewModelScope.launch {
        withContext(io) { data.deleteAll() }
        reload(data.snapshot.value)
    }

    /** Writes every sample as CSV to [dir]/signal-atlas-calibration.csv (the user's own export) and returns the file. */
    suspend fun exportCsv(dir: File): File = withContext(io) {
        dir.mkdirs()
        File(dir, EXPORT_NAME).also { f -> f.bufferedWriter().use { data.exportCsv(it) } }
    }

    private suspend fun reload(snap: CalibrationSnapshot) {
        val (count, rows) = withContext(io) {
            data.sampleCount() to snap.sectors.values.sortedWith(compareByDescending<SectorCorrection> { it.n }.thenBy { it.ident })
                .map { it.toRow(data.scatter(it.ident)) }
        }
        _state.update { it.copy(sampleCount = count, sectors = rows) }
    }

    private fun SectorCorrection.toRow(points: List<Pair<Double, Double>>) = SectorRowUi(
        ident, Network.entries[network], SectorIdentity.siteIdOf(ident), SectorIdentity.azimuthOf(ident), n, offsetDb, isApplied, points,
    )

    companion object {
        const val EXPORT_NAME = "signal-atlas-calibration.csv"
    }
}
