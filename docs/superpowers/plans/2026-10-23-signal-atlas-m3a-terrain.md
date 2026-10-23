# Signal Atlas — Milestone 3a: Terrain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hills block signal. Coverage predictions account for terrain between each tower and every map point,
using free Copernicus elevation data fetched on demand ("Increase resolution") and cached on the phone. The quick
model still shows instantly wherever terrain isn't downloaded yet.

**Architecture:**
- **`:core:geo` (new, Kotlin)** reads Copernicus GLO-90 Cloud-Optimised GeoTIFFs over HTTP range requests and converts
  them into compact on-device elevation chunks (int16 metres, 0.25° × 0.25°), stored in an LRU cache.
- **Native engine** gains a terrain stage. Per (site, frequency) it builds a polar table of terrain excess loss: 360
  radials × 256 range bins, computed with the ITU-R P.526 Bullington construction over DEM profiles. The tile renderer
  adds that loss per pixel.
- **`:core:coverage`** decides per tile whether terrain is available, schedules table builds in the background, and
  includes the terrain data version in tile keys.
- **The UI** gains an accuracy chip and the "Increase resolution" button.

**Tech Stack:** As in M2. No new libraries (TIFF/COG parsing and DEFLATE use `java.util.zip.Inflater`).

**Spec:** `docs/superpowers/specs/2026-10-22-signal-atlas-design.md` (§4 path loss with terrain, §7)
**Builds on:** branch `m2-coverage-engine` once M2's final review is clean.

## Scope split (decided 2026-10-23, budget-driven)

- **M3a (this plan):** terrain via GLO-90 (90 m), the terrain excess-loss model, "Increase resolution", the auto-cache,
  the accuracy chip and a Storage screen.
- **M3b (later plan):** 30 m terrain near the viewer, buildings (Microsoft footprints with heights + OSM), building
  loss (P.1411), 3D buildings, region packs (states, metros, corridors, custom box) including the offline base map via
  MapLibre `OfflineManager`.

## Verified facts (2026-10-23)

- **GLO-90:** `https://copernicus-dem-90m.s3.amazonaws.com/Copernicus_DSM_COG_30_{S|N}{LL}_00_{E|W}{LLL}_00_DEM/Copernicus_DSM_COG_30_{S|N}{LL}_00_{E|W}{LLL}_00_DEM.tif`.
  - Public, with no account, and `Accept-Ranges: bytes`.
  - About 5.3 MB per 1° tile; for example `S36_00_E148` is 5,265,734 bytes.
- **GLO-30 layout** (GLO-90 is the same family; the implementer confirms GLO-90's own tags):
  - 3600×3600 float32, DEFLATE (compression 8) with floating-point predictor 3, internal tiles 1024×1024.
  - ModelTiepoint origin = top-left corner. `S36_00_E148` has its origin at (148.0, −35.0), so the tile named S36
    covers latitude −36…−35.
  - Pixel scale is 1/3600°.
  - Ocean tiles do not exist (404) and mean elevation 0.
- **Microsoft building footprints** (for M3b): `https://minedbuildings.z5.web.core.windows.net/global-buildings/dataset-links.csv`.
  - 1,776 Australia quadkey files, about 1.07 GB total gz, GeoJSON lines.
  - Each record has `height` in metres, with −1 meaning unknown.

## Global Constraints

- **M1/M2 constraints still apply:** minSdk 29, no accounts or keys, no heavy main-thread work, engine purity,
  `-fno-exceptions`, 16 KB pages, and the commit trailer.
- **Terrain source:** GLO-90 only in M3a.
- **Fetching tiles:** fetch only the internal COG tiles needed, by Range request. Never splice versions: pin requests
  with `If-Match` on the tile's ETag, and restart on 412 or a change (reuse M2's `ResumableDownload`/provenance helper
  where it fits).
- **Elevation chunks:**
  - 0.25° × 0.25°, one sample every 3 arcseconds (300×300 samples plus a 1-sample overlap on the north and east edges,
    so 301×301).
  - int16 metres (rounded half away from zero; NaN or no-data becomes 0), DEFLATE-compressed.
  - File `geo/dem90/<latIdx>_<lonIdx>.dem`, where `latIdx = floor((lat + 90) * 4)` and `lonIdx = floor((lon + 180) * 4)`.
  - Header: magic `SADEM1`, version, chunk indices, CRC32.
- **Cache:**
  - LRU with a 2 GiB default budget, set in Settings to 0.5 / 1 / 2 / 4 GiB.
  - Chunks pinned by future region packs are never evicted; pinning gets a stub API in M3a.
  - Automatic fetches (around where the map is opened) run on unmetered networks only.
  - "Increase resolution" may use mobile data after showing the size.
- **Terrain need for a tile:** every chunk intersecting the tile's bounds expanded by the region margin (40 km).
  Carriers beyond 40 km of the tile contribute only if their range exceeds that, so for those the quick model is used
  for points outside the chunks fetched. See Terrain rule 6.

### Terrain model (shared by `tools/engine/terrain_reference.py` and the C++ engine; change both together)

1. **Profile:** samples along the great circle from the site (tx) to the point (rx) every 90 m, plus the endpoint.
   - Each sample's height is the DEM, interpolated bilinearly in lat/lon from chunk samples.
   - tx height above sea level = DEM(site) + `h_tx` (ACMA height AGL); rx height = DEM(point) + 1.5 m.
2. **Earth curvature:** effective radius `a_e = k·6371 km` with `k = 4/3`, applied as in P.526: intermediate heights are
   raised by the bulge `+ d_i·(d − d_i)/(2·a_e)` (equivalently `+500·C_e·d_i(d−d_i)` in P.526 units).
3. **Bullington, actual profile** (ITU-R P.526-16 §4.5.3, eqs. 41–51). With λ = c/f:
   - `S_tim = max_i (h_i + C_e·d_i·(d − d_i) − h_ts)/d_i` over interior samples, and `S_tr = (h_rs − h_ts)/d`.
   - If `S_tim < S_tr` (line of sight): `ν_max = max_i ((h_i + C_e·d_i(d−d_i)) − (h_ts(d−d_i) + h_rs·d_i)/d) · sqrt(0.002·d/(λ·d_i·(d−d_i)))`,
     and `L_uc = J(ν_max)`.
   - Otherwise compute `S_rim` from the rx side, the Bullington point `d_b = (h_rs − h_ts + S_rim·d)/(S_tim + S_rim)`, then
     `ν_b = (h_ts + S_tim·d_b − (h_ts(d − d_b) + h_rs·d_b)/d) · sqrt(0.002·d/(λ·d_b(d − d_b)))`, and `L_uc = J(ν_b)`.
   - `J(ν) = 6.9 + 20·log10(sqrt((ν−0.1)² + 1) + ν − 0.1)` for ν > −0.78, else 0.
   - Distances in km where P.526 uses km. `C_e = 1/a_e`. Follow the recommendation's units exactly and cite equation
     numbers in comments.
   - `L_bulla = L_uc + (1 − exp(−L_uc/6))·(10 + 0.02·d_km)`.
4. **Reference profile:** the straight ground line between the tx ground elevation `g_t = DEM(site)` and the rx
   ground elevation `g_r = DEM(point)`, i.e. interior heights `g_t + (g_r − g_t)·d_i/d`. Use the same terminal heights
   (`g_t + h_tx`, `g_r + 1.5`) and the same earth-curvature term. Compute the uncorrected knife-edge loss `L_uc_ref` with
   the rule-3 construction on this profile.
5. **Terrain excess loss** `L_terr = max(0, L_uc_actual − L_uc_ref)`, where `L_uc_actual` is the rule-3 `L_uc` on the real
   profile. The eq. 49 empirical correction `(1 − exp(−L_uc/6))(10 + 0.02·d)` is **not** applied to either.
   - The quick models already include the ground and clutter effects near the terminals of a path over gently sloping
     ground. So only terrain protruding above the terminal-to-terminal ground line adds loss, and the reference cancels
     near-terminal Fresnel effects.
   - The tile model becomes `RSRP = eirpRe − A − PL_quick − L_terr`. This is a deliberate hybrid with the quick models
     (recorded as a spec update). It replaces the spec's full P.1812, because it is continuous with M2 predictions and
     avoids double-counting clutter. (Amended 2026-10-23 after the reference showed the smooth-earth/eq.-49 form
     double-counted 6–10 dB in valleys and saturated the cap.)
6. **Polar table per (site, frequency key):**
   - 360 radials at bearings 0..359 (true north) and 256 range bins at `r_j = 50 m · (80 000/50)^(j/255)`.
   - Each value is `L_terr` quantised to uint8 at 0.25 dB (cap 63.75 dB).
   - The renderer interpolates bilinearly in bearing (wrapped) and `ln r`. At r < 50 m the value is 0.
   - For r beyond the last bin, or points whose profile leaves the fetched chunks, the value is 0 (quick model), and
     the tile is labelled "Terrain" only if ≥ 95% of its covered pixels had full profiles.
   - Table key: (site, frequency rounded to 10 MHz, antenna height AGL rounded to 1 m). Carriers sharing a key share a
     table, and λ uses the key frequency.
7. **Variability:** σ is unchanged from M2. Probability, legend and codes are unchanged.

## Review Focus

1. **Missing or partial elevation data** (ocean 404, network drop mid-fetch, CDN version change) → never a corrupt
   chunk, and tiles fall back to the quick model and are labelled as such. *Task 2.*
2. **Terrain makes coverage stronger anywhere** → impossible: `L_terr ≥ 0`, so prediction never exceeds the M2 quick
   model, and the renderer's branch-and-bound stays sound. *Task 4.*
3. **Table builds swamping the phone** (hundreds of sites) → builds run in the bounded render pool by viewport priority,
   cancellable, cached on disk, and the UI stays at 120 fps. *Task 5.*
4. **Cache full or storage low** → LRU eviction never removes pinned chunks or chunks in use; fetches pause when
   storage is low. *Task 2.*
5. **Data versions:** a tower update or a new terrain chunk invalidates exactly the affected tiles and tables, and never
   mixes versions. *Task 5.*

---

### Task 1: Python terrain reference and vectors
**Files:** `tools/engine/terrain_reference.py`, `tools/engine/make_terrain_vectors.py`,
`engine/host/test/vectors/terrain/{profiles.csv,tables.json,tile_canberra_terrain_z12.json}`, README section.

**Produces:**
- `profiles.csv` (`case,f_mhz,d_km,h_tx_asl,h_rx_asl,heights(space-separated at 90 m),l_bulla,l_bulls,l_terr`) for:
  flat, a single ridge (matching the knife-edge J(ν) hand value), a double ridge, a valley, LOS over a hill (ν < −0.78 → 0),
  a 60 km rural path, and 700 / 1800 / 3500 MHz.
- `tables.json`: two sites' full polar tables over a synthetic DEM.
- One Canberra tile with terrain from a real GLO-90 crop, produced by fetching the needed COG tiles once with
  `python3 -I` + ranged reads. The crop is stored as a small int16 JSON so tests stay offline.

- [ ] Implement Terrain rules 1–6 literally, stdlib only, with a hand-checked single-knife-edge case in the tests.
- [ ] Generate the vectors deterministically and commit `test(engine): terrain reference and vectors`.

### Task 2: `:core:geo` — COG reader, elevation chunk cache, fetcher
**Files:** `core/geo/` (Android library `au.signalatlas.core.geo`):
`CogReader.kt` (IFD parse, TileOffsets/ByteCounts, Inflater, predictor 3 float decode), `DemChunk.kt`, `DemChunkStore.kt`
(LRU, budget, pins, CRC), `DemFetcher.kt` (which COG tiles and internal tiles a chunk needs; ranged GETs; 404 → ocean),
`GeoPrefs.kt`. Tests: a synthetic COG written by a test helper; a real-data test gated on `GEO_LIVE=1`.

**Produces:**
```kotlin
class DemChunkStore(dir: File, budgetBytes: () -> Long) { fun get(latIdx: Int, lonIdx: Int): DemChunk?; fun has(...): Boolean; fun pin(key: String, chunks: Set<ChunkId>); fun unpin(key: String); val bytesUsed: StateFlow<Long>; fun clear() }
class DemFetcher(http: OkHttpClient, store: DemChunkStore) { suspend fun estimateBytes(chunks: Set<ChunkId>): Long; suspend fun fetch(chunks: Set<ChunkId>, allowMetered: Boolean, progress: (Float) -> Unit) }
data class ChunkId(val latIdx: Int, val lonIdx: Int); object ChunkMath { fun chunksFor(bounds: GeoBounds, marginKm: Double): Set<ChunkId> }
```
- [ ] TDD: COG decode equals the source floats exactly; chunk resampling to 3″ int16 matches a Python check; a missing
  tile becomes an ocean chunk; an interrupted fetch leaves no chunk; LRU respects pins and in-use chunks; a CRC failure
  is treated as a miss.
- [ ] Commit `feat(geo): COG range reader and elevation chunk cache`.

### Task 3: Engine terrain stage (C++)
**Files:** `engine/src/main/cpp/terrain/{dem_grid.h,profile.h,bullington.h,bullington.cpp,polar_table.h,polar_table.cpp}`; tests and bench.

**Produces:**
```cpp
struct DemGrid { double south, west; double stepDeg /*1/1200*/; int32_t rows, cols; const int16_t* h; }; // mosaic of chunks
float bullingtonExcessDb(const float* heights, int32_t n, float stepM, float totalDistM, float gTx, float gRx, float hTxAgl, float hRxAgl, float freqMhz); // rules 2–5
int32_t buildPolarTable(const DemGrid&, double siteLat, double siteLon, float hTxAgl, float freqMhz, uint8_t* out /*360*256*/, uint8_t* coverageMask /*360*256 bits: profile complete*/, const volatile int32_t* cancel);
```
- [ ] TDD against `profiles.csv` (±0.05 dB) and `tables.json` (±1 code).
- [ ] Bench: one table over an 80 km radius must take < 150 ms single-threaded on the host. Use incremental tx-side
  maxima and reuse profile samples across bins on a radial.
- [ ] Commit `feat(engine): terrain excess loss and polar tables`.

### Task 4: Renderer + JNI integration
- `CarrierArrays` gains `terrainTable` (a pointer per carrier into a table pool, null = none) plus its coverage mask.
  The renderer subtracts the interpolated `L_terr` (rule 6) per pixel.
- The branch-and-bound bound keeps `L_terr = 0`, which stays a valid upper bound (Review Focus 2).
- The parity test is extended with terrain tables.
- Kotlin: `CoverageEngine.buildTerrainTable(dem: DemGridHandle, site…, freq, cancel): TerrainTable?`; `DemGridHandle`
  wraps a direct buffer mosaic assembled from chunks.
- Tile vector parity holds against `tile_canberra_terrain_z12.json`.
- [ ] Commit `feat(engine): terrain-aware tile rendering`.

### Task 5: Coverage service integration
- Per region: assemble the DEM mosaic from cached chunks (bounds + 40 km), and build tables for the region's sites and
  frequency keys lazily, by viewport priority, in the RenderPool. Tables are stored on disk at
  `cache/terrain/<towerStamp>/<siteId>_<fkey>.tbl` with a CRC, under a 128 MiB LRU.
- **TileKey** gains `terrainVersion` (a hash of the chunk set used plus the table set complete/incomplete).
- **Accuracy level per tile:** `QUICK` or `TERRAIN`, exposed through the service as
  `accuracyFor(visibleTiles): StateFlow<Accuracy>` (TERRAIN only when every visible tile is).
- **Auto-cache:** when the map settles on an area at z ≥ 10 and the network is unmetered, fetch the chunks for the
  viewport + 40 km in the background.
- **`increaseResolution()`:** estimate, then fetch on any network after confirmation. It returns progress.
- [ ] TDD with fakes: tile keys change when chunks arrive; no version mixing; cancellation on pan; tables built once.
- [ ] Commit `feat(coverage): terrain-aware tiles, auto-cache and increase resolution`.

### Task 6: UI — accuracy chip, Increase resolution, Storage
- **Chip** (top, near the zoom chip): "Quick estimate" or "Terrain". Tapping "Quick estimate" opens the sheet: "Increase
  resolution · about N MB" with a button, "Available when online" when offline, and a progress bar while fetching.
- **Settings → Storage:** rows for "Tower data", "Antenna patterns", "Terrain", "Coverage cache", each with its size and
  "Clear" (towers and patterns cannot be cleared, they are only shown). Plus the terrain budget picker
  (0.5 / 1 / 2 / 4 GB).
- ViewModel and Compose tests for the strings and states.
- [ ] Commit `feat(app): accuracy chip, increase resolution and storage screen`.

### Task 7: Verification (emulator, real data)
- Release APK. Canberra / Brindabella Ranges z11 and Blue Mountains (Katoomba) z11 Telstra coverage, before and after
  "Increase resolution". Screenshots, fetch sizes and times, table build times, render times, native heap.
- Plausibility: the valley shadows behind ridges look right.
- `docs/verification/m3a.md`, commit `docs: M3a verification results`.
