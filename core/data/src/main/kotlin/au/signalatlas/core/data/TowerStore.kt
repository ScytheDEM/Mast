package au.signalatlas.core.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import au.signalatlas.core.acma.ImportReport
import au.signalatlas.core.acma.TowerDbSchema
import au.signalatlas.core.model.Antenna
import au.signalatlas.core.model.BandInfo
import au.signalatlas.core.model.Carrier
import au.signalatlas.core.model.GeoBounds
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Sector
import au.signalatlas.core.model.SectorIdentity
import au.signalatlas.core.model.Site
import au.signalatlas.core.model.SiteDetail
import au.signalatlas.core.model.SiteClassifier
import au.signalatlas.core.model.SiteKind
import au.signalatlas.core.model.SiteSummary
import au.signalatlas.core.model.Tech
import au.signalatlas.core.model.TowerFilter
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant
import kotlin.math.cos
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

    /** The in-memory summary of one site (name, networks, DAS kind), or null; no SQLite access. */
    fun summary(siteId: Int): SiteSummary? {
        val i = summaries.asList().binarySearch { it.id.compareTo(siteId) }
        return if (i >= 0) summaries[i] else null
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
            "SELECT id, lat, lon, name, state, postcode, elevation_m, site_kind FROM site WHERE id = ?",
        ).use { st ->
            st.bindLong(1, siteId.toLong())
            if (!st.step()) return null
            Site(
                id = st.getLong(0).toInt(), lat = st.getDouble(1), lon = st.getDouble(2), name = st.getText(3),
                state = st.textOrNull(4), postcode = st.textOrNull(5), elevationM = st.doubleOrNull(6),
                kind = SiteClassifier.classify(st.getText(3)),
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

    /**
     * Every carrier of the given [networks] and [techs] (and, unless [bands] is null, of those band
     * labels) on the sites inside [b], edges inclusive. The grid index picks the candidate sites, then
     * SQLite is asked for their carriers in chunks of at most [ID_CHUNK] site ids. Throws
     * [IllegalStateException] on a closed store.
     */
    fun carriersIn(b: GeoBounds, networks: Set<Network>, techs: Set<Tech>, bands: Set<String>?): CarrierBatch =
        carriersIn(b, networks, techs, bands, ID_CHUNK)

    internal fun carriersIn(
        b: GeoBounds, networks: Set<Network>, techs: Set<Tech>, bands: Set<String>?, idChunk: Int,
    ): CarrierBatch {
        val out = BatchBuilder(allBands)
        if (networks.isNotEmpty() && techs.isNotEmpty() && (bands == null || bands.isNotEmpty())) {
            val buf = buffers.get()!!
            val n = index.query(b, buf)
            val filter = TowerFilter(networks, techs, bands)
            val ids = IntArray(n)
            var m = 0
            for (k in 0 until n) {
                val s = summaries[buf[k]]
                if (filter.matches(s)) ids[m++] = s.id
            }
            val bandIndex = allBands.withIndex().associate { it.value to it.index }
            val networkList = networks.joinToString(",") { it.ordinal.toString() }
            val techList = techs.joinToString(",") { it.ordinal.toString() }
            var from = 0
            while (from < m) {
                val to = minOf(from + idChunk, m)
                val marks = (from until to).joinToString(",") { "?" }
                synchronized(lock) {
                    check(!closed) { "TowerStore is closed" }
                    conn.prepare(
                        """SELECT t.lat, t.lon, s.height_m, s.azimuth_deg, c.tilt_deg, c.eirp_dbm, c.freq_hz, c.bw_hz,
                                  c.tech, s.network, a.h_bw_deg, a.f2b_db, a.gain_dbi, c.sector_id, c.sdd_id, c.band
                           FROM carrier c
                           JOIN sector s ON s.id = c.sector_id
                           JOIN site t ON t.id = s.site_id
                           LEFT JOIN antenna a ON a.id = s.antenna_id
                           WHERE s.site_id IN ($marks) AND s.network IN ($networkList) AND c.tech IN ($techList)
                           ORDER BY s.site_id, c.sdd_id""",
                    ).use { st ->
                        for (k in from until to) st.bindLong(k - from + 1, ids[k].toLong())
                        while (st.step()) {
                            val band = st.getText(15)
                            if (bands != null && band !in bands) continue
                            out.add(st, checkNotNull(bandIndex[band]))
                        }
                    }
                }
                from = to
            }
        }
        return out.build()
    }

    /** The stable [SectorIdentity] of each of [sectorIds] that exists in this DB (others are absent). */
    fun sectorIdentities(sectorIds: Collection<Int>): Map<Int, String> {
        val out = HashMap<Int, String>()
        for (chunk in sectorIds.distinct().chunked(ID_CHUNK)) {
            val marks = chunk.joinToString(",") { "?" }
            synchronized(lock) {
                check(!closed) { "TowerStore is closed" }
                conn.prepare(
                    "SELECT id, site_id, network, antenna_id, azimuth_deg, height_m FROM sector WHERE id IN ($marks)",
                ).use { st ->
                    chunk.forEachIndexed { i, id -> st.bindLong(i + 1, id.toLong()) }
                    while (st.step()) out[st.getLong(0).toInt()] = identityOf(st)
                }
            }
        }
        return out
    }

    /** The current sector id of each [SectorIdentity] that exists in this DB (lowest id on a tie; others are absent). */
    fun sectorIdsByIdentity(idents: Collection<String>): Map<String, Int> {
        val wanted = idents.toSet()
        val sites = wanted.mapNotNull(SectorIdentity::siteIdOf).distinct()
        val out = HashMap<String, Int>()
        for (chunk in sites.chunked(ID_CHUNK)) {
            val marks = chunk.joinToString(",") { "?" }
            synchronized(lock) {
                check(!closed) { "TowerStore is closed" }
                conn.prepare(
                    "SELECT id, site_id, network, antenna_id, azimuth_deg, height_m FROM sector WHERE site_id IN ($marks) ORDER BY id",
                ).use { st ->
                    chunk.forEachIndexed { i, id -> st.bindLong(i + 1, id.toLong()) }
                    while (st.step()) {
                        val ident = identityOf(st)
                        if (ident in wanted) out.putIfAbsent(ident, st.getLong(0).toInt())
                    }
                }
            }
        }
        return out
    }

    private fun identityOf(st: SQLiteStatement) = SectorIdentity.of(
        st.getLong(1).toInt(), Network.entries[st.getLong(2).toInt()], if (st.isNull(3)) null else st.getLong(3).toInt(),
        st.doubleOrNull(4), st.getDouble(5),
    )

    /** Number of sites (not carriers) within [radiusM] metres of the point, by haversine distance. */
    fun siteCountWithin(lat: Double, lon: Double, radiusM: Double): Int {
        if (radiusM < 0) return 0
        val dLat = Math.toDegrees(radiusM / EARTH_RADIUS_M)
        val cosLat = cos(Math.toRadians(lat))
        // Wider than the circle's true longitude extent near the poles; clamp to the whole globe there.
        val dLon = if (cosLat < 1e-6) 360.0 else dLat / cosLat
        val buf = buffers.get()!!
        val n = index.query(GeoBounds(lat - dLat, lon - dLon, lat + dLat, lon + dLon), buf)
        var count = 0
        for (k in 0 until n) {
            val s = summaries[buf[k]]
            if (haversineM(lat, lon, s.lat, s.lon) <= radiusM) count++
        }
        return count
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
        /** Site ids per carrier query; SQLite's default variable limit is 999. */
        private const val ID_CHUNK = 900

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
                """SELECT s.id, s.lat, s.lon, s.name, s.postcode, s.site_kind, m.network_mask, m.tech_mask, m.bands
                   FROM site s JOIN site_summary m ON m.site_id = s.id ORDER BY s.id""",
            ).use { st ->
                while (st.step()) {
                    val kind = SiteClassifier.classify(st.getText(3))
                    val nm = st.getLong(6).toInt()
                    val tm = st.getLong(7).toInt()
                    val bands = st.getText(8)
                    summaries.add(
                        SiteSummary(
                            id = st.getLong(0).toInt(), lat = st.getDouble(1), lon = st.getDouble(2), name = st.getText(3),
                            networks = networkSets.getOrPut(nm) { Network.entries.filter { nm and (1 shl it.ordinal) != 0 }.toSet() },
                            techs = techSets.getOrPut(tm) { Tech.entries.filter { tm and (1 shl it.ordinal) != 0 }.toSet() },
                            bands = bandSets.getOrPut(bands) { if (bands.isEmpty()) emptySet() else bands.split(',').toSet() },
                            kind = kind,
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
            val version = values["schema_version"]?.toIntOrNull()
            if (version != TowerDbSchema.VERSION) throw SchemaMismatchException(version, TowerDbSchema.VERSION)
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

/** Growable column buffers behind [TowerStore.carriersIn]. */
private class BatchBuilder(private val bandLabels: List<String>) {
    private var cap = 1024
    private var n = 0
    private var siteLat = DoubleArray(cap)
    private var siteLon = DoubleArray(cap)
    private var heightM = FloatArray(cap)
    private var azimuthDeg = FloatArray(cap)
    private var tiltDeg = FloatArray(cap)
    private var eirpDbm = FloatArray(cap)
    private var freqMhz = FloatArray(cap)
    private var bandwidthHz = IntArray(cap)
    private var tech = ByteArray(cap)
    private var network = ByteArray(cap)
    private var hBeamwidthDeg = FloatArray(cap)
    private var frontToBackDb = FloatArray(cap)
    private var gainDbi = FloatArray(cap)
    private var sectorId = IntArray(cap)
    private var sddId = LongArray(cap)
    private var bandIdx = ByteArray(cap)

    init {
        require(bandLabels.size <= 256) { "more than 256 bands do not fit a byte index" }
    }

    /** Appends the row of the carrier query in [TowerStore.carriersIn], with its band's label index. */
    fun add(st: SQLiteStatement, band: Int) {
        if (n == cap) grow()
        fun f(i: Int) = if (st.isNull(i)) Float.NaN else st.getDouble(i).toFloat()
        siteLat[n] = st.getDouble(0)
        siteLon[n] = st.getDouble(1)
        heightM[n] = st.getDouble(2).toFloat()
        azimuthDeg[n] = f(3)
        tiltDeg[n] = st.getDouble(4).toFloat()
        eirpDbm[n] = st.getDouble(5).toFloat()
        freqMhz[n] = (st.getLong(6) / 1e6).toFloat()
        bandwidthHz[n] = st.getLong(7).toInt()
        tech[n] = st.getLong(8).toByte()
        network[n] = st.getLong(9).toByte()
        hBeamwidthDeg[n] = f(10)
        frontToBackDb[n] = f(11)
        gainDbi[n] = f(12)
        sectorId[n] = st.getLong(13).toInt()
        sddId[n] = st.getLong(14)
        bandIdx[n] = band.toByte()
        n++
    }

    private fun grow() {
        cap *= 2
        siteLat = siteLat.copyOf(cap); siteLon = siteLon.copyOf(cap); heightM = heightM.copyOf(cap)
        azimuthDeg = azimuthDeg.copyOf(cap); tiltDeg = tiltDeg.copyOf(cap); eirpDbm = eirpDbm.copyOf(cap)
        freqMhz = freqMhz.copyOf(cap); bandwidthHz = bandwidthHz.copyOf(cap); tech = tech.copyOf(cap)
        network = network.copyOf(cap); hBeamwidthDeg = hBeamwidthDeg.copyOf(cap)
        frontToBackDb = frontToBackDb.copyOf(cap); gainDbi = gainDbi.copyOf(cap)
        sectorId = sectorId.copyOf(cap); sddId = sddId.copyOf(cap); bandIdx = bandIdx.copyOf(cap)
    }

    fun build() = CarrierBatch(
        n, siteLat.copyOf(n), siteLon.copyOf(n), heightM.copyOf(n), azimuthDeg.copyOf(n), tiltDeg.copyOf(n),
        eirpDbm.copyOf(n), freqMhz.copyOf(n), bandwidthHz.copyOf(n), tech.copyOf(n), network.copyOf(n),
        hBeamwidthDeg.copyOf(n), frontToBackDb.copyOf(n), gainDbi.copyOf(n), sectorId.copyOf(n), sddId.copyOf(n),
        bandIdx.copyOf(n), bandLabels,
    )
}
