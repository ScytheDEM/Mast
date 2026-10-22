# Signal Atlas — Milestone 1: Tower Map Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An installable Android app that imports the ACMA register on the phone, shows every mobile
tower on a clean offline-capable MapLibre map, keeps the data fresh (check on open, weekly Wi-Fi +
charging, manual update), and offers filters, search, site details and a nearby list.

**Architecture:** Pure-Kotlin JVM modules (`:core:model`, `:core:acma`, `:core:data`) hold all data
logic and run their tests on the host, using the androidx bundled SQLite driver (the same code runs on
the device). The Android modules (`:core:update`, `:core:map`, `:app`) add WorkManager, OkHttp, MapLibre
and Compose. The tower DB is built into a new file and swapped in atomically; reads go through an
in-memory grid index.

**Tech Stack:** Kotlin 2.4.21, AGP 9.4.1 (built-in Kotlin), Gradle 9.8.2, JDK 17, compile/target SDK 37,
min SDK 29, Compose BOM 2026.09.00, Material 3 1.4.0, material3-adaptive 1.3.0, androidx.sqlite
(sqlite-bundled) 2.7.1, WorkManager 2.12.0, DataStore 1.3.0, OkHttp 5.5.1 (+ mockwebserver3),
kotlinx-coroutines 1.11.0, kotlinx-serialization-json 1.12.0, MapLibre Android
`org.maplibre.gl:android-sdk-vulkan` 13.7.1, androidx.window 1.5.1, JUnit 4 + Truth 1.4.5, Robolectric
(latest stable).

**Spec:** `docs/superpowers/specs/2026-10-22-signal-atlas-design.md`

## Milestone roadmap (each one gets its own plan, written after the previous milestone ships)

| # | Milestone | Delivers |
|---|---|---|
| **M1** | **Tower map (this plan)** | ACMA on-device import + updates, map, filters, search, site sheet, nearby list |
| M2 | Coverage engine | C++ engine (quick model, EIRP/RE, 3GPP patterns), monthly HRP import, GPU coverage layer, legend |
| M3 | Terrain & buildings | Copernicus/OSM/MS data, P.1812 + P.526 + P.1411, "Increase resolution", region packs, offline base-map packs |
| M4 | Radio & calibration | Connected tower, carrier/MVNO/dual-SIM, info bar, calibration logging + estimator |
| M5 | Trip mode | Foreground service, road look-ahead, alerts, Live Updates, QS tile |
| M6 | Android Auto | `fullMap` + `play` flavours |
| M7 | Polish & performance | Foldable postures, export/share, Baseline Profiles, ADPF/thermal, 120 Hz gates |

## Global Constraints

- Package `au.signalatlas.app`; module namespaces `au.signalatlas.<module>` (for example `au.signalatlas.core.acma`).
- minSdk 29, compileSdk 37, targetSdk 37; ABIs `arm64-v8a`, `x86_64`.
- No accounts and no API keys for any data source. No analytics, no ads.
- ACMA URLs (exact): `https://cdn.acma.gov.au/rrl/spectra_rrl.zip`, `https://backend.acma.gov.au/rrl/v1/extracts/datetime-of-extract.txt`.
- Base map: Protomaps v4 schema from `https://build.protomaps.com/<key>` where `<key>` is the last entry of `https://build-metadata.protomaps.dev/builds.json` (for example `20261022.pmtiles`), loaded through MapLibre's `pmtiles://` URL scheme.
- No heavy work on the main thread: DB, import, GeoJSON building and network all run on `Dispatchers.IO` / `Default`.
- Automatic updates: unmetered network + charging, at most once every 7 days. If data is more than 14 days old: unmetered only. Manual "Update now" may use any network, after confirming the size when metered.
- Material 3 with dynamic colour on API 31+; edge-to-edge; light and dark.
- Request the display's highest refresh rate (target 120 Hz).
- Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

### Import rules (shared by the Python reference and the Kotlin importer; any change must be made to both)

1. Read CSVs as ISO-8859-1, RFC 4180 (quoted fields, `""` escapes, CRLF or LF). Columns are looked up by header name, never by position.
2. **Licences kept:** `LICENCE_TYPE_NAME` ∈ {`Spectrum`, `PTS`} and the `CLIENT_NO`'s `LICENCEE` maps to a network:
   `TELSTRA LIMITED`, `Telstra 3G Spectrum Holdings Pty Ltd` → `TELSTRA`; `Optus Mobile Pty Limited` → `OPTUS`;
   `Vodafone Hutchison Australia Pty Limited`, `Vodafone Australia Pty Limited`, `TPG INTERNET PTY LTD` → `TPG`;
   `MOBILE JV PTY LIMITED` → `OPTUS_TPG_JV`; `NBN CO LIMITED` → `NBN`. Matching is exact and case-insensitive after trimming.
3. **Devices kept:** `DEVICE_TYPE == "T"` and `LICENCE_NO` is a kept licence.
4. **Band plan** (`FREQUENCY` is in Hz; compare `FREQUENCY / 1e6` in MHz against inclusive ranges, first match wins) → (band, tech):
   758–803 `B28` LTE · 859–868.999 `B26` LTE, except network `TELSTRA` → `n26` NR · 869–894 `B5` LTE, except network `TELSTRA` → `n5` NR · 925–960 `B8` LTE · 1805–1880 `B3` LTE ·
   2110–2170 `B1` LTE · 2300–2400 `B40` LTE · 2620–2690 `B7` LTE · 3300–3800 `n78` NR · 3800.001–4200 `n77` NR · 24250–27499.999 `n258` NR ·
   27500–29500 `n257` NR. Otherwise the device is **skipped** (`skippedUnknownBand`).
5. **EIRP (dBm):** if `EIRP` is non-blank, convert using `EIRP_UNIT` (`W`: 10·log10(v)+30, `mW`: 10·log10(v), `kW`: 10·log10(v)+60, `dBW`: v+30, `dBm`: v).
   Otherwise, if `TRANSMITTER_POWER` is non-blank, convert using `TRANSMITTER_POWER_UNIT` the same way, then add antenna `GAIN` (dBi, blank = 0) and subtract `FEEDER_LOSS` (blank = 0).
   A non-blank `EIRP` whose value is ≤ 0 in a linear unit (`W`, `mW`, `kW`) counts as not supplied and falls through to `TRANSMITTER_POWER`.
   Otherwise (no usable value, transmitter power ≤ 0 in a linear unit, unparseable number, or unrecognised unit) **skip** (`skippedNoPower`). Round to 0.01 dB.
   Skip precedence per device: band (rule 4) → power (rule 5) → site (rule 7); a device is counted under the first rule that skips it.
6. **Sector key:** (`SITE_ID`, network, `ANTENNA_ID`, azimuth, height) where azimuth = `AZIMUTH` as float rounded to 0.1° (blank → null = omni) and height = `HEIGHT` rounded to 0.1 m (blank → 0.0). Sector IDs are assigned 1..N in first-seen order of `device_details.csv`.
7. **Sites kept:** referenced by at least one kept device, with `LATITUDE`/`LONGITUDE` parseable and inside lat [−55, −9], lon [72, 169]. Devices on a missing or out-of-bounds site are **skipped** (`skippedBadSite`), and a sector with no remaining carriers is dropped.
8. **Antennas kept:** referenced by a kept sector. `GAIN`, `FRONT_TO_BACK`, `H_BEAMWIDTH`: blank or `0` → null (gain null → 0.0 when used in rule 5).
9. **Tilt:** `TILT` as float, blank → 0.0.
10. `ImportReport` counts: `sites`, `sectors`, `carriers`, `antennas`, `skippedUnknownBand`, `skippedNoPower`, `skippedBadSite`, and `carriersByNetwork` (map of network name → count).

### DB schema v1 (`towers.db`)

```sql
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE site(id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, name TEXT NOT NULL, state TEXT, postcode TEXT, elevation_m REAL);
CREATE TABLE antenna(id INTEGER PRIMARY KEY, gain_dbi REAL, f2b_db REAL, h_bw_deg REAL, model TEXT, manufacturer TEXT);
CREATE TABLE sector(id INTEGER PRIMARY KEY, site_id INTEGER NOT NULL, network INTEGER NOT NULL, antenna_id INTEGER, azimuth_deg REAL, height_m REAL NOT NULL);
CREATE TABLE carrier(sdd_id INTEGER PRIMARY KEY, sector_id INTEGER NOT NULL, freq_hz INTEGER NOT NULL, bw_hz INTEGER NOT NULL, eirp_dbm REAL NOT NULL, tilt_deg REAL NOT NULL, tech INTEGER NOT NULL, band TEXT NOT NULL, licence_no TEXT NOT NULL, device_reg_id TEXT NOT NULL);
CREATE TABLE site_summary(site_id INTEGER PRIMARY KEY, network_mask INTEGER NOT NULL, tech_mask INTEGER NOT NULL, bands TEXT NOT NULL);
CREATE INDEX sector_site ON sector(site_id);
CREATE INDEX carrier_sector ON carrier(sector_id);
```
`network`/`tech` columns store the enum `ordinal`; the masks are `1 shl ordinal`; `bands` is a sorted comma-joined list.
Meta keys: `schema_version`=`1`, `extract_stamp`, `zip_etag`, `imported_at` (ISO-8601 UTC), `report` (ImportReport JSON).

## Review Focus

1. **Malformed or partial ACMA rows** (blank lat/lon, blank EIRP and power, unknown unit, blank azimuth, sites outside Australia) → skipped and counted; the import still succeeds. *Pinned in Task 5.*
2. **Import interrupted, failed or out of storage** → the previous `towers.db` stays active and the app keeps working; nothing half-written is ever opened. *Pinned in Task 6.*
3. **First launch with no network and no DB** → a clear "Download tower data" screen with retry, never a crash or an endless spinner. *Pinned in Task 11.*
4. **The remote zip changes during a resumed download** (ETag differs, or the server ignores `Range` and returns 200) → restart from byte 0, never splice two files. *Pinned in Task 8.*
5. **Location permission denied** → the map opens over Australia, and the nearby list explains why it is empty; no crash. *Pinned in Task 11.*

---

### Task 0: Toolchain

**Files:**
- Create: `docs/dev-setup.md`

- [ ] **Step 1: Install the Android SDK command-line tools** to `~/Library/Android/sdk` (`commandlinetools-mac-15641748_latest.zip` from `dl.google.com/android/repository/`), then accept licences.
- [ ] **Step 2: Install packages**: `platform-tools`, `platforms;android-37.0`, the latest `build-tools;37.*`, `emulator`, and `system-images;android-37.0;google_apis;arm64-v8a`. (The NDK and CMake are not needed until M2.)
- [ ] **Step 3: Create an emulator** `sa_pixel` (device profile `pixel_9`, the image above).
- [ ] **Step 4: Verify**

Run: `~/Library/Android/sdk/platform-tools/adb version && ~/Library/Android/sdk/emulator/emulator -list-avds`
Expected: an adb version line, and `sa_pixel` listed.

- [ ] **Step 5: Write `docs/dev-setup.md`** (the exact install commands, `ANDROID_HOME`, and how to start the emulator) and commit.

### Task 1: Project scaffold

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, the Gradle wrapper (9.8.2), `.gitignore`
- Create: `core/model/build.gradle.kts`, `core/acma/build.gradle.kts`, `core/data/build.gradle.kts` (Kotlin JVM, JVM toolchain 17)
- Create: `core/update/build.gradle.kts`, `core/map/build.gradle.kts` (Android library), `app/build.gradle.kts` (Android application, Compose)
- Create: `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/au/signalatlas/app/MainActivity.kt`
- Test: `core/model/src/test/kotlin/au/signalatlas/core/model/SmokeTest.kt`

**Interfaces:**
- Produces: module paths `:core:model`, `:core:acma`, `:core:data`, `:core:update`, `:core:map`, `:app`. Dependency direction: `acma → model`, `data → model`, `update → acma, data`, `map → model`, `app → all`.

- [ ] **Step 1: Write `SmokeTest.smoke()`** asserting `1 + 1 == 2` (proves the test wiring works).
- [ ] **Step 2: Create the build files** with the versions from the Tech Stack in `libs.versions.toml`. `:core:acma` and `:core:data` depend on `androidx.sqlite:sqlite-bundled` (JVM variant on host, Android on device). `MainActivity` shows a Material 3 `Text("Signal Atlas")`.
- [ ] **Step 3: Verify**

Run: `./gradlew test :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 4: Commit** `chore: scaffold Signal Atlas project`.

### Task 2: Domain model, network mapping, band plan

**Files:**
- Create: `core/model/src/main/kotlin/au/signalatlas/core/model/{Network.kt,Tech.kt,TowerTypes.kt,BandPlan.kt,NetworkMapping.kt,GeoBounds.kt}`
- Test: `core/model/src/test/kotlin/au/signalatlas/core/model/{BandPlanTest.kt,NetworkMappingTest.kt}`

**Interfaces — Produces:**
```kotlin
enum class Network { TELSTRA, OPTUS, TPG, OPTUS_TPG_JV, NBN }      // ordinal is persisted; append only
enum class Tech { LTE, NR }                                         // ordinal is persisted; append only
data class BandInfo(val band: String, val tech: Tech)
object BandPlan { fun classify(freqHz: Long, network: Network): BandInfo? }
object NetworkMapping { fun fromLicensee(name: String): Network? }
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) { fun contains(lat: Double, lon: Double): Boolean }
data class Site(val id: Int, val lat: Double, val lon: Double, val name: String, val state: String?, val postcode: String?, val elevationM: Double?)
data class Antenna(val id: Int, val gainDbi: Double?, val frontToBackDb: Double?, val hBeamwidthDeg: Double?, val model: String?, val manufacturer: String?)
data class Carrier(val sddId: Long, val freqHz: Long, val bandwidthHz: Long, val eirpDbm: Double, val tiltDeg: Double, val band: BandInfo, val licenceNo: String, val deviceRegId: String)
data class Sector(val id: Int, val siteId: Int, val network: Network, val antenna: Antenna?, val azimuthDeg: Double?, val heightM: Double, val carriers: List<Carrier>)
data class SiteSummary(val id: Int, val lat: Double, val lon: Double, val name: String, val networks: Set<Network>, val techs: Set<Tech>, val bands: Set<String>)
data class SiteDetail(val site: Site, val sectors: List<Sector>)
data class TowerFilter(val networks: Set<Network> = Network.entries.toSet(), val techs: Set<Tech> = Tech.entries.toSet(), val bands: Set<String>? = null) { fun matches(s: SiteSummary): Boolean }
```

- [ ] **Step 1: Write the failing tests**
  - `BandPlanTest`: `classify(778_000_000, TELSTRA) == BandInfo("B28", LTE)`; `classify(872_500_000, TELSTRA) == BandInfo("n5", NR)`; `classify(872_500_000, TPG) == BandInfo("B5", LTE)`; `classify(948_000_000, OPTUS) == BandInfo("B8", LTE)`; `classify(2_352_000_000, NBN) == BandInfo("B40", LTE)`; `classify(3_410_000_000, NBN) == BandInfo("n78", NR)`; `classify(27_499_000_000, TELSTRA)!!.band == "n258"`; `classify(28_900_000_000, NBN)!!.band == "n257"`; `classify(450_000_000, TELSTRA) == null`.
  - `NetworkMappingTest`: each of the 8 licensee strings in Import rule 2 maps as listed; `" telstra limited "` → `TELSTRA`; `"Optus Networks Pty Limited"` → `null`.
  - `TowerFilter.matches`: a site with `networks={TELSTRA}`, `techs={NR}`, `bands={"n78"}` matches the default filter; it does not match `networks={OPTUS}`, nor `techs={LTE}`, nor `bands={"B28"}`; it does match `bands={"n78","B28"}`.
- [ ] **Step 2: Run** `./gradlew :core:model:test` → FAIL (unresolved references).
- [ ] **Step 3: Implement** the types and both objects exactly per Import rules 2 and 4.
- [ ] **Step 4: Run** `./gradlew :core:model:test` → PASS.
- [ ] **Step 5: Commit** `feat(model): tower domain types, network mapping and band plan`.

### Task 3: Streaming CSV reader

**Files:**
- Create: `core/acma/src/main/kotlin/au/signalatlas/core/acma/csv/CsvReader.kt`
- Test: `core/acma/src/test/kotlin/au/signalatlas/core/acma/csv/CsvReaderTest.kt`

**Interfaces — Produces:**
```kotlin
class CsvReader(input: java.io.InputStream, bufferSize: Int = 1 shl 16) : java.io.Closeable {
    val header: List<String>
    fun column(name: String): Int                  // throws AcmaFormatException("missing column <name> ...") if absent
    fun next(): Boolean                            // advances; false at EOF
    operator fun get(col: Int): String             // current row; "" for missing trailing fields
}
class AcmaFormatException(message: String) : Exception(message)
```
Approach: a byte-level state machine over a reused buffer, decoding ISO-8859-1 (byte → char), reusing one `StringBuilder` per field. No regex and no per-row `String.split`.

- [ ] **Step 1: Write the failing tests**: plain row; quoted field containing a comma and a `""` escape (`"a,""b"""` → `a,"b"`); quoted field with an embedded newline; CRLF and LF endings; an empty field and a short row (`get` returns `""`); byte `0xE9` decodes to `é`; `column("NOPE")` throws `AcmaFormatException`; a 200 000-row generated input parses with `next()` returning true exactly 200 000 times.
- [ ] **Step 2: Run** `./gradlew :core:acma:test --tests '*CsvReaderTest*'` → FAIL.
- [ ] **Step 3: Implement** `CsvReader`.
- [ ] **Step 4: Run** the same → PASS.
- [ ] **Step 5: Commit** `feat(acma): streaming ISO-8859-1 CSV reader`.

### Task 4: Python reference implementation and fixture

**Files:**
- Create: `tools/acma/reference.py` (applies the Import rules to an RRL zip and writes `expected.json` = the ImportReport fields)
- Create: `tools/acma/make_fixture.py` (trims a real RRL zip to a site list and injects synthetic bad rows)
- Create: `core/acma/src/test/resources/acma/fixture.zip`, `core/acma/src/test/resources/acma/expected.json`
- Create: `tools/acma/README.md`

**Interfaces — Produces:** `fixture.zip` (same entry names and headers as the real export) and `expected.json`:
`{"sites":…, "sectors":…, "carriers":…, "antennas":…, "skippedUnknownBand":…, "skippedNoPower":…, "skippedBadSite":…, "carriersByNetwork":{…}}`

- [ ] **Step 1: `make_fixture.py <rrl.zip> <out.zip>`** keeps every `device_details` row whose `SITE_ID` ∈ {`101160`, `41428`, `9002575`, `9019760`}, plus the `site`, `licence`, `client` and `antenna` rows those reference, and the `antenna_pattern` rows for those antennas. It then appends these synthetic rows:
  - site `999999001` with blank `LATITUDE`, and one kept-licence device on it;
  - site `999999002` at lat `51.5`, lon `-0.1`, and one device on it;
  - one device copied from the first kept device with `SDD_ID=999999101`, blank `EIRP`, `TRANSMITTER_POWER=40`, unit `W`;
  - one with `SDD_ID=999999102`, blank `EIRP` and blank `TRANSMITTER_POWER`;
  - one with `SDD_ID=999999103`, `FREQUENCY=450000000`.

  It writes only the 6 CSVs the importer reads. Run it with `python3 -I`.
- [ ] **Step 2: `reference.py <zip> <expected.json>`** implements the Import rules independently (stdlib `csv` + `zipfile` only).
- [ ] **Step 3: Generate the files** from the real export (downloaded to the scratchpad, not committed):

Run: `python3 -I tools/acma/make_fixture.py $SCRATCH/spectra_rrl.zip core/acma/src/test/resources/acma/fixture.zip && python3 -I tools/acma/reference.py core/acma/src/test/resources/acma/fixture.zip core/acma/src/test/resources/acma/expected.json && cat core/acma/src/test/resources/acma/expected.json`
Expected: 4 sites, `skippedBadSite == 2`, `skippedNoPower == 1`, `skippedUnknownBand == 1`. On the 2026-10-22 export the 4 real sites gave 1144 carriers and 314 sectors, so carriers should be 1145 (1144 + the derived-power row).

- [ ] **Step 4: Run the reference on the full export** and record its counts in `tools/acma/README.md` (used by Task 12).
- [ ] **Step 5: Commit** `test(acma): reference importer and trimmed real fixture`.

### Task 5: On-device ACMA importer

**Files:**
- Create: `core/acma/src/main/kotlin/au/signalatlas/core/acma/{AcmaImporter.kt,ImportReport.kt,TowerDbSchema.kt,UnitConversions.kt}`
- Test: `core/acma/src/test/kotlin/au/signalatlas/core/acma/AcmaImporterTest.kt`

**Interfaces:**
- Consumes: `CsvReader`, `NetworkMapping`, `BandPlan`, `Network`, `Tech`.
- Produces:
```kotlin
@Serializable data class ImportReport(val sites: Int, val sectors: Int, val carriers: Int, val antennas: Int,
    val skippedUnknownBand: Int, val skippedNoPower: Int, val skippedBadSite: Int, val carriersByNetwork: Map<String, Int>)
class AcmaImporter(private val driver: androidx.sqlite.SQLiteDriver = BundledSQLiteDriver()) {
    /** Builds a complete schema-v1 DB at [out] (overwritten). [cancel] is polled every 10 000 rows. */
    fun import(zip: java.io.File, out: java.io.File, extractStamp: String, zipEtag: String,
               progress: (phase: String, fraction: Float) -> Unit = { _, _ -> }, cancel: () -> Boolean = { false }): ImportReport
}
object TowerDbSchema { const val VERSION = 1; val CREATE: List<String> }
object UnitConversions { fun toDbm(value: Double, unit: String): Double? }
class ImportCancelledException : Exception()
```
Approach: open the zip with `java.util.zip.ZipFile` (random access) and read entries in the order `client` → `licence` → `device_details` → `site` → `antenna`. Build with `PRAGMA journal_mode=OFF; synchronous=OFF`, one transaction per entry, prepared statements reused. Afterwards, delete devices on bad sites, delete empty sectors, fill `site_summary`, then write `meta`.

- [ ] **Step 1: Write the failing tests**
  - `fixtureMatchesReference`: import `fixture.zip`; the `ImportReport` equals `expected.json` field-for-field.
  - `derivedPowerRow`: carrier `999999101` has `eirp_dbm` = 10·log10(40)+30 + that antenna's gain − that device's feeder loss, within 0.01.
  - `omniSector`: rewrite the fixture in-test with one device's `AZIMUTH` blanked → its sector has `azimuth_deg IS NULL`.
  - `metaWritten`: `schema_version='1'`, plus `extract_stamp` and `zip_etag` echo the inputs, and `report` parses back to the same `ImportReport`.
  - `missingColumnFailsClearly`: fixture with `EIRP` removed from the `device_details` header → throws `AcmaFormatException` whose message contains `EIRP`.
  - `cancelStops`: `cancel = { true }` → throws `ImportCancelledException`.
  - `UnitConversions`: `toDbm(1.0,"W")==30.0`, `toDbm(30.0,"dBm")==30.0`, `toDbm(0.0,"dBW")==30.0`, `toDbm(1.0,"kW")==60.0`, `toDbm(1.0,"furlongs")==null`.
- [ ] **Step 2: Run** `./gradlew :core:acma:test` → FAIL.
- [ ] **Step 3: Implement** per the Import rules and the DB schema.
- [ ] **Step 4: Run** `./gradlew :core:acma:test` → PASS.
- [ ] **Step 5: Add `FullExportTest`**, skipped unless the env var `ACMA_FULL_ZIP` is set. It imports the real export, asserts counts equal those in `tools/acma/README.md`, and prints the elapsed time and DB size.
- [ ] **Step 6: Commit** `feat(acma): on-device ACMA importer`.

### Task 6: DB validation and atomic install

**Files:**
- Create: `core/data/src/main/kotlin/au/signalatlas/core/data/{DbValidator.kt,DbInstaller.kt}`
- Test: `core/data/src/test/kotlin/au/signalatlas/core/data/DbInstallerTest.kt`

**Interfaces — Produces:**
```kotlin
sealed interface Validation { data object Ok : Validation; data class Invalid(val reason: String) : Validation }
class DbValidator(private val minSites: Int = 10_000, private val driver: SQLiteDriver = BundledSQLiteDriver()) {
    fun validate(db: File): Validation   // schema_version == 1; site count >= minSites; no sector without a site; no carrier without a sector; all sites within lat [-55,-9], lon [72,169]
}
class DbInstaller(private val dir: File, private val validator: DbValidator) {
    val live: File        // dir/towers.db
    val previous: File    // dir/towers.prev.db
    fun install(candidate: File): Validation   // on Ok: live -> previous (replacing it), candidate -> live via Files.move(ATOMIC_MOVE); on Invalid: candidate deleted, live untouched
    fun rollback(): Boolean                    // previous -> live if previous exists
    fun cleanupStale()                         // deletes dir/*.building and dir/*.tmp left from a crashed import
}
```

- [ ] **Step 1: Write the failing tests** (build DBs with `AcmaImporter` from `fixture.zip`, `minSites = 1`):
  - first install → `Ok`, `live` exists, `previous` does not;
  - second install → `Ok`, and `previous` holds the first DB's `imported_at`;
  - candidate with a truncated file (half its bytes) → `Invalid`, `live` unchanged byte-for-byte, candidate deleted;
  - candidate with an orphan sector inserted → `Invalid("orphan sector …")`;
  - `minSites = 10` with the fixture → `Invalid` whose reason mentions the site count;
  - `cleanupStale()` removes `x.building` and leaves `towers.db`;
  - `rollback()` restores the previous `imported_at`.
- [ ] **Step 2: Run** `./gradlew :core:data:test --tests '*DbInstallerTest*'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(data): validated atomic tower DB install with rollback`.

### Task 7: Tower store, grid index and queries

**Files:**
- Create: `core/data/src/main/kotlin/au/signalatlas/core/data/{TowerStore.kt,GridIndex.kt,DataMeta.kt}`
- Test: `core/data/src/test/kotlin/au/signalatlas/core/data/{TowerStoreTest.kt,GridIndexTest.kt}`

**Interfaces — Produces:**
```kotlin
data class DataMeta(val extractStamp: String, val importedAt: java.time.Instant, val report: ImportReport)
class GridIndex(lats: DoubleArray, lons: DoubleArray, cellDeg: Double = 0.1) {
    fun query(b: GeoBounds, out: IntArray /* reused */): Int   // returns count; out holds row indices
    fun nearest(lat: Double, lon: Double, k: Int, accept: (Int) -> Boolean): IntArray
}
class TowerStore private constructor(/* … */) : java.io.Closeable {
    companion object { fun open(db: File, driver: SQLiteDriver = BundledSQLiteDriver()): TowerStore }   // loads every SiteSummary into memory + builds GridIndex
    val meta: DataMeta
    val allBands: List<String>                                                   // distinct, sorted by frequency
    fun sitesIn(b: GeoBounds, f: TowerFilter): List<SiteSummary>
    fun allSites(f: TowerFilter): List<SiteSummary>
    fun nearest(lat: Double, lon: Double, f: TowerFilter, limit: Int = 50): List<Pair<SiteSummary, Double>>  // metres, haversine
    fun search(query: String, limit: Int = 20): List<SiteSummary>                 // numeric → exact site id first; else case-insensitive name contains; postcode exact
    fun siteDetail(siteId: Int): SiteDetail?                                     // sectors sorted by network then azimuth; carriers sorted by freq
}
```

- [ ] **Step 1: Write the failing tests** against the fixture DB:
  - `sitesIn` over Canberra bounds (−35.4, 149.0, −35.2, 149.3) contains the 3 Canberra fixture sites (101160, 41428, 9002575), and an Optus-only filter returns only those whose summary contains `OPTUS`;
  - `nearest(-35.30, 149.13, …)` is sorted by ascending distance, and the first distance matches haversine within 1 m;
  - `search("41428")` puts site 41428 first; `search("barton")` finds 101160;
  - `siteDetail(101160)` has the sector and carrier counts the reference reports for that site (compute them in-test with SQL against the same DB, and assert they match the object graph);
  - `GridIndex` gives the same results as a brute-force scan over 5 000 random points and 200 random boxes;
  - `GridIndexTest.speed`: 27 000 points, 1 000 viewport queries, average < 1 ms (JVM).
- [ ] **Step 2: Run** `./gradlew :core:data:test` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(data): in-memory tower store with grid index, search and nearest`.

### Task 8: ACMA remote, update check and resumable download

**Files:**
- Create: `core/update/src/main/kotlin/au/signalatlas/core/update/{AcmaRemote.kt,RemoteInfo.kt}`
- Test: `core/update/src/test/kotlin/au/signalatlas/core/update/AcmaRemoteTest.kt`

**Interfaces — Produces:**
```kotlin
data class RemoteInfo(val extractStamp: String, val etag: String, val sizeBytes: Long)
class AcmaRemote(private val http: okhttp3.OkHttpClient, private val zipUrl: String = ZIP_URL, private val stampUrl: String = STAMP_URL) {
    companion object { const val ZIP_URL = "https://cdn.acma.gov.au/rrl/spectra_rrl.zip"; const val STAMP_URL = "https://backend.acma.gov.au/rrl/v1/extracts/datetime-of-extract.txt" }
    suspend fun info(): RemoteInfo                     // GET stamp (trimmed, must match ^\d{12}$), HEAD zip
    /** Resumes into [dest] when dest exists and the server honours Range + If-Range(etag) with 206; otherwise restarts at 0. Verifies the final length equals sizeBytes. */
    suspend fun download(info: RemoteInfo, dest: File, onProgress: (bytes: Long, total: Long) -> Unit)
}
class RemoteFormatException(msg: String) : java.io.IOException(msg)
```

- [ ] **Step 1: Write the failing tests** with `mockwebserver3`:
  - `info()` parses stamp `202610221934\n` and HEAD `ETag`/`Content-Length`;
  - an HTML stamp body → `RemoteFormatException`;
  - fresh download writes all bytes and reports progress ending at total;
  - resume: `dest` pre-filled with the first 1 000 bytes, server replies 206 to `Range: bytes=1000-` with `If-Range` equal to the etag → file equals the source, and exactly one request sent the Range header;
  - **ETag changed**: the server ignores If-Range and returns 200 with the new body → `dest` contains only the new body (never old prefix + new);
  - truncated response (connection dropped) → throws `IOException`, and the partial file is kept for the next resume.
- [ ] **Step 2: Run** `./gradlew :core:update:testDebugUnitTest --tests '*AcmaRemoteTest*'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(update): ACMA update check and resumable download`.

### Task 9: Update orchestration and scheduling

**Files:**
- Create: `core/update/src/main/kotlin/au/signalatlas/core/update/{UpdatePrefs.kt,TowerRepository.kt,UpdateManager.kt,TowerUpdateWorker.kt,UpdateNotifications.kt}`
- Test: `core/update/src/test/kotlin/au/signalatlas/core/update/{UpdateManagerTest.kt,UpdatePolicyTest.kt}`

**Interfaces:**
- Consumes: `AcmaRemote`, `AcmaImporter`, `DbInstaller`, `TowerStore`.
- Produces:
```kotlin
class TowerRepository(dir: File, installer: DbInstaller) {           // single owner of the open TowerStore
    val store: kotlinx.coroutines.flow.StateFlow<TowerStore?>
    suspend fun reload()                                               // closes the old store after the new one opens
}
sealed interface UpdateStatus {
    data object Unknown : UpdateStatus; data object Checking : UpdateStatus
    data class UpToDate(val stamp: String) : UpdateStatus
    data class Available(val info: RemoteInfo) : UpdateStatus
    data class Running(val phase: String, val fraction: Float) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
    data object Offline : UpdateStatus
}
object UpdatePolicy {
    fun periodicConstraints(): androidx.work.Constraints                        // UNMETERED + requiresCharging + storageNotLow
    fun staleConstraints(): androidx.work.Constraints                           // UNMETERED + storageNotLow
    fun isStale(importedAt: java.time.Instant?, now: java.time.Instant): Boolean // null or > 14 days
}
class UpdateManager(context: Context, remote: AcmaRemote, repo: TowerRepository, prefs: UpdatePrefs) {
    val status: StateFlow<UpdateStatus>
    suspend fun checkOnAppOpen()            // info() vs stored stamp, at most once per 30 min; never throws (Offline on IOException)
    fun schedulePeriodic()                  // unique "acma-periodic", 7 days, KEEP; also enqueues unique "acma-stale" if isStale
    fun updateNow(allowMetered: Boolean)    // unique "acma-manual", REPLACE, expedited, NetworkType.CONNECTED if allowMetered else UNMETERED
}
class TowerUpdateWorker : CoroutineWorker   // info → download to dir/spectra_rrl.zip.tmp → import to dir/towers.db.building → install → repo.reload() → delete zip; foreground notification with progress; Result.retry() on IOException, failure() on Invalid/format errors
```

- [ ] **Step 1: Write the failing tests** (Robolectric + `work-testing` + mockwebserver3 serving `fixture.zip`, with `DbValidator(minSites = 1)`):
  - `checkOnAppOpen` with stored stamp == remote → `UpToDate`; with an older stamp → `Available`; server down → `Offline`, no exception;
  - a second `checkOnAppOpen` within 30 min makes no network request;
  - running `TowerUpdateWorker` via `TestListenableWorkerBuilder` → `Result.success()`, `repo.store.value!!.meta.extractStamp` equals the served stamp, and the temp zip is deleted;
  - worker with a corrupt zip → `Result.failure()`, the previously installed store is still open and returns the same `allSites` count;
  - `UpdatePolicy`: periodic requires charging and UNMETERED; stale requires UNMETERED and not charging; `isStale(null, now)` is true; 13 days → false; 15 days → true.
- [ ] **Step 2: Run** `./gradlew :core:update:testDebugUnitTest` → FAIL.
- [ ] **Step 3: Implement.** The notification channel is `tower_updates` (low importance), with title "Updating tower data" and the phase as text.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(update): update manager, worker and scheduling policy`.

### Task 10: Map module (clean style, base map, tower layer)

**Files:**
- Create: `core/map/src/main/assets/styles/{clean_light.json,clean_dark.json}`, `core/map/src/main/assets/glyphs/Noto Sans Regular/{0-255,256-511,8192-8447}.pbf` (+ the same for Medium), `core/map/src/main/assets/glyphs/OFL.txt`
- Create: `core/map/src/main/kotlin/au/signalatlas/core/map/{BasemapLocator.kt,StyleLoader.kt,TowerMap.kt,TowerLayer.kt,NetworkColors.kt,HighRefreshRate.kt}`
- Test: `core/map/src/test/kotlin/au/signalatlas/core/map/{StyleAssetsTest.kt,BasemapLocatorTest.kt,TowerLayerTest.kt}`

**Interfaces — Produces:**
```kotlin
class BasemapLocator(http: OkHttpClient, prefs: DataStore<Preferences>) {
    suspend fun currentKey(): String?    // cached key; refreshes from builds.json at most every 7 days; null only if never fetched and offline
}
object StyleLoader { fun load(context: Context, dark: Boolean, basemapKey: String?): String }  // replaces "{{PMTILES_URL}}"; when the key is null, strips the protomaps source and its layers (background + water fallback colour)
object TowerLayer { fun featureCollection(sites: List<SiteSummary>): String }  // GeoJSON; properties: id, net (primary network name), n (network count), nr (bool)
object NetworkColors { fun argb(n: Network): Int }   // TELSTRA 0xFF1E88E5, OPTUS 0xFF00A39A, TPG 0xFFE53935, OPTUS_TPG_JV 0xFF8E6CD8, NBN 0xFFFF8F00
@Composable fun TowerMap(sites: List<SiteSummary>, selectedSiteId: Int?, dark: Boolean, basemapKey: String?, camera: MapCameraState,
                         onSiteClick: (Int) -> Unit, onCameraIdle: (GeoBounds) -> Unit, modifier: Modifier = Modifier)
class MapCameraState(initialLat: Double = -27.0, initialLon: Double = 134.0, initialZoom: Double = 3.6) { fun animateTo(lat: Double, lon: Double, zoom: Double) }
object HighRefreshRate { fun apply(activity: Activity) }  // picks the display mode with the max refresh rate at the current resolution; sets preferredDisplayModeId
```
Style rules (clean / iOS-like, Protomaps v4 layers):
- **Light:** background `#F6F5F2`, earth `#F6F5F2`, water `#B9D8F2`, park/wood `#DCEFD6`, buildings `#ECEAE6` (z≥15), minor roads `#FFFFFF` with casing `#E3E1DC`, major roads `#FFFFFF` w/ casing `#D9D6CF`, highways `#FBE3A6` w/ casing `#EBC97A`, boundaries `#C9C6BF` dashed, labels `#5F6368` with halo `#FFFFFF` and font `Noto Sans Regular`/`Medium`, and POIs only at z≥16.
- **Dark:** background `#1C1D1F`, water `#20354A`, parks `#22302A`, roads `#3A3C40`, highways `#5A4E33`, labels `#B5B8BD` with halo `#1C1D1F`.
- glyphs `asset://glyphs/{fontstack}/{range}.pbf`; no remote sprite (tower dots are circle layers).
- Tower layer: GeoJSON source `sites` with `cluster=true`, `clusterRadius=45`, `clusterMaxZoom=11`. Cluster circle layer sized by `point_count`; unclustered `circle` coloured by `net` with radius 5→9 over z 8→16 and a white 1.5 px stroke; selected site: radius +4 and a stroke in the Material primary colour.
- `TowerMap` uses `MapView` with `textureMode=false` (SurfaceView). Re-set the GeoJSON on a background-built string only when `sites` changes by identity.

- [ ] **Step 1: Write the failing tests**
  - `StyleAssetsTest`: both style JSONs parse; with key `20261022.pmtiles` the source URL is `pmtiles://https://build.protomaps.com/20261022.pmtiles`; with a null key there is no source of type `vector` and every remaining layer is `background`; no URL in the style starts with `http` except the pmtiles source; every `text-font` used has its glyph folder in assets.
  - `BasemapLocatorTest` (mockwebserver3): picks the last key of a 3-entry builds.json; cached within 7 days → no request; offline with a cache → cached key; offline without one → null.
  - `TowerLayerTest`: 2 sites → valid GeoJSON with 2 Point features at [lon, lat]; a site with {OPTUS, TPG} has `n == 2`; `nr` is true iff the techs contain NR.
- [ ] **Step 2: Run** `./gradlew :core:map:testDebugUnitTest` → FAIL.
- [ ] **Step 3: Implement.** Download the glyph PBFs from `github.com/protomaps/basemaps-assets` (`fonts/Noto Sans Regular|Medium`) and include `OFL.txt`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(map): clean light/dark styles, basemap locator and tower layer`.

### Task 11: App UI — map screen, sheet, filters, search, nearby, updates

**Files:**
- Create: `app/src/main/kotlin/au/signalatlas/app/{SignalAtlasApp.kt,AppContainer.kt,MainActivity.kt}`
- Create: `app/src/main/kotlin/au/signalatlas/app/ui/theme/{Theme.kt,Color.kt}`
- Create: `app/src/main/kotlin/au/signalatlas/app/ui/map/{MapViewModel.kt,MapUiState.kt,MapScreen.kt,FilterSheet.kt,SiteSheet.kt,SearchBar.kt,UpdateChip.kt}`
- Create: `app/src/main/kotlin/au/signalatlas/app/ui/{firstrun/FirstRunScreen.kt,nearby/NearbyScreen.kt,updates/UpdatesScreen.kt,AppScaffold.kt}`
- Test: `app/src/test/kotlin/au/signalatlas/app/ui/map/MapViewModelTest.kt`, `app/src/test/kotlin/au/signalatlas/app/ui/firstrun/FirstRunScreenTest.kt`

**Interfaces:**
- Consumes: `TowerRepository.store`, `UpdateManager`, `BasemapLocator`, `TowerMap`, `TowerFilter`.
- Produces:
```kotlin
data class MapUiState(
    val hasData: Boolean, val sites: List<SiteSummary>, val filter: TowerFilter, val availableBands: List<String>,
    val selected: SiteDetail?, val searchResults: List<SiteSummary>, val update: UpdateStatus,
    val basemapKey: String?, val locationGranted: Boolean, val nearby: List<Pair<SiteSummary, Double>>)
class MapViewModel(repo: TowerRepository, updates: UpdateManager, basemap: BasemapLocator, location: LocationSource) : ViewModel {
    val state: StateFlow<MapUiState>
    fun setFilter(f: TowerFilter); fun selectSite(id: Int?); fun search(q: String); fun onLocation(lat: Double, lon: Double)
    fun onLocationPermission(granted: Boolean); fun checkUpdates(); fun updateNow(allowMetered: Boolean)
}
interface LocationSource { val last: StateFlow<Pair<Double, Double>?> }   // FusedLocation-free: LocationManager passive + network provider, to avoid Play Services
```
Layout: the map fills the screen edge-to-edge. On top sits a Material 3 `SearchBar` (results: sites); below it a horizontally scrolling chip row (one `FilterChip` per network, then `4G`, `5G`, then a "Bands" chip that opens `FilterSheet`). The `UpdateChip` sits under the chips when `Available`/`Running`/`Failed`. Bottom-end FABs: "My location" and "Nearby". `SiteSheet` is a `ModalBottomSheet` on compact width. On expanded width (`currentWindowAdaptiveInfo().windowSizeClass` ≥ EXPANDED), use a `ListDetailPaneScaffold` where the right pane shows `SiteSheet` content / `NearbyScreen`. The site sheet groups sectors by network → azimuth, with each carrier on one line `"n78 · 3.41 GHz · 100 MHz · 61.8 dBm EIRP"` plus height, antenna model and licence no. Navigation: map (start) → updates (from the overflow menu and the update chip). The first-run gate replaces the map while `hasData == false`.
First run: title "Download tower data", body "Signal Atlas builds its tower database from the ACMA register on your phone (about 68 MB). After that, it works offline.", button "Download now" (on a metered network it confirms "Use mobile data for about 68 MB?"), with progress from `UpdateStatus.Running`. On `Failed`/`Offline` it shows the message plus "Try again".
Updates screen: data date (from `extract_stamp`, formatted `d MMM yyyy, h:mm a` in Australia/Sydney), site/sector/carrier counts, a "Check for updates" button, "Update now" (allowed on mobile data with confirmation), and the status line.

- [ ] **Step 1: Write the failing tests**
  - `MapViewModelTest` (fakes for repo, updates, basemap, location):
    - no store → `hasData == false`; store present → `sites.size` equals the store's `allSites(default)`;
    - `setFilter(networks={OPTUS})` → every site's networks contain OPTUS;
    - `selectSite(101160)` → `selected?.site?.id == 101160`; `selectSite(null)` clears it;
    - `search("barton")` → the results contain 101160;
    - **permission denied** → `locationGranted == false`, `nearby` empty, no exception;
    - `onLocation(-35.3, 149.13)` with permission → `nearby` is sorted ascending.
  - `FirstRunScreenTest` (Robolectric Compose): with `UpdateStatus.Offline` the "Try again" button is shown and clicking it calls `updateNow`; with `Running(…, 0.4f)` a progress indicator is shown and the button is hidden.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest` → FAIL.
- [ ] **Step 3: Implement** the theme (`dynamicLightColorScheme`/`dynamicDarkColorScheme` on API 31+, otherwise a seed of `#1E88E5`), `AppContainer` (manual DI that builds OkHttp, AcmaRemote, DbInstaller(`filesDir/towers`), TowerRepository, UpdateManager, BasemapLocator), and the screens. `MainActivity`: `enableEdgeToEdge()`, `HighRefreshRate.apply(this)`, and `updateManager.checkOnAppOpen()` + `schedulePeriodic()` in `onCreate` (via `lifecycleScope`). The manifest declares `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, and handles `configChanges` `screenSize|smallestScreenSize|screenLayout|orientation|density` so fold/unfold does not recreate the map.
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest` → PASS.
- [ ] **Step 5: Commit** `feat(app): map screen, filters, search, site sheet, nearby list, updates`.

### Task 12: End-to-end verification on the emulator with real ACMA data

**Files:**
- Create: `docs/verification/m1.md`

- [ ] **Step 1: Run the full JVM suite**: `./gradlew test testDebugUnitTest` → `BUILD SUCCESSFUL`.
- [ ] **Step 2: Run the full-export importer test**: `ACMA_FULL_ZIP=$SCRATCH/spectra_rrl.zip ./gradlew :core:acma:test --tests '*FullExportTest*'` → PASS; record the elapsed time and DB size.
- [ ] **Step 3: Install and launch** on `sa_pixel`: `./gradlew :app:installDebug && adb shell am start -n au.signalatlas.app/.MainActivity`. Tap "Download now", wait for the import, and record the on-device import time (logcat tag `AcmaImporter`) and the DB size (`adb shell run-as au.signalatlas.app ls -l files/towers`).
- [ ] **Step 4: Check by screenshot** (`adb exec-out screencap -p`): the Australia view shows clusters; zooming into Canberra shows coloured tower dots; tapping site 101160 opens the sheet with Telstra, Optus and TPG sectors; the Optus filter hides Telstra-only sites; dark mode renders the dark style; turning on airplane mode and relaunching still shows towers.
- [ ] **Step 5: Check frame pacing:** `adb shell dumpsys gfxinfo au.signalatlas.app reset`, pan for 10 s, then `dumpsys gfxinfo` → record the 90th-percentile frame time (target < 8.3 ms on 120 Hz-capable hardware; the emulator figure is indicative only).
- [ ] **Step 6: Write `docs/verification/m1.md`** with the numbers and screenshots, and commit `docs: M1 verification results`.
