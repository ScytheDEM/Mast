package au.signalatlas.core.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import au.signalatlas.core.acma.ImportReport
import au.signalatlas.core.model.Antenna
import au.signalatlas.core.model.BandInfo
import au.signalatlas.core.model.Carrier
import au.signalatlas.core.model.GeoBounds
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Sector
import au.signalatlas.core.model.Site
import au.signalatlas.core.model.SiteDetail
import au.signalatlas.core.model.SiteSummary
import au.signalatlas.core.model.Tech
import au.signalatlas.core.model.TowerFilter
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant
import kotlinx.serialization.json.Json

/**
 * Read side of the tower DB. All site summaries live in memory behind a [GridIndex]; per-site detail
 * and nothing else goes to SQLite through one read-only connection guarded by a lock.
 * Safe for concurrent readers.
 */
class TowerStore private constructor(
    private val conn: SQLiteConnection,
    val meta: DataMeta,
    val allBands: List<String>,
    private val summaries: Array<SiteSummary>,
    private val postcodes: Array<String?>,
    private val index: GridIndex,
) : Closeable {

    private val lock = Any()
    private var closed = false
    private val buffers = ThreadLocal.withInitial { IntArray(summaries.size) }

    fun sitesIn(b: GeoBounds, f: TowerFilter): List<SiteSummary> {
        val buf = buffers.get()!!
        val n = index.query(b, buf)
        val result = ArrayList<SiteSummary>(n)
        for (k in 0 until n) {
            val s = summaries[buf[k]]
            if (f.matches(s)) result.add(s)
        }
        return result
    }

    fun allSites(f: TowerFilter): List<SiteSummary> = summaries.filter { f.matches(it) }

    /** Up to [limit] filtered sites nearest to the point, nearest first, with distance in metres. */
    fun nearest(lat: Double, lon: Double, f: TowerFilter, limit: Int = 50): List<Pair<SiteSummary, Double>> =
        index.nearest(lat, lon, limit) { f.matches(summaries[it]) }.map {
            val s = summaries[it]
            s to haversineM(lat, lon, s.lat, s.lon)
        }

    /**
     * Results in this order, de-duplicated and capped at [limit]:
     * 1. exact site id, only for a digits-only query without a leading zero ("0820" is not site 820);
     * 2. exact postcode, for a digits-only query (by site id);
     * 3. case-insensitive name substring, ordered by name then id (also for digits-only queries,
     *    since ACMA names often start with street numbers).
     * A blank query gives an empty list.
     */
    fun search(query: String, limit: Int = 20): List<SiteSummary> {
        val q = query.trim()
        if (q.isEmpty() || limit <= 0) return emptyList()
        val out = LinkedHashMap<Int, SiteSummary>()
        if (q.all { it in '0'..'9' }) {
            val id = if (q == "0" || !q.startsWith('0')) q.toIntOrNull() else null
            if (id != null) summaries.firstOrNull { it.id == id }?.let { out[it.id] = it }
            for (i in summaries.indices) {
                if (out.size >= limit) break
                if (postcodes[i] == q) out.putIfAbsent(summaries[i].id, summaries[i])
            }
        }
        if (out.size < limit) {
            summaries.filter { it.name.contains(q, ignoreCase = true) && it.id !in out }
                .sortedWith(compareBy<SiteSummary, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id })
                .forEach { if (out.size < limit) out[it.id] = it }
        }
        return out.values.toList()
    }

    /**
     * Full detail of one site, or null if it does not exist. Also returns null if this store has
     * been closed; callers re-read TowerRepository.store.
     */
    fun siteDetail(siteId: Int): SiteDetail? = synchronized(lock) {
        if (closed) return null
        val site = conn.prepare(
            "SELECT id, lat, lon, name, state, postcode, elevation_m FROM site WHERE id = ?",
        ).use { st ->
            st.bindLong(1, siteId.toLong())
            if (!st.step()) return null
            Site(
                id = st.getLong(0).toInt(), lat = st.getDouble(1), lon = st.getDouble(2), name = st.getText(3),
                state = st.textOrNull(4), postcode = st.textOrNull(5), elevationM = st.doubleOrNull(6),
            )
        }

        val carriers = HashMap<Int, MutableList<Carrier>>()
        conn.prepare(
            """SELECT c.sector_id, c.sdd_id, c.freq_hz, c.bw_hz, c.eirp_dbm, c.tilt_deg, c.tech, c.band,
                      c.licence_no, c.device_reg_id
               FROM carrier c JOIN sector s ON s.id = c.sector_id
               WHERE s.site_id = ? ORDER BY c.freq_hz, c.sdd_id""",
        ).use { st ->
            st.bindLong(1, siteId.toLong())
            while (st.step()) {
                carriers.getOrPut(st.getLong(0).toInt()) { ArrayList() }.add(
                    Carrier(
                        sddId = st.getLong(1), freqHz = st.getLong(2), bandwidthHz = st.getLong(3),
                        eirpDbm = st.getDouble(4), tiltDeg = st.getDouble(5),
                        band = BandInfo(st.getText(7), Tech.entries[st.getLong(6).toInt()]),
                        licenceNo = st.getText(8), deviceRegId = st.getText(9),
                    ),
                )
            }
        }

        val sectors = ArrayList<Sector>()
        conn.prepare(
            """SELECT s.id, s.network, s.azimuth_deg, s.height_m,
                      a.id, a.gain_dbi, a.f2b_db, a.h_bw_deg, a.model, a.manufacturer
               FROM sector s LEFT JOIN antenna a ON a.id = s.antenna_id
               WHERE s.site_id = ?
               ORDER BY s.network, s.azimuth_deg IS NOT NULL, s.azimuth_deg, s.id""",
        ).use { st ->
            st.bindLong(1, siteId.toLong())
            while (st.step()) {
                val id = st.getLong(0).toInt()
                val antenna = if (st.isNull(4)) null else Antenna(
                    id = st.getLong(4).toInt(), gainDbi = st.doubleOrNull(5), frontToBackDb = st.doubleOrNull(6),
                    hBeamwidthDeg = st.doubleOrNull(7), model = st.textOrNull(8), manufacturer = st.textOrNull(9),
                )
                sectors.add(
                    Sector(
                        id = id, siteId = siteId, network = Network.entries[st.getLong(1).toInt()],
                        antenna = antenna, azimuthDeg = st.doubleOrNull(2), heightM = st.getDouble(3),
                        carriers = carriers[id].orEmpty(),
                    ),
                )
            }
        }
        SiteDetail(site, sectors)
    }

    override fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                conn.close()
            }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Opens [db] and loads every site summary. Throws [FileNotFoundException] (without touching the
         * driver, so nothing is created) when [db] does not exist, and other exceptions if it is unreadable.
         * [BundledSQLiteDriver] is opened read-only; for any other [driver] the caller must supply one
         * that opens connections read-only.
         */
        fun open(db: File, driver: SQLiteDriver = BundledSQLiteDriver()): TowerStore {
            if (!db.isFile) throw FileNotFoundException("tower DB not found: ${db.path}")
            val conn = if (driver is BundledSQLiteDriver) driver.open(db.path, SQLITE_OPEN_READONLY) else driver.open(db.path)
            try {
                return load(conn)
            } catch (t: Throwable) {
                conn.close()
                throw t
            }
        }

        private fun load(conn: SQLiteConnection): TowerStore {
            val meta = readMeta(conn)

            val networkSets = HashMap<Int, Set<Network>>()
            val techSets = HashMap<Int, Set<Tech>>()
            val bandSets = HashMap<String, Set<String>>()
            val summaries = ArrayList<SiteSummary>()
            val postcodes = ArrayList<String?>()
            conn.prepare(
                """SELECT s.id, s.lat, s.lon, s.name, s.postcode, m.network_mask, m.tech_mask, m.bands
                   FROM site s JOIN site_summary m ON m.site_id = s.id ORDER BY s.id""",
            ).use { st ->
                while (st.step()) {
                    val nm = st.getLong(5).toInt()
                    val tm = st.getLong(6).toInt()
                    val bands = st.getText(7)
                    summaries.add(
                        SiteSummary(
                            id = st.getLong(0).toInt(), lat = st.getDouble(1), lon = st.getDouble(2), name = st.getText(3),
                            networks = networkSets.getOrPut(nm) { Network.entries.filter { nm and (1 shl it.ordinal) != 0 }.toSet() },
                            techs = techSets.getOrPut(tm) { Tech.entries.filter { tm and (1 shl it.ordinal) != 0 }.toSet() },
                            bands = bandSets.getOrPut(bands) { if (bands.isEmpty()) emptySet() else bands.split(',').toSet() },
                        ),
                    )
                    postcodes.add(st.textOrNull(4)?.trim())
                }
            }

            val allBands = ArrayList<String>()
            conn.prepare("SELECT band FROM carrier GROUP BY band ORDER BY MIN(freq_hz), band").use { st ->
                while (st.step()) allBands.add(st.getText(0))
            }

            val lats = DoubleArray(summaries.size) { summaries[it].lat }
            val lons = DoubleArray(summaries.size) { summaries[it].lon }
            return TowerStore(
                conn, meta, allBands, summaries.toTypedArray(), postcodes.toTypedArray(), GridIndex(lats, lons),
            )
        }

        private fun readMeta(conn: SQLiteConnection): DataMeta {
            val values = HashMap<String, String>()
            conn.prepare("SELECT key, value FROM meta").use { st ->
                while (st.step()) values[st.getText(0)] = st.getText(1)
            }
            fun req(k: String) = values[k] ?: error("meta key '$k' missing")
            return DataMeta(
                extractStamp = req("extract_stamp"),
                importedAt = Instant.parse(req("imported_at")),
                report = json.decodeFromString(ImportReport.serializer(), req("report")),
            )
        }

        private fun SQLiteStatement.textOrNull(i: Int): String? = if (isNull(i)) null else getText(i)
        private fun SQLiteStatement.doubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)
    }
}
