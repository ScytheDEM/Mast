# Signal Atlas — Milestone 2: Coverage Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Predicted coverage on the map. A native C++ engine computes signal strength (RSRP) for the selected
network from every nearby sector. It uses the real per-transmitter ACMA horizontal patterns (imported monthly on the
phone), an estimated vertical pattern, and the spec's quick path-loss models (no terrain yet: that is M3). MapLibre
colours the result on the GPU, so threshold and colour changes are instant.

**Architecture:**
- **Data:** `:core:data` owns the open tower DB (`TowerRepository` moves there, with reference-counted store lifetime) and
  a separate `patterns.db` for horizontal radiation patterns (HRP).
- **Engine:** a new `:engine` module (C++20, NDK + JNI, with host-built GoogleTest) renders one 256×256 Web-Mercator tile
  of RSRP from flat arrays of carriers.
- **Coverage:** `:core:coverage` (Kotlin) selects and culls carriers per tile, schedules renders by viewport priority,
  caches tiles, and serves them as Terrarium `raster-dem` PNGs through an OkHttp interceptor installed in MapLibre.
- **Map:** a `color-relief` layer maps RSRP (dBm) to the legend colours on the GPU.

**Tech Stack:** As in M1, plus Android NDK `30.0.16248370`, SDK CMake `4.1.2`, C++20, GoogleTest (CMake FetchContent,
tag `v1.17.0`, host build with Apple clang), and `org.maplibre.gl:android-sdk-vulkan` 13.7.1 `color-relief` and
`raster-dem` layers.

**Spec:** `docs/superpowers/specs/2026-10-22-signal-atlas-design.md` (§4, §4a, §5; coverage layer updated 2026-10-23)
**Follow-ups carried from M1:** `docs/m1-followups.md`

## Verified facts (2026-10-23)

- **HRP file.** `https://cdn.acma.gov.au/rrl/spectra_licence_hrp.zip` is 1,562,523,638 bytes (ETag `0x8DF2EDC716D83D0`,
  Last-Modified 2026-10-20). It contains one entry, `LICENCE_.csv`, of 10,060,668,828 bytes, with header
  `SDD_ID,DEVICE_REGISTRATION_IDENTIFIER,START_ANGLE,STOP_ANGLE,POWER`.
  - Each device's rows are contiguous per `SDD_ID` and describe angle intervals (see HRP rule 2; corrected 2026-10-23 after a full-file analysis).
  - `POWER` is the EIRP in dBW in that direction. For example, SDD `2909149471` (EIRP 1521 W, azimuth 270) peaks at
    265° with 33.6 and has a floor of exactly peak − 25.0.
- **MapLibre.**
  - Native supports the `color-relief` layer (maplibre-native #3965) and Terrarium `raster-dem`.
  - The Android SDK routes all tile HTTP through `org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(Call.Factory)`.
- **Toolchain.** Host compiler: Apple clang 17. SDK packages `ndk;30.0.16248370` and `cmake;4.1.2` are available.

## Global Constraints

- **M1's constraints still apply** (package names, minSdk 29 / target 37, no accounts or keys, no heavy main-thread work,
  update policy, commit trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`).
- **Native code:**
  - ABIs `arm64-v8a`, `x86_64`.
  - 16 KB page-size compatible: link with `-Wl,-z,max-page-size=16384`, and the APK must pass `zipalign -c -P 16 -v 4`.
  - `-O3`; NEON is allowed on arm64 behind a scalar fallback.
  - No exceptions across JNI.
- **Engine purity.** The engine never reads files or the network. It only computes from the arrays it is given.
- **HRP import:** WorkManager job, unmetered + charging, at most every 30 days, and only when the ETag has changed.
  It needs ≥ 3 GB free before it starts (otherwise it retries later). It installs `patterns.db` atomically. Until
  patterns exist, everything works with 3GPP parametric patterns.
- **Coverage visible zooms:** 8–16. Below 8 a hint chip shows "Zoom in to see coverage", and tiles above 16 are
  overzoomed.
- **Thermal:** when `PowerManager.getThermalHeadroom(10)` ≥ 0.85, at most 1 render thread; otherwise
  `min(4, availableProcessors − 2)` (at least 1).

### Coverage model (shared by the Python reference `tools/engine/reference.py` and the C++ engine; change both together)

1. **Inputs per carrier:**
   - site lat/lon, height above ground `h_tx` (m; clamped to ≥ 3)
   - azimuth (deg, or null = omni)
   - ACMA tilt (deg)
   - EIRP (dBm)
   - frequency (MHz) and bandwidth (Hz)
   - tech (LTE/NR), band label, network
   - H beamwidth and F/B from the antenna (nullable), gain (dBi, nullable)
   - optional HRP (pattern id + peak bearing)
2. **EIRP per resource element:** `eirpRe = eirp − 10·log10(12·N_RB)`.
   - **LTE N_RB by bandwidth (MHz):** 1.4→6, 3→15, 5→25, 10→50, 15→75, 20→100.
   - **NR N_RB:**
     - SCS 15 kHz for f < 2.3 GHz: 5→25, 10→52, 15→79, 20→106, 25→133, 30→160, 40→216, 50→270.
     - SCS 30 kHz for 2.3–7.125 GHz: 10→24, 15→38, 20→51, 25→65, 30→78, 40→106, 50→133, 60→162, 70→189, 80→217, 90→245, 100→273.
     - SCS 120 kHz for ≥ 24.25 GHz: 50→32, 100→66, 200→132, 400→264.
   - **Bandwidth not in the table:** `N_RB = floor(BW·0.9 / (12·SCS))`, minimum 6.
3. **Horizontal attenuation `A_H` (dB ≥ 0):**
   - With HRP: `att[(round(bearing) − peakBearing) mod 360]` from the stored relative pattern (0.25 dB steps).
   - Else, omni: 0.
   - Else: `min(12·(Δφ/φ3dB)², FB)`, where Δφ is the wrapped difference to the azimuth (−180..180), `φ3dB = H_BW` (default
     65°), and `FB = FRONT_TO_BACK` (default 25).
4. **Vertical attenuation `A_V`:**
   - **Beamwidth:** `θ3dB = clamp(32400 / (10^(G/10) · φ3dB), 4, 30)`. `G` defaults to 15 dBi and `φ3dB` to 65; for
     omni use `φ3dB = 360`.
   - **Tilt (downtilt, positive down):** `min(|ACMA tilt|, 15)` if ACMA tilt ≠ 0 (ACMA uses both signs for downtilt: 39.6k negative vs 21.9k positive in the 2026-10-22 export). Otherwise by `h_tx`: < 15 m → 6°, 15–30 → 4°, 30–50 → 3°, > 50 → 2°. Add 2° for f ≥ 24.25 GHz.
   - **Formula:** `θ = atan2(h_tx − 1.5, d)` in degrees (positive downwards) and `A_V = min(12·((θ − tilt)/θ3dB)², 30)`.
   - **Combined:** `A = min(A_H + A_V, max(FB, 30))`.
5. **Area class** per carrier (amended 2026-10-24 after the tile-seam bug): from the count of sites within 3 km of the
   carrier's *site* (any network, the site itself included): ≥ 40 → DENSE_URBAN, ≥ 12 → URBAN, ≥ 3 → SUBURBAN, else
   RURAL. Computed once per carrier when its region is prepared (`CarrierArrays.area`, Kotlin `AreaClassifier.perSite`)
   and used on every tile and point query, so a carrier keeps one path-loss model everywhere. The renderer's per-call
   `RenderParams.area` is only the fallback for a carrier without its own class. (Before the amendment the class was
   taken per tile, from the tile centre: neighbouring tiles across a density step switched every carrier's model, which
   showed as 10–25 dB seams at tile borders, e.g. −85 vs −110 dBm on B5.)
5a. **Receiver environment** (amended 2026-10-24 M3b): where building data exists, each point gets an environment class
   from the building grid (0.01° cells: footprint coverage fraction and area-weighted mean height, unknown height 6 m),
   with fraction and height interpolated bilinearly between the centres of the four surrounding cells (any of them without
   data = no data). The classes are blended, not switched (amended 2026-10-25: a hard switch made 9–13 dB rings):
   with piecewise-linear ramps `r(x, lo, hi)`, `s1 = r(f, 0, 0.1)`, `s2 = r(f, 0.15, 0.25)`,
   `s3 = min(s2, r(f, 0.30, 0.40)·r(h, 12, 18))`, the weights are DENSE_URBAN `s3`, URBAN `s2 − s3`, SUBURBAN `s1 − s2`,
   RURAL `1 − s1` (i.e. ±0.05 ramps around the thresholds 0.05 / 0.2 / 0.35 and 12–18 m around 15 m). The weighted sum
   of the classes' path losses replaces the carrier's class (rule 5) only in the terms that describe the receiver's
   surroundings, i.e. the Hata / COST-231 `a(h_m)`, the SUBURBAN / RURAL corrections and `C_m` (f ≤ 2200 MHz). The TR 38.901 scenario stays the
   carrier's class, the O2I loss and σ are unchanged, and without building data the carrier's class applies as before.
   Tile grids blend each sample; the branch-and-bound bound uses the lowest path loss over the classes with weight in
   the block (the blend is a convex combination, so it stays an upper bound); R_max is already the maximum over all classes. Tile keys carry the buildings
   data version.
6. **Path loss `PL(d, f, h_tx, class)`.** `d` is the 3D distance in km, floored at 0.02. `h_rx = 1.5` m.
   - **f < 1500 MHz, Okumura-Hata:** `69.55 + 26.16·log f − 13.82·log h_b − a(h_m) + (44.9 − 6.55·log h_b)·log d`.
     - `h_b = clamp(h_tx, 3, 200)`.
     - `a(h_m)`: DENSE_URBAN uses `3.2·(log(11.75·1.5))² − 4.97`; otherwise `(1.1·log f − 0.7)·1.5 − (1.56·log f − 0.8)`.
     - Corrections: SUBURBAN `−2·(log(f/28))² − 5.4`; RURAL `−4.78·(log f)² + 18.33·log f − 35.94`.
       (This is the quasi-open value: open-area −40.94, plus 5.)
   - **1500 ≤ f ≤ 2200 MHz, COST-231 Hata:** `46.3 + 33.9·log f − 13.82·log h_b − a(h_m) + (44.9 − 6.55·log h_b)·log d + C_m`.
     - `C_m` = 3 for DENSE_URBAN, else 0.
     - Same `a(h_m)` and the same SUBURBAN/RURAL corrections.
   - **f > 2200 MHz, 3GPP TR 38.901 Table 7.4.1-1** (d in metres, f_c in GHz, h_BS = `h_tx` and h_UT = 1.5):
     - DENSE_URBAN and URBAN → UMa; SUBURBAN and RURAL → RMa (h = 5 m, W = 20 m); f ≥ 24.25 GHz → UMi-Street Canyon.
     - **LOS probability (Table 7.4.2-1):** UMa uses the h_UT ≤ 13 m form; RMa uses `1` for d ≤ 10 m, else `exp(−(d−10)/1000)`;
       UMi uses `1` for d ≤ 18 m, else `18/d + exp(−d/36)·(1 − 18/d)`.
     - **Blend (dB domain, standard planning practice):** `PL = P·PL_LOS + (1−P)·PL_NLOS`, with PL_NLOS = max(PL_LOS, PL'_NLOS) as in the table. A power-domain blend lets a small LOS probability dominate at long range, which over-predicts coverage by about 11 dB at 3 km.
     - **Breakpoint:** `d_BP` uses the table's definition for each scenario.
   - **Shadowing σ (dB):** Hata family 8; UMa 6 (NLOS) / 4 (LOS); RMa 8 / 4; UMi 7.82 / 4. Blended σ =
     `P·σ_LOS + (1−P)·σ_NLOS`.
7. **Indoor (optional):** add the 3GPP O2I low-loss model:
   `5 − 10·log10(0.3·10^(−(2+0.2f)/10) + 0.7·10^(−(5+4f)/10)) + 2.5` (f in GHz). Combine σ in quadrature: `σ = √(σ² + 4.4²)` (TR 38.901 §7.4.3: independent Gaussian O2I variation).
8. **RSRP** = `eirpRe − A − PL`. Best-server RSRP = max over the selected carriers at the pixel.
   - **SINR:** `S / (Σ_{same freq, other sectors} 0.5·10^(RSRP_i/10) + N)`, where `N = −174 + 10·log10(SCS_Hz) + 7` dBm.
   - **Coverage probability (amended 2026-11-02, "reliability"):**
     `P = Q((thr − RSRP)/σ_t) · Q((−3 − SINR)/3)`, with `Q(x) = 0.5·erfc(x/√2)` and the map's `thr = −110` dBm (usable
     service, was −118).
     - `σ_t = √(σ² + m²)`: the shadowing σ plus model error `m` = 6 dB where the best server's terrain loss came from a
       complete table, 9 dB where it did not, plus `min(4, 0.2·max(0, d_km − 10))`.
     - Calibration rule 6 scales `σ_t`.
     - Why: the old P treated the prediction as exact and the threshold as usable, so −100 dBm read 99 %.
9. **Tile grid:**
   - Grid size: 128×128 sample points at z ≤ 13, 256×256 at z ≥ 14. Pixel centres follow Web-Mercator; the 128 grid is
     upsampled bilinearly to 256. The outermost pixel rows and columns lie a quarter sample outside the outermost
     samples and are extrapolated linearly from the last two samples (amended 2026-10-24 after the seam bug; clamping
     them left a step at every tile border), except where one of the four samples has no coverage (then clamped).
   - **RSRP code:** `uint8 = clamp(round((RSRP + 140)·2), 1, 254)`, with 0 = below −140 / no carrier.
   - **SINR code:** `uint8 = clamp(round((SINR + 20)·4), 0, 255)`.
   - **Probability code:** `uint8 = round(P·255)`.
   - **Culling:** a carrier is evaluated for a tile only if its `R_max` (the distance where peak-direction RSRP falls to
     −140 dBm, computed once per carrier as the maximum over all four area classes, so culling never drops a carrier whatever
     class it has; outdoor, capped at 80 km) reaches the tile's bounding box.
10. **Terrarium encoding** (map display): `elev = RSRP_dBm`, with no coverage → −200. Probability view: `elev = P·100`.
    The bytes are `R = floor((elev+32768)/256)`, `G = floor(elev+32768) mod 256`, `B = floor(frac(elev+32768)·256)`.
11. **Legend.** `color-relief` stops at alpha 0.45. Probability view: 0 → transparent, 50 → `#F4A261`, 90 → `#2A9D8F`,
    100 → `#0B5FA5`.

| Band | RSRP | Colour |
|---|---|---|
| Excellent | ≥ −85 | `#0B5FA5` |
| Good | −85 … −100 | `#2A9D8F` |
| Fair | −100 … −110 | `#E9C46A` |
| Weak | −110 … −118 | `#F4A261` |
| None | < −118 | transparent |

### HRP import rules (shared by `tools/acma/hrp_reference.py` and the Kotlin importer)

1. **Rows kept:** stream `LICENCE_.csv`, keeping rows whose `SDD_ID` is a carrier in the live `towers.db`.
2. **Rows are intervals (verified on the full file 2026-10-23).** Each row is a half-open interval `[START_ANGLE, STOP_ANGLE)` in degrees,
   which may be wider than 1° (equal adjacent degrees are merged), may use a half-degree grid (`359.5–0.5`, `0.5–1.5`, …), may wrap
   past 360, and may appear in any order. Each SDD's rows are contiguous in the file (one buffered device at a time is enough).
   - **Rasterise** to 360 bins: bin k (k = 0..359) takes the POWER of the row whose interval contains angle k (mod 360).
   - **Reject the device** (`skippedIncomplete`) when two rows containing the same bin have different POWER, when any POWER is
     > 200 or not finite (sentinels such as 2400 occur), when a row is malformed, or when more than 3 bins are empty.
   - **Fill ≤ 3 empty bins** by circular linear interpolation between the nearest filled bins on either side (this covers the
     51,980 integer-grid devices that omit `[0,1)`).
   - `POWER` is used only as a relative pattern; `peak_dbw` is stored for diagnostics and is not a reliable absolute EIRP.
3. **Normalising a device:**
   - `peak = max(POWER)`.
   - `peakBearing` = the angle of the first maximum.
   - `rel[k] = peak − POWER[(peakBearing + k) mod 360]` for k = 0..359.
   - Quantise as `q[k] = clamp(round(rel[k]·4), 0, 255)`, 0.25 dB steps.
4. **De-duplication:** identical `q` arrays share one pattern ID, assigned 1..N in first-seen order.
5. **`patterns.db`** (schema v1):
   - `pattern(id INTEGER PRIMARY KEY, att BLOB NOT NULL /*360 bytes*/)`
   - `device_pattern(sdd_id INTEGER PRIMARY KEY, pattern_id INTEGER NOT NULL, peak_bearing INTEGER NOT NULL, peak_dbw REAL NOT NULL)`
   - `meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)`, keys `schema_version=1`, `hrp_etag`, `tower_stamp`, `imported_at`, `report`.
6. **`HrpReport`:** `devices`, `patterns`, `skippedIncomplete`, `skippedUnknown` (an SDD not in towers.db), `rows`.

## Review Focus

1. **HRP job interrupted, out of storage or killed** → towers and coverage keep working on 3GPP patterns, and no partial
   `patterns.db` is ever opened. *Pinned in Task 4.*
2. **Odd carrier data** (null antenna, omni, H_BW 0, EIRP extreme, height 0, NaN coordinates) → the engine returns finite
   codes and never crashes the process (no native abort). *Pinned in Task 5.*
3. **Fast panning and zooming** → off-screen renders are cancelled, CPU work stops within one tile, and memory stays
   bounded (memory LRU ≤ 96 tiles). *Pinned in Task 8.*
4. **Tower or pattern data updated mid-session** → coverage switches to new tiles (the cache key includes both data
   versions) and never mixes versions inside one view. *Pinned in Task 8.*
5. **Areas with no towers (outback) or the ocean** → fully transparent tiles return quickly, and no error state appears.
   *Pinned in Task 6.*

---

### Task 1: Tower data lifetime refactor (M1 follow-up I6 + schema check)

**Files:**
- Move: `core/update/src/main/kotlin/au/signalatlas/core/update/TowerRepository.kt` → `core/data/src/main/kotlin/au/signalatlas/core/data/TowerRepository.kt` (and its test)
- Modify: `core/data/.../TowerStore.kt`, the call sites in `core/update` and `app`
- Test: `core/data/src/test/kotlin/au/signalatlas/core/data/{TowerRepositoryTest.kt,TowerStoreTest.kt}`

**Interfaces — Produces:**
```kotlin
class TowerRepository(installer: DbInstaller, scope: CoroutineScope, opener: (File) -> TowerStore = { TowerStore.open(it) }) {
    val store: StateFlow<TowerStore?>                  // unchanged semantics for UI
    suspend fun start(); suspend fun reload(); suspend fun refresh(); fun close()
    /** Pins the current store for the duration of [block]; a retired store closes when its last user returns. */
    suspend fun <T> withStore(block: suspend (TowerStore) -> T): T?    // null if no DB
}
class SchemaMismatchException(found: Int?, expected: Int) : java.io.IOException
// TowerStore additions
fun TowerStore.carriersIn(b: GeoBounds, networks: Set<Network>, techs: Set<Tech>, bands: Set<String>?): CarrierBatch
class CarrierBatch(val size: Int, val siteLat: DoubleArray, val siteLon: DoubleArray, val heightM: FloatArray, val azimuthDeg: FloatArray /*NaN = omni*/,
    val tiltDeg: FloatArray, val eirpDbm: FloatArray, val freqMhz: FloatArray, val bandwidthHz: IntArray, val tech: ByteArray, val network: ByteArray,
    val hBeamwidthDeg: FloatArray /*NaN = unknown*/, val frontToBackDb: FloatArray /*NaN*/, val gainDbi: FloatArray /*NaN*/, val sectorId: IntArray, val sddId: LongArray)
fun TowerStore.siteCountWithin(lat: Double, lon: Double, radiusM: Double): Int   // grid-index based
```
The time-based grace close is replaced by reference counting. `TowerStore.open` throws `SchemaMismatchException` when
`meta.schema_version != 1`, and `TowerRepository.start()` treats that as "no DB".

- [ ] **Step 1: Write the failing tests:**
  - `withStore` keeps a retired store open until the block returns, then closes it.
  - Two concurrent `withStore` users plus a reload → each sees one consistent store, and the old one closes after both finish.
  - Opening a DB with `schema_version` = 2 throws `SchemaMismatchException`.
  - `carriersIn(Canberra bounds, {TELSTRA}, all, null)` on the fixture DB equals an SQL count of Telstra carriers on sites inside the bounds.
  - Array fields match SQL for carrier sdd `999999304`.
  - `siteCountWithin` equals a brute-force haversine count.
- [ ] **Step 2: Run** `./gradlew :core:data:test` → FAIL.
- [ ] **Step 3: Implement.** `carriersIn` is a single SQL join over the grid-index site IDs in the bounds, read into the
  arrays. Update the imports in `core/update` and `app`; the behaviour of `UpdateDeps` and the workers stays unchanged.
- [ ] **Step 4: Run** `./gradlew test testDebugUnitTest` → PASS.
- [ ] **Step 5: Commit** `refactor(data): TowerRepository in core:data with ref-counted stores; bulk carrier query`.

### Task 2: NDK toolchain and `:engine` module skeleton

**Files:**
- Modify: `docs/dev-setup.md`, `settings.gradle.kts`, `gradle/libs.versions.toml`
- Create: `engine/build.gradle.kts`, `engine/src/main/cpp/CMakeLists.txt`, `engine/src/main/cpp/{engine.h,engine.cpp,jni_bridge.cpp}`, `engine/src/main/kotlin/au/signalatlas/engine/NativeEngine.kt`
- Create: `engine/host/CMakeLists.txt` (host build: engine sources + GoogleTest), `engine/host/test/smoke_test.cpp`, `engine/host/run-tests.sh`

**Interfaces — Produces:** `object NativeEngine { fun version(): Int }` (JNI). The C ABI `extern "C" int sa_engine_version()`
returns `1`. `engine/host/run-tests.sh` configures, builds and runs `ctest` with SDK CMake 4.1.2 and Apple clang. The
Gradle task `:engine:hostTest` (Exec) runs it, and `./gradlew check` depends on it.

- [ ] **Step 1:** Install `ndk;30.0.16248370` and `cmake;4.1.2` with sdkmanager, and document them in `docs/dev-setup.md`.
- [ ] **Step 2: Write the failing GoogleTest** `Smoke.Version` → `EXPECT_EQ(sa_engine_version(), 1)`. Run
  `engine/host/run-tests.sh` → FAIL (does not build).
- [ ] **Step 3: Implement the module.** It is an Android library with `externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "4.1.2" } }`,
  `ndkVersion = "30.0.16248370"`, flags `-O3 -fvisibility=hidden -std=c++20`, link flag `-Wl,-z,max-page-size=16384`,
  and no exceptions across JNI (`-fno-exceptions` in the JNI translation unit).
- [ ] **Step 4: Verify:** `engine/host/run-tests.sh` passes; `./gradlew :engine:assembleRelease :app:assembleRelease`
  passes; `zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk` passes; and a Robolectric-free
  instrumented-free check: `unzip -l` lists `lib/arm64-v8a/libsaengine.so`.
- [ ] **Step 5: Commit** `build(engine): NDK module, JNI bridge and host GoogleTest harness`.

### Task 3: Python references (coverage model + HRP) and fixtures

**Files:**
- Create: `tools/engine/reference.py` (Coverage model rules 2–9 as pure functions plus a tile renderer), `tools/engine/make_vectors.py`, `tools/engine/README.md`
- Create: `tools/acma/hrp_reference.py`, `tools/acma/make_hrp_fixture.py`
- Create: `engine/host/test/vectors/{pathloss.csv,antenna.csv,eirp_re.csv,tile_canberra_z13.json}`
- Create: `core/acma/src/test/resources/acma/hrp_fixture.zip`, `core/acma/src/test/resources/acma/hrp_expected.json`

**Interfaces — Produces:**
- **`pathloss.csv`:** columns `f_mhz,d_km,h_tx,area,model,pl_db,sigma_db`. It is a grid of f ∈ {700, 900, 1800, 2100,
  2600, 3500, 26000}, d ∈ {0.05, 0.3, 1, 3, 10, 30}, h_tx ∈ {10, 30, 60}, and all 4 areas.
- **`antenna.csv`:** columns `bearing,elev_deg,az,hbw,fb,gain,tilt,h_tx,d_m,a_db`.
- **`eirp_re.csv`:** columns `tech,f_mhz,bw_hz,eirp_dbm,eirp_re_dbm`.
- **`tile_canberra_z13.json`:** `{z,x,y,selection,carriers:[...],rsrpCodes:[256*256]}` for the z13 tile containing
  (−35.2809, 149.1300). It uses the M1 fixture carriers for TELSTRA, with HRP absent.
- **HRP fixture:** the first 40 complete devices of the real `LICENCE_.csv` (taken by ranged read, as in the
  verification script), plus synthetic rows:
  - an incomplete device (359 rows)
  - an out-of-order device
  - an SDD not in the tower fixture
  - two devices with identical shapes but different peak bearings, so they de-duplicate
  - SDDs remapped so that 10 of them equal real SDDs in the M1 tower fixture
- **`hrp_expected.json`:** the HrpReport fields plus `{"sdd": ..., "patternId": ..., "peakBearing": ..., "att0_10": [...]}`
  for 5 named SDDs.

- [ ] **Step 1: Implement `reference.py`** from Coverage model rules 1–9 using stdlib `math` only. Implement
  `hrp_reference.py` from HRP rules 1–6.
- [ ] **Step 2: Generate the vectors and fixtures** (commands in the READMEs; `python3 -I`; the real files stay in the
  scratchpad). Sanity checks:
  - Hata, 900 MHz, 1 km, h 30, URBAN gives about 126.4 dB (hand-checked against the formula).
  - LTE 20 MHz `eirpRe` = eirp − 30.79.
  - NR 100 MHz at 3.5 GHz = eirp − 35.15.
- [ ] **Step 3: Commit** `test(engine,acma): python references, vectors and HRP fixture`.

### Task 4: On-device HRP import and monthly job

**Files:**
- Create: `core/acma/src/main/kotlin/au/signalatlas/core/acma/hrp/{HrpStreamParser.kt,HrpImporter.kt,HrpReport.kt,PatternsDbSchema.kt}`
- Create: `core/data/src/main/kotlin/au/signalatlas/core/data/{PatternStore.kt,PatternInstaller.kt}`
- Create: `core/update/src/main/kotlin/au/signalatlas/core/update/{HrpRemote.kt,HrpWorker.kt}` and modify `UpdateManager.kt` and `UpdatePolicy.kt`
- Test: `core/acma/src/test/.../hrp/HrpImporterTest.kt`, `core/data/src/test/.../PatternStoreTest.kt`, `core/update/src/test/.../HrpWorkerTest.kt`

**Interfaces — Produces:**
```kotlin
class HrpStreamParser(input: InputStream) { fun next(): Boolean; val sddId: Long; val startAngle: Int; val powerDbw: Float } // byte-level, zero allocation per row
class HrpImporter(driver: SQLiteDriver = BundledSQLiteDriver(), log: (String) -> Unit = {}) {
    fun import(hrpZip: File, towersDb: File, out: File, etag: String, cancel: () -> Boolean = { false }, progress: (Float) -> Unit = {}): HrpReport
}
class PatternStore private constructor(...) : Closeable {
    companion object { fun open(db: File): PatternStore }                 // read-only; loads every pattern into one ByteArray (id * 360)
    fun lookup(sddIds: LongArray, outPatternId: IntArray, outPeakBearing: ShortArray)   // 0 = none
    val patternBytes: ByteArray; val patternCount: Int; val etag: String
}
class PatternInstaller(dir: File)    // same fsync + atomic-rename discipline as DbInstaller; file dir/patterns.db
// UpdateManager: fun scheduleHrp(); status gains HrpStatus(lastImportedAt, devices, running)
```
**Constants:**
- `HrpRemote` uses the URL `https://cdn.acma.gov.au/rrl/spectra_licence_hrp.zip`, with the same resumable download and
  ETag sidecar as `AcmaRemote` (reuse it through a shared helper; no copy-paste).
- The worker's unique name is `hrp-monthly` (periodic, 30 days, KEEP).
- The worker's constraints are UNMETERED, charging and storage-not-low.
- The worker checks for ≥ 3 GB free with `StatFs` before downloading. If there isn't enough, it returns `retry()`.
- The zip is downloaded to `towers/download/spectra_licence_hrp.zip` and discarded after a successful install.

- [ ] **Step 1: Write the failing tests:**
  - Importing `hrp_fixture.zip` against the M1 fixture `towers.db` matches `hrp_expected.json` exactly.
  - Cancel mid-stream → `ImportCancelledException`, and `out` is deleted.
  - The parser reaches ≥ 1,000,000 rows/s on the host for a 5M-row generated stream (fixed seed; assert after warm-up).
  - `PatternStore.lookup` returns 0 for unknown SDDs.
  - `PatternInstaller` keeps the old `patterns.db` when given a truncated candidate.
  - `HrpWorker` with < 3 GB (fake `StatFs` seam) → `retry`, with no request made.
  - Same ETag as installed → `success`, with no download.
  - A stopped worker never leaves a `patterns.db.building` that gets opened.
- [ ] **Step 2: Run** `./gradlew :core:acma:test :core:data:test :core:update:testDebugUnitTest` → FAIL.
- [ ] **Step 3: Implement.** Keep the HRP job independent of the weekly tower update. `tower_stamp` records which tower
  data the patterns were built against; SDDs that later disappear are simply unused.
- [ ] **Step 4: Run** the same → PASS.
- [ ] **Step 5: Add `HrpFullTest`**, skipped unless `ACMA_HRP_ZIP` and `ACMA_FULL_ZIP` are set. It imports the real
  1.56 GB file against a full `towers.db` built from the real export. Run it once and record the device and pattern counts,
  rows, time, `patterns.db` size and peak heap in `tools/acma/README.md`.
- [ ] **Step 6: Commit** `feat(acma,update): monthly on-device HRP pattern import`.

### Task 5: Engine math (EIRP/RE, antenna, path loss, probability)

**Files:**
- Create: `engine/src/main/cpp/model/{eirp.h,eirp.cpp,antenna.h,antenna.cpp,pathloss.h,pathloss.cpp,stats.h}`
- Test: `engine/host/test/{eirp_test.cpp,antenna_test.cpp,pathloss_test.cpp,robustness_test.cpp}`

**Interfaces — Produces (C++, namespace `sa`):**
```cpp
enum class Tech : uint8_t { LTE = 0, NR = 1 };
enum class Area : uint8_t { DenseUrban = 0, Urban = 1, Suburban = 2, Rural = 3 };
float eirpPerRe(float eirpDbm, Tech tech, float freqMhz, int32_t bandwidthHz);
struct AntennaParams { float azimuthDeg /*NaN omni*/, hBwDeg /*NaN*/, fbDb /*NaN*/, gainDbi /*NaN*/, tiltDeg, hTxM, freqMhz; const uint8_t* hrp /*null or 360*/; int16_t hrpPeakBearing; };
float antennaAttenuation(const AntennaParams&, float bearingDeg, float distM);   // rule 3–4, ≥ 0
struct PathLoss { float plDb, sigmaDb; };
PathLoss pathLoss(float freqMhz, float dKm3d, float hTxM, Area area);           // rule 6
float o2iLowLossDb(float freqMhz);                                               // rule 7
float coverageProbability(float rsrpDbm, float thrDbm, float sigmaDb);           // rule 8
float maxRangeKm(float eirpReDbm, float freqMhz, float hTxM, Area area);         // rule 9 culling, ≤ 80
```

- [ ] **Step 1: Write the failing tests.**
  - Every row of `pathloss.csv`, `antenna.csv` and `eirp_re.csv` matches within 0.01 dB.
  - `maxRangeKm` is monotonic in EIRP.
  - **Robustness:** NaN or ±inf in any input, h_tx = 0, bandwidth = 0 and frequency outside every range all return
    finite values. Nothing aborts. Unknown cases clamp to the nearest valid range.
- [ ] **Step 2: Run** `engine/host/run-tests.sh` → FAIL.
- [ ] **Step 3: Implement** exactly per Coverage model rules 2–8, using `float` math and `std::log10`/`std::exp`. No allocation.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(engine): EIRP/RE, antenna and path-loss models matching the reference`.

### Task 6: Engine tile renderer

**Files:**
- Create: `engine/src/main/cpp/render/{tile.h,tile.cpp,mercator.h}`
- Test: `engine/host/test/{tile_test.cpp,tile_bench.cpp}`

**Interfaces — Produces:**
```cpp
struct CarrierArrays { int32_t n; const double *lat, *lon; const float *hTx, *az, *tilt, *eirpRe, *freqMhz, *hBw, *fb, *gain, *rMaxKm;
                       const uint8_t* tech; const int32_t* sectorKey; const int32_t* freqKey; const int32_t* hrpId; const int16_t* hrpPeak; };
struct PatternTable { int32_t count; const uint8_t* att; /* count * 360, id 1-based */ };
struct RenderParams { int32_t z, x, y, grid /*128|256*/; Area area; bool indoor; float thrDbm; };
struct TileOut { uint8_t* rsrp; uint8_t* sinr; uint8_t* prob; /* each 256*256 */ };
int32_t renderTile(const CarrierArrays&, const PatternTable&, const RenderParams&, TileOut&, const volatile int32_t* cancelFlag); // returns carriers evaluated, −1 if cancelled
```
Approach: per carrier, do the bounding-box cull on `rMaxKm`. For each grid point compute RSRP and keep a running
best-server value, plus per-freqKey linear sums for SINR. Sectors sharing (site, azimuth) reuse the bearing and distance per
point. Bilinear upsample when grid = 128. Poll `cancelFlag` once per grid row.

- [ ] **Step 1: Write the failing tests:**
  - `tile_canberra_z13.json` → `rsrpCodes` equal the reference for ≥ 99.9% of pixels, and every pixel within ±1 code.
  - A tile in the Simpson Desert (z10, no carriers within R_max) returns 0 carriers evaluated and an all-zero output within 1 ms.
  - Cancelling halfway returns −1 promptly.
  - Robustness: 1 carrier with NaN lat → skipped, output finite.
- [ ] **Step 2: Bench** (`tile_bench`, Release): a z13 tile with 300 carriers on a 128 grid must take < 60 ms single-threaded
  on the M1 Max host. Record the result in `engine/host/README.md`.
- [ ] **Step 3: Run** `engine/host/run-tests.sh` → FAIL, then implement, then PASS.
- [ ] **Step 4: Commit** `feat(engine): best-server RSRP/SINR/probability tile renderer`.

### Task 7: JNI bridge and Kotlin engine API

**Files:**
- Modify: `engine/src/main/cpp/jni_bridge.cpp`, `engine/src/main/kotlin/au/signalatlas/engine/NativeEngine.kt`
- Create: `engine/src/main/kotlin/au/signalatlas/engine/{CoverageEngine.kt,EngineTypes.kt}`
- Test: `engine/src/androidTest/kotlin/au/signalatlas/engine/CoverageEngineTest.kt` (runs on the emulator), `engine/src/test/kotlin/.../EngineTypesTest.kt` (JVM)

**Interfaces — Produces (Kotlin):**
```kotlin
enum class AreaClass { DENSE_URBAN, URBAN, SUBURBAN, RURAL }
class PreparedCarriers internal constructor(...) : AutoCloseable   // direct ByteBuffers, native-order; built once per (data version, selection, region)
class CoverageEngine {
    fun prepare(batch: CarrierBatch, patterns: PatternStore?, indoor: Boolean): PreparedCarriers   // computes eirpRe, rMax, HRP lookup, sector/freq keys
    fun render(prepared: PreparedCarriers, z: Int, x: Int, y: Int, area: AreaClass, thrDbm: Float, cancel: CancelToken): RenderedTile?   // null if cancelled
}
class RenderedTile(val z: Int, val x: Int, val y: Int, val rsrp: ByteArray, val sinr: ByteArray, val prob: ByteArray)
class CancelToken { fun cancel() }   // backed by a direct IntBuffer the native side polls
```
The pattern table is uploaded to native code once per `PatternStore` instance, through a direct ByteBuffer that is
kept alive.

- [ ] **Step 1: Write the failing tests.**
  - JVM: `prepare` math helpers that live in Kotlin (none expected beyond marshalling) and the byte layouts.
  - Emulator (`./gradlew :engine:connectedDebugAndroidTest` on `sa_pixel`): the Canberra tile through JNI equals the
    host result for the same inputs; `CancelToken` cancels; 200 renders in a loop show no native memory growth
    (`Debug.getNativeHeapAllocatedSize` delta < 2 MB).
- [ ] **Step 2: Run → FAIL. Implement. Run → PASS.**
- [ ] **Step 3: Commit** `feat(engine): JNI bridge and Kotlin CoverageEngine API`.

### Task 8: `:core:coverage` — tile service, cache and scheduling

**Files:**
- Create: `core/coverage/build.gradle.kts` (Android library; deps engine, data, model)
- Create: `core/coverage/src/main/kotlin/au/signalatlas/core/coverage/{CoverageSelection.kt,TileKey.kt,CoverageTileService.kt,TileCache.kt,AreaClassifier.kt,TerrariumEncoder.kt,CoverageInterceptor.kt,RenderPool.kt}`
- Test: `core/coverage/src/test/kotlin/.../{TerrariumEncoderTest.kt,TileCacheTest.kt,CoverageTileServiceTest.kt,CoverageInterceptorTest.kt,AreaClassifierTest.kt}`

**Interfaces — Produces:**
```kotlin
data class CoverageSelection(val network: Network, val techs: Set<Tech>, val bands: Set<String>?, val indoor: Boolean, val view: View) { enum class View { RSRP, PROBABILITY } ; fun cacheId(): String /* excludes view: tiles hold RSRP+SINR+prob codes */ }
data class TileKey(val z: Int, val x: Int, val y: Int, val selectionId: String, val towerStamp: String, val patternsEtag: String /* "none" */, val engineVersion: Int)
class CoverageTileService(repo: TowerRepository, patterns: StateFlow<PatternStore?>, engine: CoverageEngine, cache: TileCache, pool: RenderPool) {
    val selection: MutableStateFlow<CoverageSelection?>       // null = coverage off
    fun tileUrlTemplate(): String?                            // "https://coverage.signalatlas.invalid/v1/<selectionId>/<view>/<towerStamp>/<patternsEtag>/{z}/{x}/{y}.png" — view only changes encoding
    suspend fun tilePng(z: Int, x: Int, y: Int, url: String): ByteArray   // renders or serves cache; Terrarium PNG 256×256
    fun setVisible(tiles: Set<Triple<Int, Int, Int>>)         // viewport priority; renders not in the set are cancelled
    fun valueAt(lat: Double, lon: Double): CoverageSample?   // from cached tiles at the highest zoom available; null if none
}
data class CoverageSample(val rsrpDbm: Float?, val sinrDb: Float?, val probability: Float?)
class CoverageInterceptor(service: CoverageTileService) : okhttp3.Interceptor   // only host coverage.signalatlas.invalid; responds 200 image/png, Cache-Control: no-store; 204 for out-of-range zooms
class TileCache(dir: File, memoryTiles: Int = 96, diskBytes: Long = 256L shl 20)   // raw 3×64 KB tiles; LRU; keyed by TileKey
object TerrariumEncoder { fun encode(rsrpCodes: ByteArray, view: CoverageSelection.View, probCodes: ByteArray): ByteArray }   // PNG via android.graphics.Bitmap (Robolectric in tests)
object AreaClassifier { fun classify(siteCountWithin3km: Int): AreaClass }
class RenderPool(threads: () -> Int)   // thermal rule from Global Constraints
```
Behaviour:
- `prepare()` runs once per (selection, data versions, a z10 region of 4×4 tiles, expanded by 80 km), and the result is
  reused by every tile inside it.
- A `TileKey` that changes in any field is a cache miss, so data from different versions is never mixed.
- `valueAt` decodes the cached RSRP code.

- [ ] **Step 1: Write the failing tests:**
  - **Terrarium:** encode → decode round trip; −200 for code 0.
  - **TileCache:** LRU eviction by count and bytes; a corrupt disk entry is ignored.
  - **Interceptor:** another host passes through; z 7 → 204; a bad path → 404.
  - **Service** (fake engine):
    - `setVisible` cancels renders for tiles not visible (the CancelToken is cancelled).
    - A tower-stamp change produces new keys.
    - At most N concurrent renders, following the thermal rule.
    - Memory LRU ≤ 96.
  - **AreaClassifier** thresholds: 39 → URBAN, 40 → DENSE_URBAN, 11 → SUBURBAN, 2 → RURAL.
- [ ] **Step 2: Run** `./gradlew :core:coverage:testDebugUnitTest` → FAIL. Implement. Run → PASS.
- [ ] **Step 3: Commit** `feat(coverage): tile service with priority scheduling, cache and terrarium serving`.

### Task 9: Map integration (raster-dem + color-relief)

**Files:**
- Modify: `core/map/.../TowerMap.kt`; create `core/map/.../CoverageLayer.kt`
- Modify: `app/.../AppContainer.kt` (call `HttpRequestUtil.setOkHttpClient(okHttp.newBuilder().addInterceptor(coverageInterceptor).build())` before the first MapView)
- Test: `core/map/src/test/kotlin/.../CoverageLayerTest.kt`

**Interfaces — Produces:**
```kotlin
object CoverageLayer {
    const val SOURCE_ID = "coverage"; const val LAYER_ID = "coverage-relief"
    fun colorExpression(view: CoverageSelection.View, opacity: Float = 0.45f): Expression   // legend rule 11; interpolate on ["elevation"]
    fun sourceJson(urlTemplate: String): String   // {"type":"raster-dem","tiles":[url],"tileSize":256,"encoding":"terrarium","minzoom":8,"maxzoom":16}
}
// TowerMap gains: coverageUrlTemplate: String?, coverageView: CoverageSelection.View, onVisibleTiles: (Set<Triple<Int,Int,Int>>) -> Unit
```
The layer is inserted below the tower layers and above roads and labels. Changing the view or opacity changes only the
paint property `color-relief-color`, with no source reload. Changing the URL template (a new selection or data version)
replaces the source.

- [ ] **Step 1: Spike on the emulator before writing the full code.**
  - Add a debug-only screen that loads one constant Terrarium tile (elevation −90) through the interceptor into a
    `color-relief` layer, then take a screenshot.
  - If `color-relief` does not render on Android 13.7.1: fall back to a `raster` source of pre-coloured PNG tiles. In
    that case `TerrariumEncoder` gains `encodeColored()`, and a colour change becomes a URL change with a re-encode from
    cached codes (≤ 2 ms per tile).
  - Record which path was taken in the commit message and in `core/map/README.md`.
- [ ] **Step 2: Write the failing tests:** the `colorExpression` JSON contains the 4 RSRP stops with the colours from
  rule 11; `sourceJson` has `encoding:"terrarium"` and minzoom 8.
- [ ] **Step 3: Implement.** Compute `onVisibleTiles` from the camera on idle (z clamped 8–16).
- [ ] **Step 4: Run** `./gradlew :core:map:testDebugUnitTest :app:assembleDebug` → PASS.
- [ ] **Step 5: Commit** `feat(map): GPU-coloured coverage layer from engine tiles`.

### Task 10: App UI for coverage

**Files:**
- Modify: `app/.../ui/map/{MapViewModel.kt,MapUiState.kt,MapScreen.kt}`
- Create: `app/.../ui/coverage/{CoverageControls.kt,CoverageLegend.kt,PointReadout.kt}`
- Modify: `app/.../ui/updates/UpdatesScreen.kt` (an "Antenna patterns" row)
- Test: `app/src/test/kotlin/.../ui/coverage/CoverageViewModelTest.kt`, plus Compose tests

**Behaviour (exact copy in quotes):**
- A floating-toolbar button **"Coverage"** toggles coverage. When it's on, a compact control sheet shows:
  - network segmented buttons (Telstra / Optus / TPG / NBN; default = the first network selected in the filter chips,
    otherwise Telstra)
  - **"4G + 5G" / "5G only"**
  - **"Indoor"** switch
  - **"Signal" / "Reliability"** view toggle (RSRP / probability)
- The legend card at the bottom start reads "Excellent ≥ −85 dBm", "Good", "Fair", "Weak", or for Reliability
  "≥ 90%", "50–90%".
- The existing band filter applies.
- Below z8, the chip **"Zoom in to see coverage"** shows.
- **Long-press** on the map shows a `PointReadout` card: **"Predicted −97 dBm · Good · 82% reliable"**, plus "Best
  sector: Telstra n78 · 1.2 km". If there is no cached sample: **"Calculating…"**, then the value.
- **Updates screen:**
  - **"Antenna patterns: Updated d MMM yyyy (N transmitters)"**, or **"Antenna patterns: Using standard patterns until
    the monthly download runs (Wi-Fi + charging)"**
  - a **"Download patterns now"** button: Wi-Fi only, confirmed with **"Download about 1.6 GB on Wi-Fi?"**

- [ ] **Step 1: Write the failing ViewModel tests:**
  - Toggling coverage on sets `CoverageTileService.selection`.
  - Turning it off sets the selection to null.
  - Changing the network or Indoor produces a new selection id.
  - The view toggle keeps the same `cacheId()` (cached tiles and prepared carriers are reused, with no tower re-query)
    and only changes the URL template's view segment.
  - Long-press formats the readout strings exactly as above.
- [ ] **Step 2: Run → FAIL. Implement. Run** `./gradlew :app:testDebugUnitTest :app:assembleDebug` **→ PASS.**
- [ ] **Step 3: Commit** `feat(app): coverage controls, legend, point readout and pattern status`.

### Task 11: End-to-end verification (emulator, real data)

**Files:**
- Create: `docs/verification/m2.md`, plus screenshots in `docs/verification/m2/`

- [ ] **Step 1: Run all suites:** `./gradlew test testDebugUnitTest :engine:hostTest`, and
  `:engine:connectedDebugAndroidTest` on `sa_pixel`.
- [ ] **Step 2: Release APK on the emulator, clean install.**
  - Import towers.
  - Trigger "Download patterns now" (emulator on Wi-Fi and "charging" via `adb shell dumpsys battery set ac 1`).
  - Record the HRP download and import times, device and pattern counts, and `patterns.db` size.
- [ ] **Step 3: Screenshots:**
  - Canberra z13 Telstra coverage (Signal), the same in Reliability, Optus, Indoor on
  - Sydney CBD z15
  - a rural highway (Hume Hwy near Gundagai) z11, before and after patterns are imported
  - the legend
  - a long-press readout
  - dark mode
- [ ] **Step 4: Measure:**
  - first coverage tile latency after enabling coverage (logcat timing)
  - median and p90 tile render time per zoom
  - CPU threads in use
  - frame pacing while panning with coverage on (gfxinfo; emulator figures are indicative only)
  - native heap after 2 minutes of panning
- [ ] **Step 5: Sanity-check plausibility:** report the predicted −118 dBm radius for one rural Telstra B28 site and one
  CBD n78 site. Compare them with typical published planning ranges (rural 700 MHz macro tens of km; urban 3.5 GHz small
  hundreds of metres) and note any wild outliers as M4 calibration inputs.
- [ ] **Step 6: Write `m2.md`** (PASS/FAIL per check, numbers, known gaps) and commit `docs: M2 verification results`.
