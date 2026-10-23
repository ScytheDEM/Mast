package au.signalatlas.core.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import au.signalatlas.core.acma.hrp.PatternsDbSchema
import java.io.File

/**
 * Installs `patterns.db` in [dir] (the tower DB's directory) with [DbInstaller]'s discipline:
 * validate, fsync, keep the old file as `patterns.prev.db` by hard link, one atomic rename. The
 * candidate is built as [building] in the same directory.
 */
class PatternInstaller(
    dir: File,
    validator: CandidateValidator = PatternDbValidator(),
) {
    private val installer = DbInstaller(dir, validator, name = NAME)

    val live: File get() = installer.live
    /** Where the importer builds the candidate (deleted by [cleanupStale]; never opened as patterns). */
    val building: File = File(dir, "$NAME.db.building")

    fun install(candidate: File): Validation = installer.install(candidate)

    fun recover(): Boolean = installer.recover()

    /** Only before an HRP import starts: recovers a missing live file and deletes stale `patterns.*.building`. */
    fun cleanupStale() = installer.cleanupStale()

    private companion object {
        const val NAME = "patterns"
    }
}

/**
 * Checks a freshly built `patterns.db`: integrity, schema version 1, at least one pattern, every
 * device's pattern exists and every pattern is 360 bytes. Never throws.
 */
class PatternDbValidator(private val driver: SQLiteDriver = BundledSQLiteDriver()) : CandidateValidator {
    override fun validate(db: File): Validation {
        if (!db.isFile) return Validation.Invalid("missing file")
        return try {
            val conn = if (driver is BundledSQLiteDriver) driver.open(db.path, SQLITE_OPEN_READONLY) else driver.open(db.path)
            try {
                check(conn)
            } finally {
                conn.close()
            }
        } catch (t: Throwable) {
            Validation.Invalid("unreadable database: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun check(conn: SQLiteConnection): Validation {
        val quick = text(conn, "PRAGMA quick_check")
        if (quick != "ok") return Validation.Invalid("integrity check failed: $quick")

        val version = text(conn, "SELECT value FROM meta WHERE key = '${PatternsDbSchema.META_SCHEMA_VERSION}'")
        if (version != PatternsDbSchema.VERSION.toString()) {
            return Validation.Invalid("schema version $version, expected ${PatternsDbSchema.VERSION}")
        }

        val patterns = long(conn, "SELECT COUNT(*) FROM pattern")
        if (patterns < 1) return Validation.Invalid("pattern count $patterns, expected at least 1")

        val dangling = long(conn, "SELECT COUNT(*) FROM device_pattern WHERE pattern_id NOT IN (SELECT id FROM pattern)")
        if (dangling > 0) return Validation.Invalid("$dangling device(s) reference a missing pattern")

        val badBlobs = long(
            conn,
            "SELECT COUNT(*) FROM pattern WHERE typeof(att) <> 'blob' OR length(att) <> ${PatternsDbSchema.PATTERN_BYTES}",
        )
        if (badBlobs > 0) return Validation.Invalid("$badBlobs pattern(s) not ${PatternsDbSchema.PATTERN_BYTES} bytes")

        return Validation.Ok
    }

    private fun text(conn: SQLiteConnection, sql: String): String =
        conn.prepare(sql).use { st -> if (st.step() && !st.isNull(0)) st.getText(0) else "(none)" }

    private fun long(conn: SQLiteConnection, sql: String): Long =
        conn.prepare(sql).use { st -> if (st.step()) st.getLong(0) else 0L }
}
