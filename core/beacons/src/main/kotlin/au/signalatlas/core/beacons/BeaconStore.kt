package au.signalatlas.core.beacons

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import au.signalatlas.core.model.LatLon
import java.io.Closeable
import java.io.File

/**
 * A beacon with a known position. [weightSum] / [n] are the learning sufficient statistics of a [Source.LEARNED] beacon
 * (the position is the weighted centroid of the fixes seen at RSSI >= [BeaconLearner.MIN_RSSI]); an [Source.IMPORTED]
 * position comes from the user's CSV and is never moved by learning.
 */
data class KnownBeacon(val id: String, val pos: LatLon, val source: Source, val weightSum: Double = 0.0, val n: Int = 0, val peakRssi: Int = -127) {
    enum class Source { LEARNED, IMPORTED }
}

/** Result of a CSV import: beacons stored and lines skipped (blank lines, comments and the header are not counted). */
data class CsvImportResult(val imported: Int, val skipped: Int)

/** Where beacon positions live. */
interface BeaconPositions {
    fun get(id: String): KnownBeacon?
}

/** [BeaconPositions] that can also store a position ([BeaconStore] is the database-backed one). */
interface BeaconRepository : BeaconPositions {
    fun put(b: KnownBeacon)
}

/**
 * `beacons.db` (schema v1): `beacon(id, lat, lon, weight_sum, n, peak_rssi, source, updated_ms)`. Positions are held in
 * memory too (a few hundred rows at most), so [get] never touches SQLite. Blocking and thread-safe.
 */
class BeaconStore private constructor(private val conn: SQLiteConnection) : BeaconRepository, Closeable {
    private val lock = Any()
    private val cache = HashMap<String, KnownBeacon>()

    init {
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS beacon(id TEXT PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, weight_sum REAL NOT NULL, " +
                "n INTEGER NOT NULL, peak_rssi INTEGER NOT NULL, source INTEGER NOT NULL, updated_ms INTEGER NOT NULL)",
        )
        conn.prepare("SELECT id, lat, lon, weight_sum, n, peak_rssi, source FROM beacon").use { st ->
            while (st.step()) {
                val b = KnownBeacon(
                    st.getText(0), LatLon(st.getDouble(1), st.getDouble(2)), KnownBeacon.Source.entries[st.getLong(6).toInt()],
                    st.getDouble(3), st.getLong(4).toInt(), st.getLong(5).toInt(),
                )
                cache[b.id] = b
            }
        }
    }

    override fun get(id: String): KnownBeacon? = synchronized(lock) { cache[id] }

    fun count(): Int = synchronized(lock) { cache.size }

    fun all(): List<KnownBeacon> = synchronized(lock) { cache.values.toList() }

    override fun put(b: KnownBeacon) = put(b, System.currentTimeMillis())

    fun put(b: KnownBeacon, nowMs: Long) = synchronized(lock) {
        conn.prepare(
            "INSERT OR REPLACE INTO beacon(id, lat, lon, weight_sum, n, peak_rssi, source, updated_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { st ->
            st.bindText(1, b.id); st.bindDouble(2, b.pos.lat); st.bindDouble(3, b.pos.lon); st.bindDouble(4, b.weightSum)
            st.bindLong(5, b.n.toLong()); st.bindLong(6, b.peakRssi.toLong()); st.bindLong(7, b.source.ordinal.toLong())
            st.bindLong(8, nowMs)
            st.step()
        }
        cache[b.id] = b
        Unit
    }

    fun deleteAll() = synchronized(lock) {
        conn.execSQL("DELETE FROM beacon")
        cache.clear()
    }

    /** Imports `id,lat,lon` lines (see [BeaconCsv]); imported positions replace learned ones for the same id. */
    fun importCsv(text: String): CsvImportResult {
        val rows = BeaconCsv.parse(text)
        synchronized(lock) {
            conn.execSQL("BEGIN")
            try {
                rows.beacons.forEach { put(KnownBeacon(it.id, it.pos, KnownBeacon.Source.IMPORTED)) }
                conn.execSQL("COMMIT")
            } catch (t: Throwable) {
                conn.execSQL("ROLLBACK")
                throw t
            }
        }
        return CsvImportResult(rows.beacons.size, rows.skipped)
    }

    override fun close() = synchronized(lock) { conn.close() }

    companion object {
        fun open(file: File): BeaconStore {
            file.parentFile?.mkdirs()
            return BeaconStore(BundledSQLiteDriver().open(file.path))
        }

        /** An in-memory store (tests). */
        fun openInMemory(): BeaconStore = BeaconStore(BundledSQLiteDriver().open(":memory:"))
    }
}

/** `id,lat,lon` lines: a header line, blank lines and `#` comments are ignored; bad ids or coordinates are skipped. */
object BeaconCsv {
    class Parsed(val beacons: List<KnownBeacon>, val skipped: Int)

    fun parse(text: String): Parsed {
        val out = LinkedHashMap<String, KnownBeacon>()
        var skipped = 0
        text.lineSequence().map { it.trim().trimStart('﻿') }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEachIndexed { i, line ->
            val parts = line.split(',').map { it.trim().trim('"') }
            if (i == 0 && parts.getOrNull(0).equals("id", ignoreCase = true)) return@forEachIndexed
            val id = parts.getOrNull(0)?.let(BeaconIds::normalise)
            val lat = parts.getOrNull(1)?.toDoubleOrNull()
            val lon = parts.getOrNull(2)?.toDoubleOrNull()
            if (parts.size != 3 || id == null || lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                skipped++
            } else {
                out[id] = KnownBeacon(id, LatLon(lat, lon), KnownBeacon.Source.IMPORTED)
            }
        }
        return Parsed(out.values.toList(), skipped)
    }
}

/** Opens the database on first use, so constructing the object graph at start-up touches no file. */
class LazyBeaconRepository(private val delegate: Lazy<BeaconRepository>) : BeaconRepository {
    override fun get(id: String): KnownBeacon? = delegate.value.get(id)
    override fun put(b: KnownBeacon) = delegate.value.put(b)
}
