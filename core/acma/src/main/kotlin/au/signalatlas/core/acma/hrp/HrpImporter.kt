package au.signalatlas.core.acma.hrp

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.execSQL
import au.signalatlas.core.acma.ImportCancelledException
import au.signalatlas.core.acma.deleteDbFiles
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.InputStream
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.math.floor
import kotlinx.serialization.json.Json

/**
 * Builds `patterns.db` (HRP import rules 1–6) by streaming `LICENCE_.csv` out of ACMA's HRP zip,
 * keeping only devices that are carriers in [import]'s `towersDb`.
 *
 * Memory: the known SDD IDs as one sorted `LongArray` (≈850k), a parallel "stored" flag per SDD,
 * one 360-entry power buffer and the de-duplication map (one 360-byte key per distinct pattern).
 * Rows go straight to SQLite.
 *
 * Ruling BB: the zip entry's CRC-32 and size are checked while inflating (`ZipFile`'s entry stream
 * does not check them), so a corrupted or spliced download fails with [ZipException] at the end of
 * the stream, before anything is committed.
 */
class HrpImporter(
    private val driver: SQLiteDriver = BundledSQLiteDriver(),
    /** Receives `phase=<name> ms=<elapsed>` lines: `towers`, `stream`, then `total`. */
    private val log: (String) -> Unit = {},
) {
    /**
     * Builds a complete schema-v1 DB at [out] (overwritten; deleted on any failure). [cancel] is
     * polled before streaming and every [POLL_ROWS] rows; [progress] gets the fraction of the
     * uncompressed CSV consumed, ending with 1.
     */
    fun import(
        hrpZip: File,
        towersDb: File,
        out: File,
        etag: String,
        cancel: () -> Boolean = { false },
        progress: (Float) -> Unit = {},
    ): HrpReport {
        deleteDbFiles(out)
        try {
            val started = System.nanoTime()
            val towers = loadTowers(towersDb)
            log("phase=towers ms=${elapsedMs(started)}")
            if (cancel()) throw ImportCancelledException()
            val report = ZipFile(hrpZip).use { zf ->
                val entry = zf.getEntry(ENTRY) ?: throw HrpFormatException("missing $ENTRY in HRP zip")
                if (entry.crc < 0 || entry.size < 0) throw ZipException("$ENTRY has no CRC or size")
                val input = VerifyingInputStream(zf.getInputStream(entry), entry.crc, entry.size)
                val conn = driver.open(out.path)
                try {
                    val streamStarted = System.nanoTime()
                    HrpRun(conn, towers.sdds, cancel, progress).run(input, entry.size, etag, towers.stamp).also {
                        log("phase=stream ms=${elapsedMs(streamStarted)}")
                    }
                } finally {
                    conn.close()
                }
            }
            log("phase=total ms=${elapsedMs(started)}")
            return report
        } catch (t: Throwable) {
            deleteDbFiles(out)
            throw t
        }
    }

    private class Towers(val sdds: LongArray, val stamp: String)

    /** Every carrier SDD (sorted, as stored: the primary key) and the DB's extract stamp. */
    private fun loadTowers(db: File): Towers {
        if (!db.isFile) throw FileNotFoundException("tower DB not found: ${db.path}")
        val conn = if (driver is BundledSQLiteDriver) driver.open(db.path, SQLITE_OPEN_READONLY) else driver.open(db.path)
        try {
            val count = conn.prepare("SELECT COUNT(*) FROM carrier").use { check(it.step()); it.getLong(0).toInt() }
            val sdds = LongArray(count)
            var n = 0
            conn.prepare("SELECT sdd_id FROM carrier ORDER BY sdd_id").use { st ->
                while (st.step() && n < count) sdds[n++] = st.getLong(0)
            }
            check(n == count) { "carrier count changed while reading" }
            val stamp = conn.prepare("SELECT value FROM meta WHERE key = 'extract_stamp'").use { st ->
                if (st.step()) st.getText(0) else ""
            }
            return Towers(sdds, stamp)
        } finally {
            conn.close()
        }
    }

    private fun elapsedMs(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

    companion object {
        const val ENTRY = "LICENCE_.csv"
        const val POLL_ROWS = 10_000
    }
}

private class HrpRun(
    private val conn: SQLiteConnection,
    /** Sorted carrier SDDs; a device's index here is its slot in [stored]. */
    private val known: LongArray,
    private val cancel: () -> Boolean,
    private val progress: (Float) -> Unit,
) {
    private val stored = BooleanArray(known.size)
    private val powers = DoubleArray(ANGLES)
    private val q = ByteArray(ANGLES)
    private val patternIds = HashMap<PatternKey, Int>()

    private var devices = 0
    private var skippedIncomplete = 0
    private var skippedUnknown = 0

    // The current run of consecutive rows with one parsed SDD_ID.
    private var runSlot = -1 // index in [known], or -1 when not a carrier
    private var runRows = 0
    private var runOk = true

    private lateinit var insertPattern: SQLiteStatement
    private lateinit var insertDevice: SQLiteStatement

    fun run(input: VerifyingInputStream, total: Long, etag: String, towerStamp: String): HrpReport {
        conn.execSQL("PRAGMA journal_mode=OFF")
        conn.execSQL("PRAGMA synchronous=OFF")
        for (sql in PatternsDbSchema.CREATE) conn.execSQL(sql)
        insertPattern = conn.prepare("INSERT INTO pattern(id, att) VALUES (?, ?)")
        insertDevice =
            conn.prepare("INSERT INTO device_pattern(sdd_id, pattern_id, peak_bearing, peak_dbw) VALUES (?, ?, ?, ?)")
        try {
            conn.execSQL("BEGIN")
            progress(0f)

            val parser = HrpStreamParser(input)
            var rows = 0L
            var started = false
            var current = HrpStreamParser.INVALID_SDD
            while (parser.next()) {
                if (rows % HrpImporter.POLL_ROWS == 0L) {
                    if (cancel()) throw ImportCancelledException()
                    if (total > 0) progress((input.count.toFloat() / total).coerceIn(0f, 1f))
                }
                rows++
                val sdd = parser.sddId
                if (!started || sdd != current) {
                    if (started) finishRun(current)
                    started = true
                    current = sdd
                    startRun(sdd)
                }
                if (runSlot >= 0) {
                    if (runOk) {
                        if (runRows < ANGLES && parser.valid && parser.startAngle == runRows) {
                            powers[runRows] = parser.power
                        } else {
                            runOk = false
                        }
                    }
                    runRows++
                }
            }
            if (started) finishRun(current)
            input.verify() // the parser read to EOF, which already verified; never skip it

            val report = HrpReport(devices, patternIds.size, skippedIncomplete, skippedUnknown, rows)
            writeMeta(report, etag, towerStamp)
            conn.execSQL("COMMIT")
            progress(1f)
            return report
        } finally {
            insertPattern.close()
            insertDevice.close()
        }
    }

    private fun startRun(sdd: Long) {
        runSlot = if (sdd == HrpStreamParser.INVALID_SDD) -1 else known.binarySearch(sdd).coerceAtLeast(-1)
        runRows = 0
        runOk = true
    }

    /** Rules 2–4 for one run; [sdd] is the run's SDD_ID. */
    private fun finishRun(sdd: Long) {
        val slot = runSlot
        when {
            slot < 0 -> skippedUnknown++
            !runOk || runRows != ANGLES -> skippedIncomplete++
            stored[slot] -> Unit // a repeated complete run: the first wins, not counted
            else -> {
                stored[slot] = true
                store(sdd)
            }
        }
    }

    private fun store(sdd: Long) {
        // Rule 3: peak and first peak bearing, then attenuation relative to the peak, 0.25 dB steps.
        var peak = powers[0]
        var peakBearing = 0
        for (k in 1 until ANGLES) {
            if (powers[k] > peak) {
                peak = powers[k]
                peakBearing = k
            }
        }
        for (k in 0 until ANGLES) {
            val rel = peak - powers[(peakBearing + k) % ANGLES]
            q[k] = floor(rel * 4.0 + 0.5).coerceIn(0.0, 255.0).toInt().toByte()
        }

        // Rule 4: identical arrays share one ID, 1..N in first-seen order.
        var id = patternIds[PatternKey(q)]
        if (id == null) {
            id = patternIds.size + 1
            val att = q.copyOf()
            patternIds[PatternKey(att)] = id
            insertPattern.reset()
            insertPattern.bindLong(1, id.toLong())
            insertPattern.bindBlob(2, att)
            insertPattern.step()
        }
        insertDevice.reset()
        insertDevice.bindLong(1, sdd)
        insertDevice.bindLong(2, id.toLong())
        insertDevice.bindLong(3, peakBearing.toLong())
        insertDevice.bindDouble(4, peak)
        insertDevice.step()
        devices++
    }

    private fun writeMeta(report: HrpReport, etag: String, towerStamp: String) {
        val meta = listOf(
            PatternsDbSchema.META_SCHEMA_VERSION to PatternsDbSchema.VERSION.toString(),
            PatternsDbSchema.META_HRP_ETAG to etag,
            PatternsDbSchema.META_TOWER_STAMP to towerStamp,
            PatternsDbSchema.META_IMPORTED_AT to Instant.now().toString(),
            PatternsDbSchema.META_REPORT to Json.encodeToString(HrpReport.serializer(), report),
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

    private companion object {
        const val ANGLES = PatternsDbSchema.PATTERN_BYTES
    }
}

/** A quantised pattern as a hash key (content equality; the array must not change once used as a key). */
private class PatternKey(val att: ByteArray) {
    private val hash = att.contentHashCode()
    override fun hashCode(): Int = hash
    override fun equals(other: Any?): Boolean = other is PatternKey && other.hash == hash && other.att.contentEquals(att)
}

/**
 * Counts and CRC-32s the inflated bytes; at end of stream checks them against the zip directory's
 * CRC and size and throws [ZipException] on any mismatch.
 */
internal class VerifyingInputStream(
    input: InputStream,
    private val expectedCrc: Long,
    private val expectedSize: Long,
) : FilterInputStream(input) {
    private val crc = CRC32()
    var count = 0L
        private set
    private var verified = false

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) {
            crc.update(b)
            count++
        } else {
            verify()
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) {
            crc.update(b, off, n)
            count += n
        } else if (n < 0) {
            verify()
        }
        return n
    }

    override fun skip(n: Long): Long = throw UnsupportedOperationException("skipping would bypass the CRC check")

    /** Throws [ZipException] unless the whole entry has been read with the expected size and CRC. */
    fun verify() {
        if (verified) return
        if (count != expectedSize) throw ZipException("HRP entry size mismatch: read $count of $expectedSize bytes")
        val actual = crc.value
        if (actual != expectedCrc) {
            throw ZipException("invalid entry CRC (expected 0x%x but got 0x%x)".format(expectedCrc, actual))
        }
        verified = true
    }
}
