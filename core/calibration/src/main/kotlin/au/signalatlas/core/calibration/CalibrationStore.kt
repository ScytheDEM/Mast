package au.signalatlas.core.calibration

import au.signalatlas.core.model.CalibrationRules
import au.signalatlas.core.model.CalibrationSnapshot
import au.signalatlas.core.model.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The calibration data and what has been learned from it. [add] stores a sample and updates its sector's estimate
 * and residual cell at once (rules 4-5, incremental); [snapshot] republishes everything at most every
 * [publishIntervalMs] while samples arrive (rule 7), and immediately after [deleteAll] / [resetSector]. Each publish
 * increments [CalibrationSnapshot.version].
 *
 * [scope] runs the deferred publishes (use a background dispatcher: a publish reads every estimate and residual cell).
 * [clock] is wall-clock milliseconds, used only to pace publishes. Thread-safe.
 */
class CalibrationStore(
    private val db: CalibrationDb,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val publishIntervalMs: Long = 30_000L,
) : CellMapStore {
    private val lock = Any()
    private var version = 0L
    private var lastPublishMs = Long.MIN_VALUE / 2
    private var dirty = false
    private var pending: Job? = null

    private val _snapshot = MutableStateFlow(CalibrationSnapshot.EMPTY)
    val snapshot: StateFlow<CalibrationSnapshot> = _snapshot.asStateFlow()

    init {
        if (db.sampleCount() > 0) publishNow()
    }

    /** Stores [sample] (only Likely or Confirmed matches reach here; others are rejected) and updates the estimates. */
    fun add(sample: Sample) {
        require(sample.confidence != Confidence.UNCERTAIN) { "only Likely or Confirmed matches are stored (rule 3)" }
        db.transaction {
            db.insertSample(sample)
            val row = Estimator.updateSector(db.sector(sample.sectorId), sample) { db.sectorSamples(sample.sectorId) }
            db.putSector(row)
            db.addResidual(
                sample.carrierNetwork.ordinal, CalibrationRules.cellIndex(sample.lat), CalibrationRules.cellIndex(sample.lon),
                sample.sectorId, sample.residualDb, Estimator.log10Km(sample.distanceM),
            )
        }
        markChanged()
    }

    fun sampleCount(): Int = db.sampleCount()

    /** Every sample as CSV (header = [CalibrationDb.SAMPLE_COLUMNS]; enums by name, unavailable fields empty). */
    fun exportCsv(out: Appendable) {
        out.append(CalibrationDb.SAMPLE_COLUMNS.joinToString(",")).append('\n')
        db.forEachSample { s ->
            val fields = listOf<Any?>(
                s.timeMs, s.lat, s.lon, s.accuracyM, s.network.name, s.tech.name, s.channel, s.pci, s.cellId, s.taM, s.rsrp,
                s.rsrq, s.sinr, s.sddId, s.sectorId, s.carrierNetwork.name, s.siteLat, s.siteLon, s.predictedDbm, s.distanceM,
                s.area.name, s.confidence.name,
            )
            out.append(fields.joinToString(",") { it?.toString() ?: "" }).append('\n')
        }
    }

    /** Deletes every sample, estimate, mapping and residual cell; publishes the empty snapshot at once. */
    fun deleteAll() {
        db.deleteAll()
        publishNow()
    }

    /** Forgets everything learned about [sectorId] (a wrong match the user spotted); publishes at once. */
    fun resetSector(sectorId: Int) {
        db.deleteSector(sectorId)
        publishNow()
    }

    override fun cellMapping(key: String): CellMapping? = db.cellMap(key)?.let {
        CellMapping(it.key, Network.entries[it.network], it.sectorId, it.sddId, it.likelyCount, it.confirmed)
    }

    override fun putCellMapping(m: CellMapping) =
        db.putCellMap(CalibrationDb.CellMapRow(m.key, m.network.ordinal, m.sectorId, m.sddId, m.likelyCount, m.confirmed))

    private fun markChanged() = synchronized(lock) {
        dirty = true
        if (pending == null) pending = scope.launch { publishLoop() }
    }

    /** Publishes while changes keep arriving, no more often than every [publishIntervalMs]. */
    private suspend fun publishLoop() {
        while (true) {
            val wait = synchronized(lock) { lastPublishMs + publishIntervalMs - clock() }
            if (wait > 0) delay(wait)
            synchronized(lock) {
                if (!dirty) {
                    pending = null
                    return
                }
            }
            publishNow()
        }
    }

    private fun publishNow() = synchronized(lock) {
        dirty = false
        val rows = db.sectors()
        val fits = rows.associate { it.sectorId to Estimator.fitOf(it) }
        val snap = CalibrationSnapshot(
            ++version, rows.associate { it.sectorId to Estimator.correction(it) }, Estimator.residualCells(db.residualRows(), fits),
        )
        lastPublishMs = clock()
        _snapshot.value = snap
    }
}
