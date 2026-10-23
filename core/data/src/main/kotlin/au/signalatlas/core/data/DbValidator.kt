package au.signalatlas.core.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import au.signalatlas.core.acma.TowerDbSchema
import java.io.File

sealed interface Validation {
    data object Ok : Validation
    data class Invalid(val reason: String) : Validation
}

/** Checks a freshly built tower DB before it may replace the live one. Never throws. */
class DbValidator(
    private val minSites: Int = 10_000,
    private val driver: SQLiteDriver = BundledSQLiteDriver(),
) : CandidateValidator {
    override fun validate(db: File): Validation {
        if (!db.isFile) return Validation.Invalid("missing file")
        return try {
            open(db).use { conn -> check(conn) }
        } catch (t: Throwable) {
            Validation.Invalid("unreadable database: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun open(db: File): SQLiteConnection =
        if (driver is BundledSQLiteDriver) driver.open(db.path, SQLITE_OPEN_READONLY) else driver.open(db.path)

    private fun check(conn: SQLiteConnection): Validation {
        val quick = text(conn, "PRAGMA quick_check")
        if (quick != "ok") return Validation.Invalid("integrity check failed: $quick")

        val version = text(conn, "SELECT value FROM meta WHERE key = 'schema_version'")
        if (version != TowerDbSchema.VERSION.toString()) {
            return Validation.Invalid("schema version $version, expected ${TowerDbSchema.VERSION}")
        }

        val sites = long(conn, "SELECT COUNT(*) FROM site")
        if (sites < minSites) return Validation.Invalid("site count $sites below minimum $minSites")

        val orphanSectors = long(conn, "SELECT COUNT(*) FROM sector WHERE site_id NOT IN (SELECT id FROM site)")
        if (orphanSectors > 0) return Validation.Invalid("orphan sector: $orphanSectors sector(s) without a site")

        val orphanCarriers = long(conn, "SELECT COUNT(*) FROM carrier WHERE sector_id NOT IN (SELECT id FROM sector)")
        if (orphanCarriers > 0) return Validation.Invalid("orphan carrier: $orphanCarriers carrier(s) without a sector")

        val outOfBounds = long(
            conn,
            "SELECT COUNT(*) FROM site WHERE lat NOT BETWEEN -55 AND -9 OR lon NOT BETWEEN 72 AND 169",
        )
        if (outOfBounds > 0) return Validation.Invalid("$outOfBounds site(s) outside Australian bounds")

        return Validation.Ok
    }

    private fun text(conn: SQLiteConnection, sql: String): String =
        conn.prepare(sql).use { st -> if (st.step() && !st.isNull(0)) st.getText(0) else "(none)" }

    private fun long(conn: SQLiteConnection, sql: String): Long =
        conn.prepare(sql).use { st -> if (st.step()) st.getLong(0) else 0L }
}
