package au.signalatlas.core.acma.hrp

/** `patterns.db` schema v1 (HRP import rule 5). `att` is 360 bytes of attenuation in 0.25 dB steps. */
object PatternsDbSchema {
    const val VERSION = 1
    /** Bytes per pattern: one quantised attenuation per degree, relative to the peak bearing. */
    const val PATTERN_BYTES = 360

    const val META_SCHEMA_VERSION = "schema_version"
    const val META_HRP_ETAG = "hrp_etag"
    /** The `extract_stamp` of the towers.db the SDD filter was built from. */
    const val META_TOWER_STAMP = "tower_stamp"
    const val META_IMPORTED_AT = "imported_at"
    const val META_REPORT = "report"

    val CREATE: List<String> = listOf(
        "CREATE TABLE pattern(id INTEGER PRIMARY KEY, att BLOB NOT NULL)",
        "CREATE TABLE device_pattern(sdd_id INTEGER PRIMARY KEY, pattern_id INTEGER NOT NULL, " +
            "peak_bearing INTEGER NOT NULL, peak_dbw REAL NOT NULL)",
        "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
    )
}
