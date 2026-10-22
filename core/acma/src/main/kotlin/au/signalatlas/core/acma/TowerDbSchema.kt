package au.signalatlas.core.acma

/** Tower DB schema v1 (`towers.db`). `network`/`tech` columns hold enum ordinals. */
object TowerDbSchema {
    const val VERSION = 1

    val TABLES: List<String> = listOf(
        "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        "CREATE TABLE site(id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, name TEXT NOT NULL, " +
            "state TEXT, postcode TEXT, elevation_m REAL)",
        "CREATE TABLE antenna(id INTEGER PRIMARY KEY, gain_dbi REAL, f2b_db REAL, h_bw_deg REAL, model TEXT, " +
            "manufacturer TEXT)",
        "CREATE TABLE sector(id INTEGER PRIMARY KEY, site_id INTEGER NOT NULL, network INTEGER NOT NULL, " +
            "antenna_id INTEGER, azimuth_deg REAL, height_m REAL NOT NULL)",
        "CREATE TABLE carrier(sdd_id INTEGER PRIMARY KEY, sector_id INTEGER NOT NULL, freq_hz INTEGER NOT NULL, " +
            "bw_hz INTEGER NOT NULL, eirp_dbm REAL NOT NULL, tilt_deg REAL NOT NULL, tech INTEGER NOT NULL, " +
            "band TEXT NOT NULL, licence_no TEXT NOT NULL, device_reg_id TEXT NOT NULL)",
        "CREATE TABLE site_summary(site_id INTEGER PRIMARY KEY, network_mask INTEGER NOT NULL, " +
            "tech_mask INTEGER NOT NULL, bands TEXT NOT NULL)",
    )

    val INDEXES: List<String> = listOf(
        "CREATE INDEX sector_site ON sector(site_id)",
        "CREATE INDEX carrier_sector ON carrier(sector_id)",
    )

    /** Full schema; the importer runs [TABLES] first and [INDEXES] after bulk loading. */
    val CREATE: List<String> = TABLES + INDEXES
}
