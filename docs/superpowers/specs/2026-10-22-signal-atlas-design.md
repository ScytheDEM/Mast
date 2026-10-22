# Signal Atlas — Design Spec

- **Date:** 2026-10-22
- **Status:** Approved in conversation, pending written-spec review
- **Working title / package:** Signal Atlas / `au.signalatlas.app` (rename before publishing)

## 1. Intent

An Android app for viewing Australian mobile phone towers and **accurately predicted coverage**, with a
"you're about to leave coverage" alert while driving. It is functionally a superset of the "Aus Phone
Towers" app, but independently built, more accurate, and tuned for Pixel and Samsung phones and
foldables.

**Who it is for:** the owner first (sideloaded), then a public Google Play release.

**What success looks like**
- Coverage predictions that match real-world experience noticeably better than the reference app, and
  that keep improving on routes the user drives (local self-calibration).
- A "leaving coverage" alert that fires early enough to act on, and rarely fires falsely.
- Smooth on Pixel and Samsung hardware: **120 fps target** while panning and zooming (60 fps floor),
  cold start to an interactive map in under 1 s.
- Works **offline-first**: towers, coverage and the base map with no signal.
- Free to run: no servers, no paid APIs, **no accounts** needed for any data source.

### What the user specified vs assumptions

| Decided by the user | Assumed by Claude (correct if wrong) |
|---|---|
| Own app, published to Play eventually; not a contribution to the reference app | Clean-room implementation (no AGPL code reuse) so the licence stays the owner's choice |
| Phone downloads ACMA data weekly itself; offline-first | Minimum Android 10 (API 29) |
| Automatic update on Wi-Fi only, preferably while charging; cheap check on app open; manual "Check for updates" that may use mobile data | Kotlin + Jetpack Compose + C++20 NDK engine |
| Standard models plus terrain and building blocking; free, no-account data sources | Working title "Signal Atlas" |
| Region packs + auto-cache; quick model anywhere uncached + "Increase resolution" button | Auto-cache budget defaults to 2 GB |
| MapLibre + offline OSM base map, styled clean / iOS-like | Default alert window 90 s or 2 km |
| Trip mode (not always-on background) | Trip corridor fetches allowed on mobile data by default |
| Android Auto with band info; Live Updates (the Android "live activity") | |
| v1 features: connected tower, filters & search, tower details & lists, export & sharing | |
| Local self-calibration (on-device only) | |
| Approach 2: native C++ engine + GPU-drawn raster coverage tiles | |
| 120 fps target | |

### Out of scope for v1
- Crowd-sourced upload or any backend.
- Always-on background location monitoring.
- User ranking / gamification and ads / in-app purchases (monetisation decided before the Play release).
- A Vulkan-compute propagation engine (possible later accelerator inside the C++ engine).
- Home-screen widgets.

## 2. Architecture

- **Platform:** minSdk 29, targetSdk latest. Kotlin, Jetpack Compose, Material 3 Expressive with dynamic
  colour. C++20 engine via NDK. ABIs: arm64-v8a (+ x86_64 for emulators). 16 KB page-size compatible.
- **Modules**

| Module | Responsibility |
|---|---|
| `:app` | Compose UI, navigation, adaptive layouts (foldables, tablets, DeX) |
| `:core:data` | Room/SQLite: sites, sectors, carriers, antennas, patterns, calibration; R-Tree spatial index |
| `:core:acma` | Update check, resumable download, streaming parse, normalise, validate, atomic swap, diff |
| `:core:geo` | Terrain and building fetch, conversion to engine tiles, region packs, LRU cache |
| `:engine` (C++) | Propagation → per-sector RSRP grid → best-server RSRP / SINR / coverage-probability tiles |
| `:core:coverage` | Kotlin wrapper: tile scheduling by viewport priority, cancellation, disk cache, edge-polygon extraction |
| `:core:radio` | Cell info, signal, carrier/MVNO detection, dual-SIM, connected-tower matching |
| `:feature:trip` | Trip foreground service, road-following look-ahead, alerts, calibration logging |
| `:car` | Android Auto (Car App Library), with a full-map flavour and a Play-compliant flavour |
| `:core:map` | MapLibre setup, custom clean style (light/dark), offline base-map packs, native coverage layer |

- **Data flow**

```
ACMA zip ─► :core:acma ─► Room DB ─┐
Terrain/Buildings ─► :core:geo ────┼─► :engine (C++) ─► signal tiles (disk cache) ─► MapLibre GPU layer
Live cells/GPS ─► :core:radio ─────┘                          └─► coverage edges ─► Trip alerts / Auto / Live Update
                    └─► calibration samples ─► back into :engine
```

- **Performance rules:** no heavy work on the main thread; the map only draws pre-computed tiles; engine
  work is prioritised by what is visible and cancelled when the view moves away.

## 3. ACMA pipeline and database

- **Source:** the ACMA Register of Radiocommunications Licences full weekly export (`spectra_rrl.zip`).
  The size (believed to be ~150 MB+ compressed) is **to be verified at implementation**; it could not be
  reached from the design environment.
- **Check:** on app open and on "Check for updates", send a `HEAD` request and compare `ETag` /
  `Last-Modified` with the stored values. If newer, show a "New tower data" chip.
- **Automatic update:** WorkManager, unmetered network required, prefers charging. If the data is more
  than 14 days old, the charging requirement is relaxed (Wi-Fi still required).
- **Manual update:** "Update now" may use mobile data, after confirming the size.
- **Processing:**
  1. Resumable download (HTTP Range) to a temp file.
  2. Random-access zip read (no full unzip). Entries are processed in dependency order: `client` →
     `licence` → `site` → `antenna` → `antenna_pattern` → `device_details`.
  3. Early filter to wanted operators: Telstra, Optus, TPG/Vodafone, NBN fixed wireless, and optional
     categories such as broadcast. Everything else is discarded row by row.
  4. Normalise into Site, Sector (site + antenna + azimuth group: height, tilt, EIRP/power, feeder loss),
     Carrier (frequency, bandwidth, technology derived from band and emission), and Antenna (gain,
     beamwidths, front-to-back, model). H/V radiation patterns are packed into 0.25 dB lookup tables.
  5. Build the SQLite R-Tree spatial index.
  6. Build into a new DB file → validate (row counts, coordinate bounds, referential integrity) → atomic
     rename. Keep the previous DB for rollback.
  7. Diff against the previous DB → invalidate coverage tiles only near changed sites → feed the
     "What's new" sheet.
- **ACMA field names and band/technology mappings live in one mapping file.**
- **Estimates to confirm:** 25–60 MB DB, 3–8 minutes of background processing on a mid-range phone.

## 4. Propagation engine (C++)

Per sector, per grid point:

`RSRP = EIRP_per_RE − pattern_loss(az, el) − path_loss − terrain_diffraction − building_loss − calibration_offset`

1. **EIRP per resource element:** taken from ACMA EIRP, or from power + gain − feeder loss, minus
   10·log10(subcarriers) for the carrier bandwidth and technology (for example ~31 dB for 20 MHz LTE).
2. **Antenna pattern:** ACMA H/V patterns steered by azimuth and ACMA tilt. Fallback is the 3GPP
   parametric pattern built from the beamwidths and front-to-back ratio.
3. **Path loss**, chosen automatically:
   - With terrain: **ITU-R P.1812** with **ITU-R P.526 delta-Bullington** diffraction.
   - Urban close range: **3GPP TR 38.901** UMa/UMi. Where building data exists, a deterministic
     line-of-sight check against building heights replaces the probabilistic one.
   - mmWave (n257/n258): 38.901 short-range model with heavy blocking.
   - **Quick model** (uncached areas, offline): COST-231 Hata / 38.901 RMa, with area type inferred from
     local site density.
4. **Buildings:** a building-height raster is used for blockage; blocked paths add **ITU-R P.1411**
   rooftop diffraction. An Indoor toggle adds 3GPP O2I building-entry loss.
5. **Calibration:** per-sector offset and slope plus a local residual grid (§6).

- **Coverage probability:** each point is modelled with a log-normal shadowing sigma (default ~8 dB,
  shrinking as calibration data accumulates). Alerts and the optional probability view use P(RSRP ≥
  threshold).
- **Speed:** radial sweep with 720 radials and an incremental horizon (~10–30 ms per site at 30 m),
  NEON SIMD, a thread pool on the big cores, and memory-mapped terrain/building tiles. Results are
  progressive (quick → full), stored as uint8 values in 0.5 dB steps.
- **Tile layers:** best-server RSRP, estimated SINR and serving-sector ID, per carrier × technology.

## 5. Rendering and map style

- **MapLibre Native Android:** Vulkan renderer where available, otherwise GLES. Fonts (Inter) and icons
  are bundled.
- **Base map:** Protomaps OSM vector data (OpenFreeMap as an online fallback). Offline packs are ranged
  extracts stored as PMTiles.
- **Style:** a custom clean / iOS-like design. Light: off-white land, soft blue water, gentle green
  parks, white roads with hairline casing, pale-amber motorways, minimal POIs. Dark: charcoal with muted
  roads. Optional hillshade and 3D buildings. Material You accent only on the location dot, selections
  and controls.
- **Coverage layer:** a native custom GPU layer reads the engine's tiles. A shader maps RSRP to colour.
  Changing threshold, colour scheme, carrier, band or Indoor/Outdoor only updates uniforms, so it is
  instant.
- **Legend (adjustable, colour-blind-safe):** Excellent ≥ −85 dBm · Good −85 to −100 · Fair −100 to −110 ·
  Weak −110 to −118 · none below.
- **Towers:** carrier-coloured pins with GPU clustering. Sector wedges show on selection. The connected
  tower pulses with a line to the user's location; neighbour cells are drawn faintly.
- **Targets:** 120 fps (p90 frame < 8.3 ms) while panning, cold start < 1 s, viewport tower query < 5 ms.

## 6. Trip mode, alerts, calibration

- **Start/stop:** manual, a Quick Settings tile, or automatically on Android Auto / a chosen car
  Bluetooth device connecting. Runs as a location foreground service. GPS runs at 1 Hz while moving and
  drops to a low rate when stationary (Activity Recognition).
- **Look-ahead:** snap to the road in the offline map data and follow the most natural continuation for
  1–20 km. Fallback is a heading projection.
- **Corridor pre-compute:** full-resolution coverage for the corridor ahead. Missing terrain/buildings
  are fetched (allowed on mobile data by default, can be turned off).
- **Alert rule:** sample every 50 m. Fire when there is a stretch of at least 300 m with P(coverage) <
  50% inside the warning window (default 90 s or 2 km, scaled by speed). The live signal trend can bring
  the alert forward. Hysteresis and cooldowns prevent flapping. Also "Back in coverage" and "Coverage
  returns in X km". The technology floor is configurable (lose 5G / lose 4G / lose all data).
- **Delivery:** notification (custom sound/vibration), optional TTS, Android Auto alert, Live Update.
- **Calibration logging** (app open or trip): position, serving cell (band, ARFCN, PCI, CI/TAC),
  RSRP/RSRQ/SINR, neighbours, timing advance. Throttled to ≈ one sample per 50 m or 5 s.
- **Cell → sector matching:** frequency match + distance vs timing advance + azimuth + predicted vs
  measured. Learned cell-ID → sector mappings are persisted.
- **Estimation:** a Bayesian per-sector offset (prior σ ≈ 8 dB), a distance slope once enough distance
  spread exists, and a ~100 m local residual grid along travelled roads.
- **Privacy:** on-device only. Settings offers view, export and delete for calibration data, plus a
  per-sector measured-vs-predicted chart.

## 7. Region packs and offline storage

- **Sources (free, no account):** Copernicus GLO-30 (AWS Open Data, ranged COG reads); OSM buildings with
  heights/levels from the Protomaps data; Microsoft Global ML Building Footprints for gaps.
- **Unknown heights:** estimated from levels, or from type and area. Tagged heights win.
- **On-device format:** compressed, checksummed, memory-mappable terrain grid and 5 m building-height grid
  tiles.
- **Packs:** states/territories, metro areas, major highway corridors, a custom box, or "Along a route"
  (A → B with a corridor width). The size is shown before download. Packs download on Wi-Fi in the
  background, are resumable, install atomically, and can be updated or deleted individually.
- **Auto-cache:** around viewed areas and trip corridors. LRU within the budget (default 2 GB). Packs are
  never evicted.
- **Resolution chip + "Increase resolution":** the chip shows Quick estimate → Terrain → Terrain +
  buildings. The button shows the size, fetches data for the visible area (mobile data allowed), refines
  tile by tile, then keeps the area cached. With no signal it shows "Available when online".
- **Storage screen:** per-category sizes and clear actions.

## 8. Connected tower, Live Updates, Android Auto

- **Radio:** `TelephonyManager` cell info / `TelephonyCallback`, NSA awareness, and graceful handling of
  partial vendor data (Samsung Exynos, Pixel Tensor).
- **Carrier detection:** MCC/MNC 505-01/02/03…; MVNOs mapped to their host network; dual-SIM picker
  (defaults to the data SIM); roaming shown.
- **Info bar:** carrier · tech · bands · RSRP · tower distance/bearing · estimated speed (from bandwidth
  + SINR) · confidence ("Likely" / "Confirmed").
- **Live Updates (API 36+):** a promoted ongoing notification with a status-bar chip showing a short value
  (for example "1.8 km"). `ProgressStyle` segments act as a coverage strip of the road ahead with a car
  tracker. Shown in the Samsung One UI 8 Now Bar. API < 36 falls back to a standard ongoing notification.
- **Android Auto:** a map (coverage, towers, road ahead) plus an info panel (carrier, tech, band, signal,
  next dead zone) and car alerts with optional voice.
  - `fullMap` flavour (owner sideload, AA developer mode): custom map surface.
  - `play` flavour: template-only (info, tower list, alerts), because custom map surfaces are reserved
    for turn-by-turn navigation apps.

## 9. UI and devices

- **Screens:** Map (search bar, Expressive floating toolbar, info bar); Site sheet (sectors, pattern
  plot, licence, measured-vs-predicted, per-sector footprint, export); Filters; Nearby list; Updates +
  What's new; Offline regions; Calibration; Storage; Settings.
- Sharing via the share sheet: KML / GeoJSON / CSV.
- **Adaptive layouts:** compact (phones, Fold cover, Flip Flex Window ≥ 300 dp); expanded (Fold inner,
  tablets) map + hinge-aware side panel; tabletop (map top / controls + coverage strip bottom); book
  (split at the hinge); DeX, multi-window and pop-up view fully resizable. The map surface survives
  fold/unfold without recreation.
- **Tuning:** `Surface.setFrameRate` for 120 Hz; ADPF performance hints; thermal-headroom back-off;
  Baseline + Startup Profiles; R8 full mode; Samsung "Never sleeping apps" guidance when Trip mode is
  killed.

## 10. Testing

- **Engine (GoogleTest):** ITU-R P.1812 validation examples, 3GPP 38.901 reference curves, pattern
  interpolation, radial sweep vs brute force, EIRP-per-RE conversion.
- **Kotlin (JUnit):** ACMA parser on a trimmed real fixture, mapping tables, carrier/MVNO mapping, tower
  matching, calibration estimator, alert logic replayed against recorded drive tracks.
- **UI:** Compose tests; foldable posture simulation (WindowManager testing) on Pixel Fold and Galaxy Z
  Fold profiles.
- **Performance:** Macrobenchmark (startup, frame timing; gate p90 < 8.3 ms), JankStats, engine
  microbenchmarks.
- **CI:** GitHub Actions build and test on every change.

## 11. Risks and open items

- **ACMA export size and format:** verify before building the parser. Isolated behind the mapping file.
- **ACMA EIRP semantics** vary by licence type; validate on known sites against field measurements.
- **The MapLibre custom layer with the Vulkan backend** may lag behind GLES. The fallback is a
  local raster tile source with a raster-colour ramp.
- **PMTiles ranged extract from the Protomaps build:** confirm the usage terms and stability of build
  URLs. OpenFreeMap is the fallback.
- **Android Auto Play policy** for the `play` flavour: re-check at publish time.
- **Background location policy** for the Trip foreground service: prepare the Play declaration and
  video at publish time.
- **Scale:** this is a large project. It will be delivered in milestones defined in the implementation
  plan, each producing a runnable app.
