package au.signalatlas.core.calibration

import au.signalatlas.core.model.CalibrationRules
import au.signalatlas.core.model.CalibrationSnapshot
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.SectorCorrection
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
 * Estimates and cell mappings are stored under the sector's stable identity (Ruling TT); each publish resolves them to the
 * tower DB's current sector ids through [resolver], dropping estimates whose sector no longer exists (they stay stored
 * and come back if it returns). Call [onTowersChanged] when a new tower DB is installed to republish and to convert
 * rows migrated from schema v1.
 *
 * [scope] runs the deferred publishes (use a background dispatcher: a publish reads every estimate and residual cell).
 * [clock] is wall-clock milliseconds, used only to pace publishes. Thread-safe.
 */
class CalibrationStore(
    private val db: CalibrationDb,
    private val resolver: SectorResolver,
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

    /** The tower DB changed (a new one was installed): re-resolves identities and publishes at once. */
    fun onTowersChanged() = publishNow()

    /** Stores [sample] (only Likely or Confirmed matches reach here; others are rejected) and updates the estimates. */
    fun add(sample: Sample) {
        require(sample.confidence != Confidence.UNCERTAIN) { "only Likely or Confirmed matches are stored (rule 3)" }
        db.transaction {
            db.insertSample(sample)
            val row = Estimator.updateSector(db.sector(sample.ident), sample) { db.sectorSamples(sample.ident) }
            db.putSector(row)
            db.addResidual(
                sample.carrierNetwork.ordinal, CalibrationRules.cellIndex(sample.lat), CalibrationRules.cellIndex(sample.lon),
                sample.ident, sample.residualDb, Estimator.log10Km(sample.distanceM),
            )
        }
        markChanged()
    }

    fun sampleCount(): Int = db.sampleCount()

    /** Up to [limit] (predicted, measured) RSRP pairs of the sector [ident], newest first (the Calibration screen's scatter). */
    fun scatter(ident: String, limit: Int = 300): List<Pair<Double, Double>> = db.scatter(ident, limit)

    /** Every sample as CSV (header = [CalibrationDb.SAMPLE_COLUMNS]; enums by name, unavailable fields empty). */
    fun exportCsv(out: Appendable) {
        out.append(CalibrationDb.SAMPLE_COLUMNS.joinToString(",")).append('\n')
        db.forEachSample { s ->
            val fields = listOf<Any?>(
                s.timeMs, s.lat, s.lon, s.accuracyM, s.network.name, s.tech.name, s.channel, s.pci, s.cellId, s.taM, s.rsrp,
                s.rsrq, s.sinr, s.sddId, s.sectorId, s.carrierNetwork.name, s.siteLat, s.siteLon, s.predictedDbm, s.distanceM,
                s.area.name, s.confidence.name, s.ident,
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
        resolver.identitiesOf(listOf(sectorId))[sectorId]?.let(db::deleteSector)
        publishNow()
    }

    /** Same as [resetSector] for a stored identity (a sector that has left the tower DB can still be reset). */
    fun resetIdent(ident: String) {
        db.deleteSector(ident)
        publishNow()
    }

    override fun cellMapping(key: String): CellMapping? = db.cellMap(key)?.let {
        CellMapping(it.key, Network.entries[it.network], it.ident, it.sddId, it.likelyCount, it.confirmed)
    }

    override fun putCellMapping(m: CellMapping) =
        db.putCellMap(CalibrationDb.CellMapRow(m.key, m.network.ordinal, m.ident, m.sddId, m.likelyCount, m.confirmed))

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
        migrateLegacy()
        val rows = db.sectors()
        val current = resolver.sectorIdsOf(rows.map { it.ident })
        val fits = rows.associate { it.ident to Estimator.fitOf(it) }
        val sectors = HashMap<Int, SectorCorrection>()
        // If two stored identities ever resolve to one sector, the better-supported estimate wins.
        for (row in rows.sortedBy { it.n }) current[row.ident]?.let { sectors[it] = Estimator.correction(row, it) }
        val snap = CalibrationSnapshot(++version, sectors, Estimator.residualCells(db.residualRows(), fits))
        lastPublishMs = clock()
        _snapshot.value = snap
    }

    /** Rows converted from schema v1 are keyed by sector id; once the tower DB is there, key them by identity. */
    private fun migrateLegacy() {
        val legacy = db.legacySectorIds()
        if (legacy.isEmpty()) return
        val idents = resolver.identitiesOf(legacy)
        if (idents.isNotEmpty()) db.rekeyLegacy(idents)
    }
}
