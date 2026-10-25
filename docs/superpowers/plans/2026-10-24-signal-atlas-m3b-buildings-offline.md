# Signal Atlas — Milestone 3b: Buildings, Consistent Terrain, Offline Packs, Road Look-ahead

> Fast mode: one builder per batch, no per-task reviews, one combined review later.

**Goal:** City predictions account for buildings. Terrain is applied consistently everywhere, including dense CBDs.
Whole areas can be downloaded for offline use (base map, terrain, buildings). Trip mode's look-ahead follows the real
road.

## Batch 1: consistent terrain, plus building data and model (core only; no app UI)
- **Global terrain tables (fixes the CBD seams left over from M2/M3a):**
  - Terrain tables are owned per carrier key **globally** (an LRU across regions), not per region. Any region that needs a
    key reuses the same table.
  - Demand is contender-aware: carriers that are best server for ≥ 1% of covered pixels in any visible tile get tables
    first.
  - A region's TERRAIN label counts only carriers that are actually serving.
  - A carrier without a table pays no terrain loss only in QUICK-labelled tiles. In TERRAIN or PARTIAL tiles, an
    untabled serving carrier forces that pixel to "incomplete". So no tile mixes terrain and no-terrain for the same
    serving carrier across a border.
  - Add an edge-continuity test across a z10 border with terrain.
- **Buildings data (`:core:geo`):**
  - Fetch Microsoft Global ML Building Footprints per z9 quadkey (`dataset-links.csv`, gz GeoJSON lines; height −1 =
    unknown).
  - Rasterise to a **building grid per 0.01° cell** (about 1 km): building coverage fraction and mean height (unknown
    height → 6 m, or 3 m per level if tagged).
  - Store compactly in `geo/bld/` chunks matching the DEM chunk layout (0.25°). Same fetch, cache, LRU and pin rules as
    the DEM (no account, ranged/whole-file gz).
- **Receiver environment model (the engine and the Python reference change together):**
  - The receiver-end correction uses a **per-pixel environment class** from the building grid, interpolated
    bilinearly between cell centres so it is continuous (no tile seams):
    - coverage fraction ≥ 0.35 and mean height ≥ 15 m → DENSE_URBAN
    - ≥ 0.2 → URBAN
    - ≥ 0.05 → SUBURBAN
    - else RURAL
  - It applies only to the Hata `a(h_m)` and the SUBURBAN/RURAL corrections and C_m (the terms that describe the
    receiver's surroundings).
  - The per-carrier area class (M2 rule 5 amended 2026-10-24) still chooses the 38.901 scenario.
  - Where building data is missing, the per-carrier class is used, so the behaviour is unchanged.
  - Indoor O2I loss is unchanged.
  - Tile keys include the buildings data version.

## Batch 2: offline region packs (app + core)
- **Pack catalogue:** each state/territory, the major metros (Sydney, Melbourne, Brisbane, Perth, Adelaide, Canberra,
  Hobart, Darwin) and the major highway corridors (Hume, Pacific, Bruce, Stuart, Princes) as bounding boxes or
  corridors. Plus "Draw a box on the map".
- **Download:** the base map via MapLibre `OfflineManager` (the pmtiles style region, z0–14), DEM chunks, building chunks,
  and the road network for the corridor/box (batch 3). It shows the size before starting and runs on Wi-Fi in the
  background (WorkManager), resumable, with progress. Packs pin their chunks.
- **Screen:** Offline regions (list, size, update, delete), reachable from the top-right menu.

## Batch 3: road-following look-ahead (trip)
- **Road network:** decode Protomaps v4 `roads` features from PMTiles (HTTP range reads, cached; packs pre-fetch) with a
  small MVT/protobuf decoder in Kotlin. Build a local road graph around the vehicle (z14 tiles within 15 km).
- **Matching:** map-match the position to the nearest road edge by heading and distance. At junctions, prefer the
  continuation with the smallest turn and the higher road class. The look-ahead follows the road for the window plus
  10 km, falling back to M5's arc extrapolation when no road matches.
- **Tests:** synthetic graphs (a bend, a junction, a roundabout-like loop) and a real decoded tile fixture.

## Batch 4: APK + smoke
- Emulator smoke tests: buildings change urban predictions plausibly; a pack downloads and works offline (airplane
  mode); a trip on a winding road follows the road. Deliver an APK.
