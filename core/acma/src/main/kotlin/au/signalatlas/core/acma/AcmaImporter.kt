package au.signalatlas.core.acma

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import au.signalatlas.core.acma.csv.AcmaFormatException
import au.signalatlas.core.acma.csv.CsvReader
import au.signalatlas.core.model.BandPlan
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.NetworkMapping
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.TreeSet
import java.util.zip.ZipFile
import kotlin.math.floor
import kotlinx.serialization.json.Json

class ImportCancelledException : Exception("ACMA import cancelled")

/**
 * Builds the tower DB on the device from an ACMA RRL zip, applying the shared Import rules 1–10.
 *
 * Entries are streamed in the order client → licence → device_details → site → antenna, so
 * sites and antennas are filtered against the devices already kept. Only the ~537k distinct
 * sector keys are held in memory (in a primitive open-addressing table); everything else goes
 * straight into SQLite.
 */
class AcmaImporter(
    private val driver: SQLiteDriver = BundledSQLiteDriver(),
    /** Receives one `phase=<name> ms=<elapsed>` line per phase and finalise step, then `phase=total`. */
    private val log: (String) -> Unit = {},
) {
    /**
     * Builds a complete schema-v1 DB at [out] (overwritten). [cancel] is polled every 10 000 rows
     * and between the finalise steps.
     */
    fun import(
        zip: File,
        out: File,
        extractStamp: String,
        zipEtag: String,
        progress: (phase: String, fraction: Float) -> Unit = { _, _ -> },
        cancel: () -> Boolean = { false },
    ): ImportReport {
        deleteDb(out)
        try {
            return ZipFile(zip).use { zf ->
                val conn = driver.open(out.path)
                try {
                    ImportRun(zf, conn, progress, cancel, log).run(extractStamp, zipEtag)
                } finally {
                    conn.close()
                }
            }
        } catch (t: Throwable) {
            deleteDb(out)
            throw t
        }
    }

    private fun deleteDb(out: File) {
        for (suffix in listOf("", "-journal", "-wal", "-shm")) File(out.path + suffix).delete()
    }

    companion object {
        const val POLL_ROWS = 10_000
    }
}

private class ImportRun(
    private val zf: ZipFile,
    private val conn: SQLiteConnection,
    private val progress: (String, Float) -> Unit,
    private val cancel: () -> Boolean,
    private val log: (String) -> Unit,
) {
    private val networkByClient = HashMap<Int, Network>()
    private val networkByLicence = HashMap<String, Network>()

    private val sectors = SectorKeyTable()
    private var nextSectorId = 1
    private val referencedSites = HashSet<Int>()
    private val referencedAntennas = HashSet<Int>()
    private val gainByAntenna = HashMap<Int, Double?>()

    /** Devices whose EIRP is derived from transmitter power; antenna gain is added once antennas are read. */
    private val pending = PendingPower()

    private var skippedUnknownBand = 0
    private var skippedNoPower = 0
    private var skippedBadSite = 0
    private var skippedMalformed = 0

    fun run(extractStamp: String, zipEtag: String): ImportReport {
        val started = System.nanoTime()
        conn.execSQL("PRAGMA journal_mode=OFF")
        conn.execSQL("PRAGMA synchronous=OFF")
        for (sql in TowerDbSchema.TABLES) conn.execSQL(sql)

        timed("client") { readClients() }
        timed("licence") { readLicences() }
        timed("devices") { readDevices() }
        timed("sites") { readSites() }
        timed("antennas") { readAntennas() }
        val report = timed(PHASE_FINALISE) { finalise() }
        writeMeta(report, extractStamp, zipEtag)
        progress(PHASE_FINALISE, 1f)
        log("phase=total ms=${elapsedMs(started)}")
        return report
    }

    private inline fun <T> timed(phase: String, block: () -> T): T {
        val started = System.nanoTime()
        return block().also { log("phase=$phase ms=${elapsedMs(started)}") }
    }

    private fun elapsedMs(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

    /** One finalise step: cancellable before it starts, timed as `finalise.<name>`. */
    private inline fun <T> step(name: String, block: () -> T): T {
        if (cancel()) throw ImportCancelledException()
        return timed("$PHASE_FINALISE.$name", block)
    }

    private fun readClients() = scan("client.csv", "client") { r ->
        val clientNo = r.column("CLIENT_NO")
        val licensee = r.column("LICENCEE")
        return@scan {
            val network = NetworkMapping.fromLicensee(r[licensee])
            val id = parseInt32Id(r[clientNo])
            if (network != null && id != null) networkByClient[id] = network
        }
    }

    private fun readLicences() = scan("licence.csv", "licence") { r ->
        val licenceNo = r.column("LICENCE_NO")
        val clientNo = r.column("CLIENT_NO")
        val type = r.column("LICENCE_TYPE_NAME")
        return@scan {
            if (r[type].trim() in LICENCE_TYPES) {
                val network = parseInt32Id(r[clientNo])?.let { networkByClient[it] }
                if (network != null) networkByLicence[r[licenceNo].trim()] = network
            }
        }
    }

    private fun readDevices() {
        val insertSector = conn.prepare(
            "INSERT INTO sector(id, site_id, network, antenna_id, azimuth_deg, height_m) VALUES (?, ?, ?, ?, ?, ?)",
        )
        // Ruling J: a duplicate SDD_ID keeps the first device.
        val insertCarrier = conn.prepare(
            "INSERT OR IGNORE INTO carrier(sdd_id, sector_id, freq_hz, bw_hz, eirp_dbm, tilt_deg, tech, band, " +
                "licence_no, device_reg_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        val changes = conn.prepare("SELECT changes()")
        try {
            scan("device_details.csv", "devices") { r ->
                val cSdd = r.column("SDD_ID")
                val cLicence = r.column("LICENCE_NO")
                val cRegId = r.column("DEVICE_REGISTRATION_IDENTIFIER")
                val cFreq = r.column("FREQUENCY")
                val cBw = r.column("BANDWIDTH")
                val cType = r.column("DEVICE_TYPE")
                val cTxPower = r.column("TRANSMITTER_POWER")
                val cTxUnit = r.column("TRANSMITTER_POWER_UNIT")
                val cSite = r.column("SITE_ID")
                val cAntenna = r.column("ANTENNA_ID")
                val cAzimuth = r.column("AZIMUTH")
                val cHeight = r.column("HEIGHT")
                val cTilt = r.column("TILT")
                val cFeederLoss = r.column("FEEDER_LOSS")
                val cEirp = r.column("EIRP")
                val cEirpUnit = r.column("EIRP_UNIT")
                return@scan device@{
                    // Rule 3.
                    if (r[cType].trim() != "T") return@device
                    val licenceNo = r[cLicence].trim()
                    val network = networkByLicence[licenceNo] ?: return@device

                    // Rule 11: checked before rule 4.
                    val sddId = parseIdentifier(r[cSdd], Long.MAX_VALUE)
                    if (sddId == null) {
                        skippedMalformed++
                        return@device
                    }

                    // Rule 4.
                    val freq = parseNumber(r[cFreq])
                    val freqHz = freq?.let { Math.round(it) }
                    val band = freqHz?.let { BandPlan.classify(it, network) }
                    if (freqHz == null || band == null) {
                        skippedUnknownBand++
                        return@device
                    }

                    // Rule 5. derivedBase != null means "transmitter power path": eirp gets antenna gain later.
                    var eirp = Double.NaN
                    var derivedBase: Double? = null
                    var feederLoss = 0.0
                    val eirpText = r[cEirp].trim()
                    val eirpValue = if (eirpText.isEmpty()) null else parseNumber(eirpText)
                    val eirpUnit = r[cEirpUnit].trim()
                    if (eirpText.isNotEmpty() && eirpValue == null) {
                        skippedNoPower++
                        return@device
                    }
                    if (eirpValue != null && !(eirpUnit in UnitConversions.LINEAR_UNITS && eirpValue <= 0)) {
                        val dbm = UnitConversions.toDbm(eirpValue, eirpUnit)
                        if (dbm == null) {
                            skippedNoPower++
                            return@device
                        }
                        eirp = round2(dbm)
                    } else {
                        val tx = r[cTxPower].trim()
                        val base = if (tx.isEmpty()) null else parseNumber(tx)?.let {
                            UnitConversions.toDbm(it, r[cTxUnit].trim())
                        }
                        if (base == null) {
                            skippedNoPower++
                            return@device
                        }
                        derivedBase = base
                        feederLoss = parseNumber(r[cFeederLoss]) ?: 0.0
                        eirp = round2(base - feederLoss) // provisional until the antenna gain is known
                    }

                    // Rule 7 (first half) + rule 11: a blank or invalid SITE_ID can never match a site.
                    val siteId = parseInt32Id(r[cSite])
                    if (siteId == null) {
                        skippedBadSite++
                        return@device
                    }

                    // Rule 6 + rule 11: a blank or invalid ANTENNA_ID is a null antenna.
                    val antennaId = parseInt32Id(r[cAntenna])
                    val azimuth = parseNumber(r[cAzimuth])?.let(::round1)
                    val height = parseNumber(r[cHeight])?.let(::round1) ?: 0.0
                    val sectorId = sectors.getOrAdd(siteId, antennaId, azimuth, height, network.ordinal, nextSectorId)
                    if (sectorId == nextSectorId) {
                        nextSectorId++
                        referencedSites += siteId
                        if (antennaId != null) referencedAntennas += antennaId
                        insertSector.reset()
                        insertSector.bindLong(1, sectorId.toLong())
                        insertSector.bindLong(2, siteId.toLong())
                        insertSector.bindLong(3, network.ordinal.toLong())
                        if (antennaId == null) insertSector.bindNull(4) else insertSector.bindLong(4, antennaId.toLong())
                        if (azimuth == null) insertSector.bindNull(5) else insertSector.bindDouble(5, azimuth)
                        insertSector.bindDouble(6, height)
                        insertSector.step()
                    }

                    insertCarrier.reset()
                    insertCarrier.bindLong(1, sddId)
                    insertCarrier.bindLong(2, sectorId.toLong())
                    insertCarrier.bindLong(3, freqHz)
                    insertCarrier.bindLong(4, parseNumber(r[cBw])?.let { Math.round(it) } ?: 0L)
                    insertCarrier.bindDouble(5, eirp)
                    insertCarrier.bindDouble(6, parseNumber(r[cTilt]) ?: 0.0)
                    insertCarrier.bindLong(7, band.tech.ordinal.toLong())
                    insertCarrier.bindText(8, band.band)
                    insertCarrier.bindText(9, licenceNo)
                    insertCarrier.bindText(10, r[cRegId].trim())
                    insertCarrier.step()

                    if (derivedBase != null && changes.singleLong() == 1L) {
                        pending.add(sddId, derivedBase, feederLoss, antennaId ?: NO_ANTENNA)
                    }
                }
            }
        } finally {
            insertSector.close()
            insertCarrier.close()
            changes.close()
        }
    }

    private fun readSites() {
        // Duplicate in-bounds rows: the last wins, as in the reference.
        val insert = conn.prepare(
            "INSERT OR REPLACE INTO site(id, lat, lon, name, state, postcode, elevation_m) VALUES (?, ?, ?, ?, ?, ?, ?)",
        )
        try {
            scan("site.csv", "sites") { r ->
                val cId = r.column("SITE_ID")
                val cLat = r.column("LATITUDE")
                val cLon = r.column("LONGITUDE")
                val cName = r.column("NAME")
                val cState = r.column("STATE")
                val cPostcode = r.column("POSTCODE")
                val cElevation = r.column("ELEVATION")
                return@scan site@{
                    val id = parseInt32Id(r[cId]) ?: return@site
                    if (id !in referencedSites) return@site
                    val lat = parseNumber(r[cLat]) ?: return@site
                    val lon = parseNumber(r[cLon]) ?: return@site
                    if (lat < -55.0 || lat > -9.0 || lon < 72.0 || lon > 169.0) return@site
                    insert.reset()
                    insert.bindLong(1, id.toLong())
                    insert.bindDouble(2, lat)
                    insert.bindDouble(3, lon)
                    insert.bindText(4, r[cName].trim())
                    insert.bindTextOrNull(5, r[cState].trim())
                    insert.bindTextOrNull(6, r[cPostcode].trim())
                    insert.bindDoubleOrNull(7, parseNumber(r[cElevation]))
                    insert.step()
                }
            }
        } finally {
            insert.close()
        }
    }

    private fun readAntennas() {
        val insert = conn.prepare(
            "INSERT OR REPLACE INTO antenna(id, gain_dbi, f2b_db, h_bw_deg, model, manufacturer) VALUES (?, ?, ?, ?, ?, ?)",
        )
        try {
            scan("antenna.csv", "antennas") { r ->
                val cId = r.column("ANTENNA_ID")
                val cGain = r.column("GAIN")
                val cF2b = r.column("FRONT_TO_BACK")
                val cHbw = r.column("H_BEAMWIDTH")
                val cModel = r.column("MODEL")
                val cManufacturer = r.column("MANUFACTURER")
                return@scan antenna@{
                    val id = parseInt32Id(r[cId]) ?: return@antenna
                    if (id !in referencedAntennas) return@antenna
                    // Rule 8: blank or 0 -> null.
                    val gain = nonZeroOrNull(r[cGain])
                    gainByAntenna[id] = gain
                    insert.reset()
                    insert.bindLong(1, id.toLong())
                    insert.bindDoubleOrNull(2, gain)
                    insert.bindDoubleOrNull(3, nonZeroOrNull(r[cF2b]))
                    insert.bindDoubleOrNull(4, nonZeroOrNull(r[cHbw]))
                    insert.bindTextOrNull(5, r[cModel].trim())
                    insert.bindTextOrNull(6, r[cManufacturer].trim())
                    insert.step()
                }
            }
        } finally {
            insert.close()
        }
    }

    /**
     * Runs in one transaction; a cancel between steps throws [ImportCancelledException] and the
     * caller deletes the half-built file, so the open transaction never needs a rollback.
     */
    private fun finalise(): ImportReport {
        if (cancel()) throw ImportCancelledException()
        progress(PHASE_FINALISE, 0f)
        conn.execSQL("BEGIN")

        // Rule 5: derived EIRP = transmitter power + antenna gain - feeder loss.
        step("eirp") { updateDerivedEirp() }
        progress(PHASE_FINALISE, 0.1f)

        step("indexes") { for (sql in TowerDbSchema.INDEXES) conn.execSQL(sql) }
        progress(PHASE_FINALISE, 0.4f)

        step("prune") { prune() }
        step("renumber") { renumberSectors() }
        progress(PHASE_FINALISE, 0.6f)

        step("summary") { fillSiteSummary() }
        conn.execSQL("COMMIT")
        progress(PHASE_FINALISE, 0.9f)

        return step("report") { report() }
    }

    private fun updateDerivedEirp() {
        conn.prepare("UPDATE carrier SET eirp_dbm = ? WHERE sdd_id = ?").use { st ->
            for (i in 0 until pending.size) {
                val antenna = pending.antenna[i]
                val gain = if (antenna == NO_ANTENNA) 0.0 else gainByAntenna[antenna] ?: 0.0
                st.reset()
                st.bindDouble(1, round2(pending.base[i] + gain - pending.loss[i]))
                st.bindLong(2, pending.sddId[i])
                st.step()
            }
        }
    }

    private fun prune() {
        // Rule 7 (second half): devices on missing or out-of-bounds sites, then emptied sectors.
        val onBadSite = "sector_id IN (SELECT id FROM sector WHERE site_id NOT IN (SELECT id FROM site))"
        skippedBadSite += conn.longQuery("SELECT COUNT(*) FROM carrier WHERE $onBadSite").toInt()
        conn.execSQL("DELETE FROM carrier WHERE $onBadSite")
        conn.execSQL("DELETE FROM sector WHERE NOT EXISTS (SELECT 1 FROM carrier WHERE carrier.sector_id = sector.id)")
        conn.execSQL("DELETE FROM site WHERE NOT EXISTS (SELECT 1 FROM sector WHERE sector.site_id = site.id)")
        // Rule 8: antennas referenced by a kept sector.
        conn.execSQL("DELETE FROM antenna WHERE id NOT IN (SELECT antenna_id FROM sector WHERE antenna_id IS NOT NULL)")
    }

    private fun report(): ImportReport {
        val byNetwork = sortedMapOf<String, Int>()
        conn.prepare(
            "SELECT s.network, COUNT(*) FROM carrier c JOIN sector s ON s.id = c.sector_id GROUP BY s.network",
        ).use { st ->
            while (st.step()) byNetwork[Network.entries[st.getLong(0).toInt()].name] = st.getLong(1).toInt()
        }
        return ImportReport(
            sites = conn.longQuery("SELECT COUNT(*) FROM site").toInt(),
            sectors = conn.longQuery("SELECT COUNT(*) FROM sector").toInt(),
            carriers = conn.longQuery("SELECT COUNT(*) FROM carrier").toInt(),
            antennas = conn.longQuery("SELECT COUNT(*) FROM antenna").toInt(),
            skippedUnknownBand = skippedUnknownBand,
            skippedNoPower = skippedNoPower,
            skippedBadSite = skippedBadSite,
            skippedMalformed = skippedMalformed,
            carriersByNetwork = byNetwork,
        )
    }

    /** Rule 6: sector IDs are 1..N in first-seen order; close the gaps left by dropped sectors. */
    private fun renumberSectors() {
        val count = conn.longQuery("SELECT COUNT(*) FROM sector")
        val max = conn.longQuery("SELECT COALESCE(MAX(id), 0) FROM sector")
        if (count == max) return
        conn.execSQL("CREATE TEMP TABLE sector_renum(old INTEGER PRIMARY KEY, new INTEGER NOT NULL)")
        conn.execSQL("INSERT INTO sector_renum SELECT id, ROW_NUMBER() OVER (ORDER BY id) FROM sector")
        conn.execSQL("UPDATE carrier SET sector_id = (SELECT new FROM sector_renum WHERE old = carrier.sector_id)")
        // Via negative IDs so no intermediate UPDATE collides with an existing primary key.
        conn.execSQL("UPDATE sector SET id = -(SELECT new FROM sector_renum WHERE old = sector.id)")
        conn.execSQL("UPDATE sector SET id = -id")
        conn.execSQL("DROP TABLE sector_renum")
    }

    private fun fillSiteSummary() {
        val insert = conn.prepare(
            "INSERT INTO site_summary(site_id, network_mask, tech_mask, bands) VALUES (?, ?, ?, ?)",
        )
        try {
            var site = -1L
            var networkMask = 0L
            var techMask = 0L
            val bands = TreeSet<String>()
            fun flush() {
                if (site < 0) return
                insert.reset()
                insert.bindLong(1, site)
                insert.bindLong(2, networkMask)
                insert.bindLong(3, techMask)
                insert.bindText(4, bands.joinToString(","))
                insert.step()
            }
            conn.prepare(
                "SELECT DISTINCT s.site_id, s.network, c.tech, c.band FROM sector s " +
                    "JOIN carrier c ON c.sector_id = s.id ORDER BY s.site_id",
            ).use { st ->
                while (st.step()) {
                    val id = st.getLong(0)
                    if (id != site) {
                        flush()
                        site = id
                        networkMask = 0
                        techMask = 0
                        bands.clear()
                    }
                    networkMask = networkMask or (1L shl st.getLong(1).toInt())
                    techMask = techMask or (1L shl st.getLong(2).toInt())
                    bands += st.getText(3)
                }
            }
            flush()
        } finally {
            insert.close()
        }
    }

    private fun writeMeta(report: ImportReport, extractStamp: String, zipEtag: String) {
        val meta = listOf(
            "schema_version" to TowerDbSchema.VERSION.toString(),
            "extract_stamp" to extractStamp,
            "zip_etag" to zipEtag,
            "imported_at" to Instant.now().toString(),
            "report" to Json.encodeToString(ImportReport.serializer(), report),
        )
        conn.prepare("INSERT INTO meta(key, value) VALUES (?, ?)").use { st ->
            for ((k, v) in meta) {
                st.reset()
                st.bindText(1, k)
                st.bindText(2, v)
                st.step()
            }
        }
    }

    /**
     * Streams [entryName] in one transaction. [setup] looks up the needed columns (failing fast on a
     * missing one) and returns the per-row handler. Polls [cancel] and reports progress (by
     * uncompressed bytes consumed) every [AcmaImporter.POLL_ROWS] rows.
     */
    private fun scan(entryName: String, phase: String, setup: (CsvReader) -> (() -> Unit)) {
        val entry = zf.getEntry(entryName) ?: throw AcmaFormatException("missing $entryName in ACMA zip")
        val total = entry.size
        val input = CountingInputStream(zf.getInputStream(entry))
        progress(phase, 0f)
        CsvReader(input).use { r ->
            val onRow = setup(r)
            conn.execSQL("BEGIN")
            var rows = 0L
            while (r.next()) {
                if (rows % AcmaImporter.POLL_ROWS == 0L) {
                    if (cancel()) throw ImportCancelledException()
                    if (total > 0) progress(phase, (input.count.toFloat() / total).coerceIn(0f, 1f))
                }
                onRow()
                rows++
            }
            conn.execSQL("COMMIT")
        }
        progress(phase, 1f)
    }

    private companion object {
        const val PHASE_FINALISE = "finalise"
        const val NO_ANTENNA = -1
        val LICENCE_TYPES = setOf("Spectrum", "PTS")
    }
}

/**
 * Parses a finite decimal number the way the reference's `float()` does for ACMA data: digits,
 * sign, '.', exponent. Rejects Java-only forms (hex floats, `d`/`f` suffixes, NaN, Infinity).
 */
internal fun parseNumber(raw: String): Double? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    for (ch in s) {
        if (ch !in '0'..'9' && ch != '.' && ch != '-' && ch != '+' && ch != 'e' && ch != 'E') return null
    }
    val v = s.toDoubleOrNull() ?: return null
    return if (v.isFinite()) v else null
}

/**
 * Rule 11: an identifier is ASCII decimal digits after trimming (leading zeros allowed) with a value
 * in 0..[max]; anything else is null. Never throws, so malformed rows cannot abort an import.
 */
internal fun parseIdentifier(raw: String, max: Long): Long? {
    val s = raw.trim()
    if (s.isEmpty() || s.any { it !in '0'..'9' }) return null
    val digits = s.trimStart('0')
    if (digits.length > 19) return null // more than Long.MAX_VALUE's 19 digits
    val v = if (digits.isEmpty()) 0L else digits.toLongOrNull() ?: return null
    return v.takeIf { it <= max }
}

/** Rule 11 for SITE_ID, ANTENNA_ID and CLIENT_NO (0..2^31-1). */
internal fun parseInt32Id(raw: String): Int? = parseIdentifier(raw, Int.MAX_VALUE.toLong())?.toInt()

/** Rule 6: `floor(x * 10 + 0.5) / 10` in double, with `-0.0` normalised to `0.0` (by adding `0.0`). */
internal fun round1(x: Double): Double = floor(x * 10 + 0.5) / 10 + 0.0

/** Rule 5: round to 0.01 dB exactly as Python's `round(x, 2)` (correctly rounded, ties to even). */
internal fun round2(x: Double): Double = BigDecimal(x).setScale(2, RoundingMode.HALF_EVEN).toDouble()

private fun nonZeroOrNull(s: String): Double? = parseNumber(s)?.takeIf { it != 0.0 }

private fun SQLiteStatement.bindTextOrNull(index: Int, value: String) =
    if (value.isEmpty()) bindNull(index) else bindText(index, value)

private fun SQLiteStatement.bindDoubleOrNull(index: Int, value: Double?) =
    if (value == null) bindNull(index) else bindDouble(index, value)

private fun SQLiteStatement.singleLong(): Long {
    reset()
    check(step())
    return getLong(0)
}

private fun SQLiteConnection.longQuery(sql: String): Long = prepare(sql).use { it.singleLong() }

private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) count += it }
}

/** Growable parallel primitive arrays for derived-power devices (~10k on the real export). */
private class PendingPower {
    var size = 0
        private set
    var sddId = LongArray(256)
        private set
    var base = DoubleArray(256)
        private set
    var loss = DoubleArray(256)
        private set
    var antenna = IntArray(256)
        private set

    fun add(sdd: Long, baseDbm: Double, feederLoss: Double, antennaId: Int) {
        if (size == sddId.size) {
            val n = size * 2
            sddId = sddId.copyOf(n)
            base = base.copyOf(n)
            loss = loss.copyOf(n)
            antenna = antenna.copyOf(n)
        }
        sddId[size] = sdd
        base[size] = baseDbm
        loss[size] = feederLoss
        antenna[size] = antennaId
        size++
    }
}

/**
 * Open-addressing (linear probing) map from a sector key (site, antenna?, azimuth?, height,
 * network) to its sector ID, over packed primitive arrays: ~29 MB at 2^20 slots instead of a
 * HashMap of boxed data-class keys. Azimuth/height are keyed on the bits of their rule-6 rounded
 * doubles, so equality is exactly the reference's tuple equality.
 */
private class SectorKeyTable {
    private var capacity = 1 shl 14
    private var siteAntenna = LongArray(capacity)
    private var azimuthBits = LongArray(capacity)
    private var heightBits = LongArray(capacity)
    private var network = ByteArray(capacity)
    private var ids = IntArray(capacity) // 0 = empty slot
    private var size = 0

    /** Returns the ID stored for the key, or stores and returns [newId] if the key is new. */
    fun getOrAdd(siteId: Int, antennaId: Int?, azimuth: Double?, height: Double, net: Int, newId: Int): Int {
        val a = (siteId.toLong() shl 32) or ((antennaId ?: -1).toLong() and 0xFFFF_FFFFL)
        val b = azimuth?.toRawBits() ?: NULL_AZIMUTH
        val c = height.toRawBits()
        var i = slot(a, b, c, net)
        while (true) {
            val id = ids[i]
            if (id == 0) break
            if (siteAntenna[i] == a && azimuthBits[i] == b && heightBits[i] == c && network[i].toInt() == net) {
                return id
            }
            i = (i + 1) and (capacity - 1)
        }
        siteAntenna[i] = a
        azimuthBits[i] = b
        heightBits[i] = c
        network[i] = net.toByte()
        ids[i] = newId
        if (++size * 4 > capacity * 3) grow()
        return newId
    }

    private fun slot(a: Long, b: Long, c: Long, net: Int): Int {
        // Combine with the golden-ratio multiplier, then the MurmurHash3 fmix64 finaliser.
        var h = ((a * GOLDEN + b) * GOLDEN + c) * GOLDEN + net
        h = (h xor (h ushr 33)) * FMIX1
        h = (h xor (h ushr 33)) * FMIX2
        h = h xor (h ushr 33)
        return h.toInt() and (capacity - 1)
    }

    private fun grow() {
        val oldA = siteAntenna
        val oldB = azimuthBits
        val oldC = heightBits
        val oldN = network
        val oldIds = ids
        capacity *= 2
        siteAntenna = LongArray(capacity)
        azimuthBits = LongArray(capacity)
        heightBits = LongArray(capacity)
        network = ByteArray(capacity)
        ids = IntArray(capacity)
        for (j in oldIds.indices) {
            if (oldIds[j] == 0) continue
            var i = slot(oldA[j], oldB[j], oldC[j], oldN[j].toInt())
            while (ids[i] != 0) i = (i + 1) and (capacity - 1)
            siteAntenna[i] = oldA[j]
            azimuthBits[i] = oldB[j]
            heightBits[i] = oldC[j]
            network[i] = oldN[j]
            ids[i] = oldIds[j]
        }
    }

    private companion object {
        /** Bits of -0.0, which [round1] never produces, so it can stand for "omni" (null azimuth). */
        val NULL_AZIMUTH = (-0.0).toRawBits()
        const val GOLDEN = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        const val FMIX1 = -0x00ae502812aa7333L // 0xFF51AFD7ED558CCD
        const val FMIX2 = -0x3b314601e57a13adL // 0xC4CEB9FE1A85EC53
    }
}
