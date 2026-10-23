package au.signalatlas.app.data

import au.signalatlas.core.calibration.CalibrationStore
import au.signalatlas.core.model.CalibrationSnapshot
import kotlinx.coroutines.flow.StateFlow

/** The calibration store as the Calibration screen uses it (blocking calls: run them off the main thread). */
interface CalibrationData {
    val snapshot: StateFlow<CalibrationSnapshot>
    fun sampleCount(): Int
    /** (predicted, measured) RSRP pairs of one sector, newest first. */
    fun scatter(ident: String): List<Pair<Double, Double>>
    fun resetSector(ident: String)
    fun deleteAll()
    fun exportCsv(out: Appendable)
}

class StoreCalibrationData(private val store: CalibrationStore) : CalibrationData {
    override val snapshot get() = store.snapshot
    override fun sampleCount() = store.sampleCount()
    override fun scatter(ident: String) = store.scatter(ident)
    override fun resetSector(ident: String) = store.resetIdent(ident)
    override fun deleteAll() = store.deleteAll()
    override fun exportCsv(out: Appendable) = store.exportCsv(out)
}
