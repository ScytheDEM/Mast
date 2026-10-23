package au.signalatlas.core.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import au.signalatlas.core.acma.hrp.HrpReport
import au.signalatlas.core.acma.hrp.PatternsDbSchema
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import kotlinx.serialization.json.Json

/**
 * The installed horizontal radiation patterns, fully in memory: [open] reads `patterns.db` once and
 * closes it, so lookups never touch SQLite and the store needs no lifetime management.
 *
 * Pattern `id` (1..[patternCount]) occupies `patternBytes[(id − 1) * 360 until id * 360]`: the
 * attenuation in 0.25 dB steps at each degree clockwise of the device's peak bearing.
 */
class PatternStore private constructor(
    val patternBytes: ByteArray,
    val patternCount: Int,
    /** Sorted SDD IDs, with each device's pattern ID and peak bearing at the same index. */
    private val sdds: LongArray,
    private val patternIds: IntArray,
    private val peakBearings: ShortArray,
    val etag: String,
    /** The `extract_stamp` of the tower data these patterns were filtered against. */
    val towerStamp: String,
    val importedAt: Instant,
    val report: HrpReport,
) : Closeable {
    val deviceCount: Int get() = sdds.size

    @Volatile var isClosed: Boolean = false
        private set

    /**
     * For each `sddIds[i]` writes its pattern ID and peak bearing (degrees) to `outPatternId[i]` and
     * `outPeakBearing[i]`, or 0 and 0 when the device has no pattern. Binary search; no allocation.
     */
    fun lookup(sddIds: LongArray, outPatternId: IntArray, outPeakBearing: ShortArray) {
        require(outPatternId.size >= sddIds.size && outPeakBearing.size >= sddIds.size) { "output arrays too small" }
        for (i in sddIds.indices) {
            val k = sdds.binarySearch(sddIds[i])
            if (k >= 0) {
                outPatternId[i] = patternIds[k]
                outPeakBearing[i] = peakBearings[k]
            } else {
                outPatternId[i] = 0
                outPeakBearing[i] = 0
            }
        }
    }

    /** Nothing to release (everything is in memory); marks the store as replaced. */
    override fun close() {
        isClosed = true
    }

    companion object {
        /**
         * Loads [db] read-only. Throws [FileNotFoundException] (without creating it) when it is missing,
         * [IOException] for another schema version or an inconsistent file.
         * For a [driver] other than [BundledSQLiteDriver] the caller must supply one that opens read-only.
         */
        fun open(db: File, driver: SQLiteDriver = BundledSQLiteDriver()): PatternStore {
            if (!db.isFile) throw FileNotFoundException("patterns DB not found: ${db.path}")
            val conn = if (driver is BundledSQLiteDriver) driver.open(db.path, SQLITE_OPEN_READONLY) else driver.open(db.path)
            try {
                return load(conn)
            } finally {
                conn.close()
            }
        }

        private fun load(conn: SQLiteConnection): PatternStore {
            val meta = HashMap<String, String>()
            conn.prepare("SELECT key, value FROM meta").use { st -> while (st.step()) meta[st.getText(0)] = st.getText(1) }
            val version = meta[PatternsDbSchema.META_SCHEMA_VERSION]?.toIntOrNull()
            if (version != PatternsDbSchema.VERSION) {
                throw IOException("patterns DB schema version ${version ?: "(none)"}, expected ${PatternsDbSchema.VERSION}")
            }

            val size = PatternsDbSchema.PATTERN_BYTES
            val patternCount = count(conn, "pattern")
            val bytes = ByteArray(patternCount * size)
            var expectedId = 1L
            conn.prepare("SELECT id, att FROM pattern ORDER BY id").use { st ->
                while (st.step()) {
                    val att = st.getBlob(1)
                    if (st.getLong(0) != expectedId || att.size != size || expectedId > patternCount) {
                        throw IOException("corrupt patterns DB: pattern ${st.getLong(0)}")
                    }
                    att.copyInto(bytes, ((expectedId - 1) * size).toInt())
                    expectedId++
                }
            }

            val deviceCount = count(conn, "device_pattern")
            val sdds = LongArray(deviceCount)
            val ids = IntArray(deviceCount)
            val bearings = ShortArray(deviceCount)
            var n = 0
            conn.prepare("SELECT sdd_id, pattern_id, peak_bearing FROM device_pattern ORDER BY sdd_id").use { st ->
                while (st.step()) {
                    val id = st.getLong(1)
                    val bearing = st.getLong(2)
                    if (n == deviceCount || id !in 1..patternCount || bearing !in 0..359) {
                        throw IOException("corrupt patterns DB: device ${st.getLong(0)}")
                    }
                    sdds[n] = st.getLong(0)
                    ids[n] = id.toInt()
                    bearings[n] = bearing.toInt().toShort()
                    n++
                }
            }

            return PatternStore(
                patternBytes = bytes,
                patternCount = patternCount,
                sdds = sdds,
                patternIds = ids,
                peakBearings = bearings,
                etag = meta[PatternsDbSchema.META_HRP_ETAG] ?: "",
                towerStamp = meta[PatternsDbSchema.META_TOWER_STAMP] ?: "",
                importedAt = Instant.parse(meta[PatternsDbSchema.META_IMPORTED_AT] ?: throw IOException("no imported_at")),
                report = Json.decodeFromString(
                    HrpReport.serializer(),
                    meta[PatternsDbSchema.META_REPORT] ?: throw IOException("no report"),
                ),
            )
        }

        private fun count(conn: SQLiteConnection, table: String): Int =
            conn.prepare("SELECT COUNT(*) FROM $table").use { st -> check(st.step()); st.getLong(0).toInt() }
    }
}
