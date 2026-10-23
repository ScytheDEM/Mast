package au.signalatlas.core.calibration

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import au.signalatlas.engine.AreaClass
import java.io.Closeable
import java.io.File

/**
 * `calibration.db`: the on-device calibration data (it never leaves the phone except through the user's CSV export).
 * One read-write connection guarded by a lock; every method is blocking and thread-safe.
 *
 * Tables: `samples` (rule 3 fields), `sector_estimate` (rule 4 sufficient statistics and the current fit per sector),
 * `cell_map` (rule 2 learned cell -> sector mappings) and `residual_cell` (rule 5 sums per network, 0.001 deg cell and
 * sector, so a cell's mean after the sector corrections can be recomputed exactly whenever a fit changes).
 */
class CalibrationDb private constructor(private val conn: SQLiteConnection) : Closeable {
    private val lock = Any()

    internal data class SectorRow(
        val sectorId: Int, val network: Int, val siteLat: Double, val siteLon: Double, val sddId: Long,
        val n: Int, val sumR: Double, val offsetDb: Double, val slope: Double, val dMedianKm: Double, val updatedMs: Long,
    )

    internal data class CellMapRow(val key: String, val network: Int, val sectorId: Int, val sddId: Long, val likelyCount: Int, val confirmed: Boolean)

    internal data class ResidualRow(val network: Int, val latIdx: Int, val lonIdx: Int, val sectorId: Int, val n: Int, val sumR: Double, val sumLogD: Double)

    internal fun <T> transaction(block: () -> T): T = synchronized(lock) {
        conn.execSQL("BEGIN IMMEDIATE")
        try {
            block().also { conn.execSQL("COMMIT") }
        } catch (t: Throwable) {
            conn.execSQL("ROLLBACK")
            throw t
        }
    }

    internal fun insertSample(s: Sample) = synchronized(lock) {
        conn.prepare(
            "INSERT INTO samples(time_ms, lat, lon, accuracy_m, network, tech, channel, pci, cell_id, ta_m, rsrp, rsrq, sinr, " +
                "sdd_id, sector_id, carrier_network, site_lat, site_lon, predicted_rsrp, distance_m, area, confidence) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { st ->
            st.bindLong(1, s.timeMs); st.bindDouble(2, s.lat); st.bindDouble(3, s.lon); st.bindDouble(4, s.accuracyM)
            st.bindLong(5, s.network.ordinal.toLong()); st.bindLong(6, s.tech.ordinal.toLong())
            st.bindOpt(7, s.channel?.toLong()); st.bindOpt(8, s.pci?.toLong()); st.bindOpt(9, s.cellId)
            if (s.taM == null) st.bindNull(10) else st.bindDouble(10, s.taM)
            st.bindLong(11, s.rsrp.toLong()); st.bindOpt(12, s.rsrq?.toLong()); st.bindOpt(13, s.sinr?.toLong())
            st.bindLong(14, s.sddId); st.bindLong(15, s.sectorId.toLong()); st.bindLong(16, s.carrierNetwork.ordinal.toLong())
            st.bindDouble(17, s.siteLat); st.bindDouble(18, s.siteLon)
            st.bindDouble(19, s.predictedDbm); st.bindDouble(20, s.distanceM)
            st.bindLong(21, s.area.ordinal.toLong()); st.bindLong(22, s.confidence.ordinal.toLong())
            st.step()
        }
        Unit
    }

    /** Every sample of [sectorId] as (r = M - P, distance km). */
    internal fun sectorSamples(sectorId: Int): Pair<DoubleArray, DoubleArray> = synchronized(lock) {
        val r = ArrayList<Double>()
        val d = ArrayList<Double>()
        conn.prepare("SELECT rsrp - predicted_rsrp, distance_m FROM samples WHERE sector_id = ? ORDER BY id").use { st ->
            st.bindLong(1, sectorId.toLong())
            while (st.step()) {
                r += st.getDouble(0)
                d += st.getDouble(1) / 1000.0
            }
        }
        r.toDoubleArray() to d.toDoubleArray()
    }

    internal fun sector(sectorId: Int): SectorRow? = synchronized(lock) {
        conn.prepare("$SECTOR_SELECT WHERE sector_id = ?").use { st ->
            st.bindLong(1, sectorId.toLong())
            if (st.step()) sectorRow(st) else null
        }
    }

    internal fun sectors(): List<SectorRow> = synchronized(lock) {
        conn.prepare(SECTOR_SELECT).use { st -> buildList { while (st.step()) add(sectorRow(st)) } }
    }

    internal fun putSector(r: SectorRow) = synchronized(lock) {
        conn.prepare(
            "INSERT OR REPLACE INTO sector_estimate(sector_id, network, site_lat, site_lon, sdd_id, n, sum_r, offset_db, slope, " +
                "d_median_km, updated_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { st ->
            st.bindLong(1, r.sectorId.toLong()); st.bindLong(2, r.network.toLong()); st.bindDouble(3, r.siteLat)
            st.bindDouble(4, r.siteLon); st.bindLong(5, r.sddId); st.bindLong(6, r.n.toLong()); st.bindDouble(7, r.sumR)
            st.bindDouble(8, r.offsetDb); st.bindDouble(9, r.slope); st.bindDouble(10, r.dMedianKm); st.bindLong(11, r.updatedMs)
            st.step()
        }
        Unit
    }

    internal fun addResidual(network: Int, latIdx: Int, lonIdx: Int, sectorId: Int, r: Double, logD: Double) = synchronized(lock) {
        conn.prepare(
            "INSERT INTO residual_cell(network, lat_idx, lon_idx, sector_id, n, sum_r, sum_log_d) VALUES (?, ?, ?, ?, 1, ?, ?) " +
                "ON CONFLICT(network, lat_idx, lon_idx, sector_id) DO UPDATE SET n = n + 1, sum_r = sum_r + excluded.sum_r, " +
                "sum_log_d = sum_log_d + excluded.sum_log_d",
        ).use { st ->
            st.bindLong(1, network.toLong()); st.bindLong(2, latIdx.toLong()); st.bindLong(3, lonIdx.toLong())
            st.bindLong(4, sectorId.toLong()); st.bindDouble(5, r); st.bindDouble(6, logD)
            st.step()
        }
        Unit
    }

    internal fun residualRows(): List<ResidualRow> = synchronized(lock) {
        conn.prepare("SELECT network, lat_idx, lon_idx, sector_id, n, sum_r, sum_log_d FROM residual_cell").use { st ->
            buildList {
                while (st.step()) {
                    add(ResidualRow(st.getInt(0), st.getInt(1), st.getInt(2), st.getInt(3), st.getInt(4), st.getDouble(5), st.getDouble(6)))
                }
            }
        }
    }

    internal fun cellMap(key: String): CellMapRow? = synchronized(lock) {
        conn.prepare("SELECT key, network, sector_id, sdd_id, likely_count, confirmed FROM cell_map WHERE key = ?").use { st ->
            st.bindText(1, key)
            if (st.step()) CellMapRow(st.getText(0), st.getInt(1), st.getInt(2), st.getLong(3), st.getInt(4), st.getLong(5) != 0L) else null
        }
    }

    internal fun putCellMap(r: CellMapRow) = synchronized(lock) {
        conn.prepare(
            "INSERT OR REPLACE INTO cell_map(key, network, sector_id, sdd_id, likely_count, confirmed) VALUES (?, ?, ?, ?, ?, ?)",
        ).use { st ->
            st.bindText(1, r.key); st.bindLong(2, r.network.toLong()); st.bindLong(3, r.sectorId.toLong()); st.bindLong(4, r.sddId)
            st.bindLong(5, r.likelyCount.toLong()); st.bindLong(6, if (r.confirmed) 1L else 0L)
            st.step()
        }
        Unit
    }

    fun sampleCount(): Int = synchronized(lock) { conn.prepare("SELECT COUNT(*) FROM samples").use { it.step(); it.getInt(0) } }

    /** Calls [row] for every sample in insertion order (holding the lock; keep it quick). */
    internal fun forEachSample(row: (Sample) -> Unit) = synchronized(lock) {
        conn.prepare("SELECT ${SAMPLE_COLUMNS.joinToString()} FROM samples ORDER BY id").use { st ->
            while (st.step()) {
                row(
                    Sample(
                        st.getLong(0), st.getDouble(1), st.getDouble(2), st.getDouble(3), Network.entries[st.getInt(4)],
                        Tech.entries[st.getInt(5)], st.optLong(6)?.toInt(), st.optLong(7)?.toInt(), st.optLong(8),
                        if (st.isNull(9)) null else st.getDouble(9), st.getInt(10), st.optLong(11)?.toInt(), st.optLong(12)?.toInt(),
                        st.getLong(13), st.getInt(14), Network.entries[st.getInt(15)], st.getDouble(16), st.getDouble(17),
                        st.getDouble(18), st.getDouble(19), AreaClass.entries[st.getInt(20)], Confidence.entries[st.getInt(21)],
                    ),
                )
            }
        }
    }

    internal fun deleteAll() = transaction {
        for (t in listOf("samples", "sector_estimate", "cell_map", "residual_cell")) conn.execSQL("DELETE FROM $t")
    }

    /** Removes everything learned about [sectorId]: its samples, estimate, residual sums and cell mappings. */
    internal fun deleteSector(sectorId: Int) = transaction {
        for (t in listOf("samples", "sector_estimate", "cell_map", "residual_cell")) {
            conn.prepare("DELETE FROM $t WHERE sector_id = ?").use { it.bindLong(1, sectorId.toLong()); it.step() }
        }
    }

    override fun close() = synchronized(lock) { conn.close() }

    companion object {
        const val SCHEMA_VERSION = 1

        /** Column order of [forEachSample] and of the CSV export. */
        val SAMPLE_COLUMNS = listOf(
            "time_ms", "lat", "lon", "accuracy_m", "network", "tech", "channel", "pci", "cell_id", "ta_m", "rsrp", "rsrq",
            "sinr", "sdd_id", "sector_id", "carrier_network", "site_lat", "site_lon", "predicted_rsrp", "distance_m", "area",
            "confidence",
        )

        private const val SECTOR_SELECT =
            "SELECT sector_id, network, site_lat, site_lon, sdd_id, n, sum_r, offset_db, slope, d_median_km, updated_ms FROM sector_estimate"

        private val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS samples(id INTEGER PRIMARY KEY AUTOINCREMENT, time_ms INTEGER NOT NULL, lat REAL NOT NULL, " +
                "lon REAL NOT NULL, accuracy_m REAL NOT NULL, network INTEGER NOT NULL, tech INTEGER NOT NULL, channel INTEGER, " +
                "pci INTEGER, cell_id INTEGER, ta_m REAL, rsrp INTEGER NOT NULL, rsrq INTEGER, sinr INTEGER, sdd_id INTEGER NOT NULL, " +
                "sector_id INTEGER NOT NULL, carrier_network INTEGER NOT NULL, site_lat REAL NOT NULL, site_lon REAL NOT NULL, " +
                "predicted_rsrp REAL NOT NULL, distance_m REAL NOT NULL, area INTEGER NOT NULL, confidence INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS samples_sector ON samples(sector_id)",
            "CREATE TABLE IF NOT EXISTS sector_estimate(sector_id INTEGER PRIMARY KEY, network INTEGER NOT NULL, " +
                "site_lat REAL NOT NULL, site_lon REAL NOT NULL, sdd_id INTEGER NOT NULL, n INTEGER NOT NULL, sum_r REAL NOT NULL, " +
                "offset_db REAL NOT NULL, slope REAL NOT NULL, d_median_km REAL NOT NULL, updated_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS cell_map(key TEXT PRIMARY KEY, network INTEGER NOT NULL, sector_id INTEGER NOT NULL, " +
                "sdd_id INTEGER NOT NULL, likely_count INTEGER NOT NULL, confirmed INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS cell_map_sector ON cell_map(sector_id)",
            "CREATE TABLE IF NOT EXISTS residual_cell(network INTEGER NOT NULL, lat_idx INTEGER NOT NULL, lon_idx INTEGER NOT NULL, " +
                "sector_id INTEGER NOT NULL, n INTEGER NOT NULL, sum_r REAL NOT NULL, sum_log_d REAL NOT NULL, " +
                "PRIMARY KEY(network, lat_idx, lon_idx, sector_id))",
            "CREATE INDEX IF NOT EXISTS residual_sector ON residual_cell(sector_id)",
        )

        /**
         * Opens (creating) the database at [file]. A file of another schema version is discarded and recreated: the data
         * is a cache of what the phone measured, and a mismatched layout must never be misread.
         */
        fun open(file: File, driver: SQLiteDriver = BundledSQLiteDriver()): CalibrationDb {
            file.parentFile?.mkdirs()
            var conn = driver.open(file.path)
            val version = conn.prepare("PRAGMA user_version").use { it.step(); it.getInt(0) }
            if (version != 0 && version != SCHEMA_VERSION) {
                conn.close()
                file.delete()
                File(file.path + "-wal").delete()
                File(file.path + "-shm").delete()
                conn = driver.open(file.path)
            }
            conn.execSQL("PRAGMA journal_mode=WAL")
            for (sql in SCHEMA) conn.execSQL(sql)
            conn.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
            return CalibrationDb(conn)
        }

        private fun sectorRow(st: SQLiteStatement) = SectorRow(
            st.getInt(0), st.getInt(1), st.getDouble(2), st.getDouble(3), st.getLong(4), st.getInt(5), st.getDouble(6),
            st.getDouble(7), st.getDouble(8), st.getDouble(9), st.getLong(10),
        )

        private fun SQLiteStatement.bindOpt(i: Int, v: Long?) = if (v == null) bindNull(i) else bindLong(i, v)
        private fun SQLiteStatement.optLong(i: Int): Long? = if (isNull(i)) null else getLong(i)
    }
}
