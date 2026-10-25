# Coverage model reference

Python reference for the Signal Atlas "Coverage model" rules 1-9. The C++ engine (`engine/src/main/cpp`)
must reproduce its numbers. Stdlib only (`math`); run with `python3 -I`.

- `reference.py`: the rules as pure functions (`eirp_per_re`, `antenna_attenuation`, `path_loss`,
  `o2i_low_loss_db`, `coverage_probability`, `max_range_km`, codes, geometry) and `render_tile`.
- `make_vectors.py <rrl_fixture.zip> <out_dir>`: writes the vectors below. Takes about 70 s (most of it the
  fringe-tile search).
- `test_reference.py`: unit checks, including the sanity values.

## Regenerate

```
python3 -I tools/engine/test_reference.py
python3 -I tools/engine/make_vectors.py core/acma/src/test/resources/acma/fixture.zip engine/host/test/vectors
```

The output is byte-identical on regeneration.

## Vectors (`engine/host/test/vectors`)

Floats have 6 decimals. In CSVs, `nan` means "not supplied" (NaN in `AntennaParams`). Lines end in LF.

| File | Columns / content |
|---|---|
| `pathloss.csv` | `f_mhz,d_km,h_tx,area,model,pl_db,sigma_db`. f ∈ {700, 900, 1800, 2100, 2600, 3500, 26000}, d (3D km) ∈ {0.05, 0.3, 1, 3, 10, 30}, h_tx ∈ {10, 30, 60}, all 4 areas (504 rows). `model` is HATA, COST231, UMA, RMA or UMI. |
| `antenna.csv` | `bearing,elev_deg,az,hbw,fb,gain,tilt,h_tx,d_m,a_db,f_mhz,hrp_id,hrp_peak`. `a_db` = `antennaAttenuation(params, bearing, d_m)`; `elev_deg` is the computed depression angle (informational). `d_m` is the horizontal distance. `hrp_id` 0 = no HRP, else a row of `patterns.csv`. Covers sectored/omni, default antenna fields, three tilts, the height bands, height clamping, the +2° mm-wave tilt, and HRP bearing rounding/wrap. |
| `patterns.csv` | `id,att`: two synthetic relative patterns, 360 space-separated quantised values (0.25 dB). |
| `eirp_re.csv` | `tech,f_mhz,bw_hz,eirp_dbm,eirp_re_dbm`: every table bandwidth, non-table bandwidths (fallback formula, minimum 6), bandwidth 0, and the SCS frequency edges. |
| `tile_canberra_z13.json` | One rendered tile (below). |
| `tile_canberra_z13_urban_indoor_hi.json` | The Canberra tile, URBAN, indoor, > 2200 MHz carriers only (UMa) (below). |
| `tile_canberra_z13_urban_indoor_mid.json` | The same, 1500–2200 MHz carriers only (COST-231) (below). |
| `tile_canberra_z13_carrier_area.json` | The Canberra tile, TELSTRA + OPTUS, with a `carrierArea` column (rule 5 as amended 2026-10-24: each carrier's class from the sites within 3 km of its own site, as an index into `AREAS`); the tile's own `area` (RURAL) is then only the fallback, and no carrier has it, so the vector fails if the engine ignores the column. |
| `tile_edge_z12.json` | A fringe tile, same format (below). |

### `tile_canberra_z13.json`

The z13 tile containing (−35.2809, 149.1300): **z13/7489/4954**, grid 128 (bilinear to 256). Carriers are
every TELSTRA carrier of the M1 fixture (`core/acma/src/test/resources/acma/fixture.zip`, through
`tools/acma/reference.py`), ordered by site id then SDD id, with HRP absent. Fields:

- `z, x, y, grid, area, sitesWithin3km, indoor, thrDbm, selection, patterns` (empty), `evaluated` (carriers that pass culling).
  `area` is an **input to the renderer** (`RenderParams.area`); the C++ engine never derives it. Kotlin
  computes it with rule 5, and a vector may force it (`selection.areaForced`, with `selection.derivedArea`).
- `carriers`: structure of arrays, `n` entries each. The `CarrierArrays` fields `lat, lon, hTx, az, tilt, eirpRe,
  freqMhz, hBw, fb, gain, rMaxKm, tech, sectorKey, freqKey, hrpId, hrpPeak`, plus `eirpDbm, bwHz` (so `eirpRe`
  is reproducible) and `sddId, band` (for tracing). `null` = NaN.
  - `hTx` is the stored height. The model clamps it to ≥ 3 m where it is used.
  - `tech`: 0 LTE, 1 NR. `sectorKey`: the sector id from the reference importer (first-seen order in the export).
  - `freqKey` = `networkOrdinal·1 000 000 + round(freq_hz / 100 000)`, i.e. network plus frequency in 0.1 MHz.
  - `rMaxKm` = `culling_range_km(eirpRe, freqMhz, hTx)`: the maximum of `max_range_km` over all four area
    classes (rule 9), computed once per carrier and independent of the tile's area. RURAL alone is not enough:
    a 3.5 GHz carrier at h 3 m with eirpRe 45 reaches 12.9 km in URBAN (UMa) but only 3.9 km in RURAL (RMa).
- `rsrpCodes, sinrCodes, probCodes, best`: 65536 values each, row-major (row 0 is the north edge). `best` is
  the carrier index, or 65535.

Every float in `carriers` is rounded to 6 decimals *before* rendering, so the JSON holds exactly the inputs
the reference used. Area: no fixture site lies within 3 km of the tile centre (the nearest is 3.69 km away),
so the tile is **RURAL** (Okumura-Hata/COST-231 rural and RMa). All 109 carriers pass culling; every pixel
is covered (RSRP codes 69..113).

### `tile_canberra_z13_urban_indoor_hi.json` and `_mid.json`

These are the same tile, rendered with `area` forced to **URBAN** (its derived class is RURAL) and
`indoor = true` (O2I low loss, σ in quadrature). Each is limited to one path-loss family, so that model
decides the outputs. Carriers come from TELSTRA and OPTUS; B40 is OPTUS-only in the fixture.
`selection.families` records the family.

| File | Family / model | Carriers | Covered pixels | Best server |
|---|---|---|---|---|
| `_hi` | > 2200 MHz, UMa | 59 (n78, B7, B40) | 24 960 / 65 536, RSRP codes 0..32 | B7 22 769, n78 2 191 |
| `_mid` | 1500–2200 MHz, COST-231 | 81 (B1, B3) | 36 587 / 65 536, RSRP codes 0..28 | B3 34 715, B1 1 872 |

`make_vectors.py` checks that the best server comes from the intended family on ≥ 90% of covered pixels
(here 100%, since the selection is filtered). Both tiles also exercise code 0 / `best` 65535 inside the
coverage area. The B28-dominated outdoor tile `tile_canberra_z13.json` is unchanged.

### `tile_edge_z12.json`

The same format, the same TELSTRA fixture carriers, and the same area rule. `make_vectors.py` picks the tile
by searching outward from the z12 tile containing (−35.2809, 149.1300), which is z12/3744/2477:

- It goes ring by ring (Chebyshev distance), then by (dy, dx) within each ring.
- A tile is screened first on a 16-point grid, accepted if 25–75% of pixels are at code 0.
- It must then show 20–80% code 0 on the full render.

**Picked: z12/3738/2470** (ring 7, centre −34.7777, 148.5791, about 76.7 km north-west of the Canberra sites).

- The area is RURAL, grid 128, and 15 of the 109 carriers pass culling.
- 44 880 of 65 536 pixels (68.5%) have RSRP code 0 and `best` 65535. These are exactly the same pixels.
- The other 20 656 pixels are covered at codes 1–2, all served by carrier 74 (B28 778 MHz, azimuth 320°,
  `rMaxKm` 80). The edge is the natural −140 dBm contour, not the culling cap.
- This tile exercises the code 0/1 boundary, the no-coverage `best`, and partial culling.

Sanity values: Hata 900 MHz / 1 km / h 30 / URBAN = 126.403286 dB. LTE 20 MHz `eirpRe` = eirp − 30.791812.
NR 100 MHz at 3.5 GHz = eirp − 35.153439.

## Interpretation notes (the C++ engine must match)

**General.**
- Integer codes use `floor(x + 0.5)`.
- `h_tx` is clamped to ≥ 3 m (rule 1) in the antenna, path-loss and distance calculations.
- A missing antenna field uses its default: H_BW 65°, F/B 25 dB, gain 15 dBi.

**EIRP per RE (rule 2).**
- Table lookup needs an exact Hz match (1.4 MHz = 1 400 000 Hz). Any other bandwidth uses
  `max(6, floor(BW·0.9 / (12·SCS)))`.
- SCS is 15 kHz for LTE. For NR it is 15 kHz below 2300 MHz, 120 kHz from 24250 MHz, and 30 kHz otherwise
  (so 7125–24250 MHz uses 30 kHz).

**Antenna (rules 3–4).**
- Δφ = `((bearing − az + 180) mod 360) − 180`.
- With an HRP the index is `(floor(bearing + 0.5) − peakBearing) mod 360`, and A_H = `att·0.25`. The HRP is
  used even when the sector is omni.
- The default-tilt height bands are: h < 15 → 6°, 15 ≤ h < 30 → 4°, 30 ≤ h ≤ 50 → 3°, h > 50 → 2°.
- Downtilt (Ruling CC): a non-zero ACMA tilt gives `min(|tilt|, 15)`, because ACMA uses both signs for
  downtilt. The +2° for f ≥ 24.25 GHz applies only to the default (height-table) tilt.
- θ = `atan2(h − 1.5, d2D)`, using the horizontal distance.

**Path loss (rule 6).**
- `d` is the 3D distance, floored at 0.02 km.
- Hata and COST-231 use `d` (km) directly, with `h_b = clamp(h, 3, 200)`.
- For TR 38.901 the 2D distance is recovered as `d2D = sqrt(max(d3D² − (h − 1.5)², 0))`. It is clamped
  to ≥ 10 m (the validity lower bound), and then `d3D` is recomputed from the clamped `d2D`.
- Beyond the upper validity bounds (UMa 5 km, UMi 5 km, RMa 10 km), the outermost formula is
  extrapolated (no clamp). PL1 is used when `d2D ≤ d_BP`, otherwise PL2.
- The LOS probability uses `d2D`. The blend is in the dB domain (Ruling DD):
  `PL = P·PL_LOS + (1 − P)·PL_NLOS` and `σ = P·σ_LOS + (1 − P)·σ_NLOS`. For example, UMa 3.5 GHz at 3 km
  and h 30 gives P = 0.006, PL 160.107 dB (PL'NLOS 160.307 dB) and σ 5.988 dB. The power-domain blend gave
  148.9 dB.
- c = 3.0·10⁸ m/s, and f_c is in GHz (in Hz inside d_BP).

**TR 38.901 forms used** (Table 7.4.1-1, from the published equations; h_UT = 1.5 and h_E = 1 m for
`h' = h − h_E`, which is exact here: note 1 gives h_E = 1 m with probability 1/(1 + C), and C(d2D, h_UT) = 0 for
h_UT < 13 m):

- **UMa.**
  - d'BP = 4·h'BS·h'UT·fc/c.
  - PL1 = 28.0 + 22 log10(d3D) + 20 log10(fc).
  - PL2 = 28.0 + 40 log10(d3D) + 20 log10(fc) − 9 log10(d'BP² + (hBS − hUT)²).
  - PL'NLOS = 13.54 + 39.08 log10(d3D) + 20 log10(fc) − 0.6(hUT − 1.5).
  - σ: LOS 4, NLOS 6.
- **RMa** (h = 5, W = 20).
  - d_BP = 2π·hBS·hUT·fc/c (actual heights).
  - PL1 = 20 log10(40π·d3D·fc/3) + min(0.03h^1.72, 10) log10(d3D) − min(0.044h^1.72, 14.77) + 0.002 log10(h)·d3D.
  - PL2 = PL1(d_BP) + 40 log10(d3D/d_BP).
  - PL'NLOS = 161.04 − 7.1 log10(W) + 7.5 log10(h) − (24.37 − 3.7(h/hBS)²) log10(hBS)
    + (43.42 − 3.1 log10(hBS))(log10(d3D) − 3) + 20 log10(fc) − (3.2 (log10(11.75 hUT))² − 4.97).
  - σ: LOS 4, NLOS 8 (rule 6; the table also gives 6 for PL2, which is not used).
- **UMi-Street Canyon.**
  - d'BP as for UMa.
  - PL1 = 32.4 + 21 log10(d3D) + 20 log10(fc).
  - PL2 = 32.4 + 40 log10(d3D) + 20 log10(fc) − 9.5 log10(d'BP² + (hBS − hUT)²).
  - PL'NLOS = 35.3 log10(d3D) + 22.4 + 21.3 log10(fc) − 0.3(hUT − 1.5).
  - σ: LOS 4, NLOS 7.82.
- In every scenario, PL_NLOS = max(PL_LOS, PL'NLOS).
- **LOS probability** (Table 7.4.2-1):
  - UMa (h_UT ≤ 13 m, so C' = 0): 1 for d2D ≤ 18 m, else `18/d + exp(−d/63)(1 − 18/d)`.
  - RMa and UMi: as in rule 6.

**Indoor (rule 7).** PL += `o2i_low_loss_db(f)`, and σ = `sqrt(σ² + 4.4²)` (Ruling EE, in quadrature).

**Tile (rules 8–9).**
- Sample point (i, j) of an N grid has its centre at tile coordinates (x + (i+0.5)/N, y + (j+0.5)/N) in
  Web Mercator.
- `d2D` is the haversine distance (R = 6 371 008.8 m), and d3D = `sqrt(d2D² + (h − 1.5)²)`. The bearing
  is the initial great-circle bearing from the site, in [0, 360).
- RSRP = eirpRe − A − PL. Best server: the highest RSRP; ties go to the lower carrier index.
- **SINR.** Interferers are the carriers with the best server's `freqKey` but a different `sectorKey`,
  with load 0.5. N uses the best server's SCS.
- **Probability** uses the best server's blended σ.
- **No carrier evaluated at a sample:** RSRP −200 dBm (code 0), SINR −20 dB (code 0), P = 0, best 65535.
- **Grid 128 to 256.** Output pixel p maps to grid coordinate g = (p + 0.5)/2 − 0.5, clamped to
  [0, 127], with i0 = floor(g), i1 = min(i0 + 1, 127).
  - RSRP (dBm), SINR (dB) and P are interpolated bilinearly before quantisation.
  - `best` takes the nearest sample (`floor(g + 0.5)` = p div 2), and is 65535 wherever the RSRP code is 0.
- **Culling.** A carrier is evaluated when the haversine distance from the site to the nearest point of
  the tile's lat/lon box (the site clamped into the box) is ≤ `rMaxKm`.
- **`max_range_km`.**
  - The search runs on the 3D distance, with A = 0, outdoors, for one area class. R_max
    (`culling_range_km`) is the maximum over the four classes.
  - It returns 80 when `eirpRe − PL(80 km) ≥ −140`, and 0 when the margin is negative at 0.02 km.
  - Otherwise it runs 60 bisection steps on [0.02, 80] and returns the last distance with a margin ≥ 0.

# Terrain model reference

Python reference for the "Terrain model" rules 1–6 (M3a). The C++ terrain stage (`engine/src/main/cpp/terrain`)
must reproduce its numbers. Stdlib only; run with `python3 -I`.

- `terrain_reference.py`: profiles (rule 1), Bullington L_uc over the actual profile and over the terminal
  ground line (rules 2–4, Ruling B3), the excess loss (rule 5), polar tables and the renderer lookup (rule 6),
  and `DemGrid` (bilinear heights).
- `glo90_crop.py`: a minimal COG reader (classic/BigTIFF IFD, TileOffsets/TileByteCounts, zlib inflate,
  floating-point predictor 3) over HTTP range requests, every request of a COG pinned with `If-Match` on the
  ETag of its first response (a 412 or a changed ETag restarts the crop). `tags <name>` prints one COG's IFD.
- `make_terrain_vectors.py`: `fetch-dem <out_dir>` fetches the Canberra crop once (network, ~30 s);
  `<rrl_fixture.zip> <out_dir>` writes the vectors offline from the committed crop (~6 min).
- `test_terrain_reference.py`: unit checks, including the hand-checked knife edge.
- `reference.py` `render_tile(..., terrain=fn)` subtracts `L_terr` per carrier (rule 5); without `terrain` the
  M2 output is unchanged (the M2 vectors regenerate byte-identical).

## Regenerate

```
python3 -I tools/engine/test_terrain_reference.py
python3 -I tools/engine/make_terrain_vectors.py core/acma/src/test/resources/acma/fixture.zip engine/host/test/vectors/terrain
# only to refresh the real-data crop (network):
python3 -I tools/engine/make_terrain_vectors.py fetch-dem engine/host/test/vectors/terrain
```

The output is byte-identical on regeneration.

## Equation forms and units

The ITU-R P.526-16 PDF could not be fetched (ITU returned 404 for the known URL patterns), so the forms are the
published Bullington equations as they appear in P.526 §4.5 (identical in P.452-17 §4.2.1 and P.1812-6
§4.3.1), with the equation numbers the plan assigns (§4.5.3 eqs. 41–48). Ruling B3 replaced the smooth-earth
reference (eqs. 52–55) and dropped the eq. 49 correction. Units exactly as in the recommendation: d, d_i in **km**, heights in **m** above sea level, λ in
**m** (λ = 3·10⁸ / f), C_e = 1/a_e in **km⁻¹** with a_e = (4/3)·6371 km.

- J(ν) = 6.9 + 20 log10(√((ν − 0.1)² + 1) + ν − 0.1) for ν > −0.78, else 0 (eq. 31).
- Interior points only (i = 2…n−1) in every max; a profile with no interior point has L = 0.
- S_tim = max (h_i + 500·C_e·d_i(d − d_i) − h_ts)/d_i; S_tr = (h_rs − h_ts)/d (eqs. 41–42).
- S_tim < S_tr: ν_max = max (h_i + 500·C_e·d_i(d − d_i) − (h_ts(d − d_i) + h_rs·d_i)/d)·√(0.002·d/(λ·d_i(d − d_i))),
  L_uc = J(ν_max) (eqs. 43–44).
- Otherwise S_rim = max (h_i + 500·C_e·d_i(d − d_i) − h_rs)/(d − d_i), d_b = (h_rs − h_ts + S_rim·d)/(S_tim + S_rim),
  ν_b = (h_ts + S_tim·d_b − (h_ts(d − d_b) + h_rs·d_b)/d)·√(0.002·d/(λ·d_b(d − d_b))), L_uc = J(ν_b) (eqs. 45–48).
  Guard: S_tim + S_rim ≤ 0 (exact grazing, 0/0) uses the line-of-sight branch (ν = 0 there).
- **No** eq. 49 correction (1 − exp(−L_uc/6))(10 + 0.02·d) on either profile (Ruling B3).
- Reference profile (rule 4): interior heights on the straight ground line g_t + (g_r − g_t)·d_i/d, with
  g_t = DEM(site), g_r = DEM(point), the same terminals h_ts = g_t + h_tx, h_rs = g_r + 1.5 and the same
  curvature term. L_terr = max(0, L_uc(actual) − L_uc(reference)) (rule 5). Flat or uniformly sloped ground
  gives 0 (up to rounding), and so does any profile that never rises above the ground line (valleys), since
  L_uc is non-decreasing in every interior height; the near-terminal Fresnel loss of a 1.5 m receiver is in
  both and cancels.

## Interpretation notes (the C++ engine must match)

- **Curvature.** Interior heights are raised by the bulge h_i + 500·C_e·d_i(d − d_i) (km in, m out), as in
  P.526 (rule 2 as amended by B3).
- **Profile.** Samples at 90·i m for every i with 90·i < d, then the endpoint d (the last step is shorter;
  `profile_distances_m`). Heights are bilinear in lat/lon from the DEM nodes; any sample without DEM makes the
  profile incomplete. h_ts = DEM(site) + the height key (below), h_rs = DEM(point) + 1.5. Only interior samples
  enter the maxima; the terminals' ground is g_t, g_r (equal to the first and last profile heights).
- **Great circle.** A point's profile uses spherical interpolation from the site toward the point (sphere
  R = 6 371 008.8 m, haversine distance). For tables, sample i of radial b is the destination point at 90·i m on
  bearing b, and cell (b, j) ends at the destination at r_j: the same points (differences < 1e-9°), so a radial's
  samples are shared by all its bins.
- **DEM grid.** Node (row, col) at latitude (southIndex + row)/1200 and longitude (westIndex + col)/1200; row 0
  is the south edge; int16 metres little-endian, row-major. Outside [0, rows−1] × [0, cols−1] (fractional
  index) there is no DEM; the last row/column use the cell below/left of them.
- **Tables.** `codes[b·256 + j]` = quantise(L_terr) = min(255, floor(L/0.25 + 0.5)) on bearing b (true) at
  r_j = 50·1600^(j/255) m. Mask bit k = `mask[k >> 3] >> (k & 7) & 1` (LSB first), 1 = every sample including the
  endpoint had DEM; incomplete cells hold 0. A site without DEM gives an all-incomplete table.
- **Lookup.** u = ln(r/50)/ln(1600)·255, j0 = min(floor(u), 255), j1 = min(j0 + 1, 255); b0 = floor(bearing) mod 360,
  b1 = (b0 + 1) mod 360; bilinear in (bearing, u) of code·0.25. r < 50 m → (0, complete); r > 80 km → (0,
  incomplete); any of the four cells incomplete → (0, incomplete). The renderer uses the haversine d2D and
  initial bearing it already computes (M2 rule 8).
- **Table key** (rule 6, B3; coarsened by Ruling UU so dense city centres fit the 128-table region cap): (site,
  3GPP band centre frequency, antenna height AGL clamped to ≥ 3 m then rounded half up to 2 m). The band centre is
  the midpoint of the band's DL range (B28 → 780.5 MHz, n78 → 3550 MHz; `BandPlan.centreMhz`); a frequency outside
  every known band keeps the old key (rounded half up to 10 MHz). The table is built with the key height and
  λ = c / key frequency (so ≤ ~1 dB diffraction error against the carrier's own λ). The key lives on the Kotlin
  coverage side (`TerrainKeys`); `polar_table` and `height_key_m` here take/produce whatever the caller keys on, and
  the vectors still use the 10 MHz / 1 m keys. Disk tables carry format version 2: version-1 files are ignored.
- **Tile accuracy.** A pixel counts as terrain-complete when its best server (the nearest grid sample, as for
  `best`) had a complete lookup; `accuracy` is TERRAIN when ≥ 95% of covered pixels (RSRP code > 0) are.

## Vectors (`engine/host/test/vectors/terrain`)

Floats have 6 decimals, lines end in LF.

| File | Content |
|---|---|
| `profiles.csv` | `case,f_mhz,total_dist_m,step_m,g_tx,g_rx,h_tx_agl,h_rx_agl,heights,l_uc_actual,l_uc_ref,l_terr`, matching `bullingtonExcessDb(heights, n, stepM, totalDistM, gTx, gRx, hTxAgl, hRxAgl, freqMhz)`; `heights` are the n ground heights (m, space-separated) at 90·i m while below the total distance, then at the total distance (`profile_distances_m`); `g_tx`/`g_rx` equal the first/last height. Cases flat, single_ridge (the hand-checked knife edge), double_ridge, valley, los_hill (mast to mast, ν_max < −0.78 → all 0), rural_60km (60 km, last step 60 m), each at 700 / 1800 / 3500 MHz. |
| `tables.json` | `dem` (a 361 × 361 synthetic grid, 0.3°, hills, a range and a 0 m lake) and two full tables: A (−35.35, 149.05, 30 m, 1800 MHz) and B (−35.47, 148.93, 45 m, 700 MHz, near the corner so many radials leave the grid). Each has `codes`, `mask` (base64), `complete`, `nonZero`, `maxCode`, `capCells`, `groundM`. |
| `dem_canberra_glo90.json` | The real crop: Copernicus GLO-90 nodes for z12/3744/2477 + 40 km (951 × 1170, 357–1905 m), with each COG's name, ETag and layout. |
| `tile_canberra_terrain_z12.json` | The M2 tile format (TELSTRA fixture carriers, grid 128, area derived = RURAL) rendered with RSRP = M2 − L_terr, plus `terrain`: `dem` (file reference), `carrierTable` (table index per carrier), `tables` (one per key: site, `freqKeyMhz`, `hTxAgl` = the height key, `groundM`, `codes`, `mask` as in `tables.json`), `capCells` (all tables), `coveredPixels`, `terrainPixels`, `accuracy`, `pixelsChangedFromQuick`. |

GLO-90 facts checked with `glo90_crop.py tags` (S35/S36 × E148/E149, 2026-10-23): little-endian classic TIFF,
1200 × 1200 float32, **one 2048 × 2048 internal tile** (so a range read fetches ~3.8 MB of ~5.2 MB), DEFLATE,
predictor 3, no GDAL_NODATA, PixelIsPoint, scale 1/1200°, tiepoint (0, 0) → (west, north) on whole degrees. The
COG nodes are therefore exactly the chunk-grid nodes and the crop needs no interpolation; the node on a
whole-degree latitude is row 0 of the COG to its south. Values are rounded half away from zero; NaN/no-data and
missing (404) COGs give 0.

# Calibration terms and point query (M4)

`reference.py` also holds the M4 engine terms (Calibration rules 4-6 of the M4 plan) and the point query:

- `calibration_db(offset, slope, d_median_km, d2_m)` = `mu + b * log10(clamp(d / d_median, 0.1, 10))`. The ratio
  clamp is an engine interpretation: a slope never extrapolates more than one decade from the sector's median sample
  distance. NaN / None offset = 0; NaN slope or `d_median <= 0` = no slope term. d is the horizontal distance.
- `calibrated_sigma(sigma, scale)` = `max(4, sigma * scale)` for `scale < 1`, else sigma (rule 6; Kotlin passes
  `scale = sqrt(10 / (10 + n))`).
- Residual rasters (rule 5): `render_tile(..., residuals=[(codes, mask), ...])`, 65536 int8 codes of 0.25 dB each,
  added to every carrier whose `network` column bit is set in `mask`. Grid index i of an N grid samples pixel
  `floor((i + 0.5) * 256 / N)` (`residual_pixel`). The raster changes the best server like any other RSRP term.
- `predict_point(carriers, patterns, lat, lon, area, indoor)`: every carrier's RSRP at one point, the renderer's
  per-carrier model without culling (carriers beyond R_max are predicted too; the matcher scores them) and without
  residual rasters.
- Branch and bound: a block's bound adds the calibration term's maximum over the block's distance range (it is
  monotonic in d, so the endpoints suffice) and each applicable raster's maximum over the block's samples, so it
  stays a true upper bound (checked against `debugNoBounds` with random terms and rasters).

Regenerate (13 s; deterministic):

```
python3 -I tools/engine/make_calibration_vectors.py engine/host/test/vectors
```

| File (`engine/host/test/vectors/calibration`) | Content |
|---|---|
| `point_canberra.json` | The Canberra tile carriers plus synthetic `calOffsetDb, calSlope, calDmedKm, calSigmaScale` (every third carrier full terms, the next offset only, the next null) and `network` (i % 2); `points`: 8 points (tile centre to ~170 km away, all four areas, some indoor), each with every carrier's `rsrp`. |
| `tile_canberra_z13_calibrated.json` | The z13 tile rendered with those columns and two `residuals` (base64 int8 codes and `mask`): a smooth +-3 dB raster for network 0 and +10 dB squares for networks 0 and 1. |

## Receiver environment (rule 5a, M3b, 2026-10-24)

`reference.environment_at(grid, lat, lon)` classifies a point from a building grid (dict: `south`, `west`, `step`
0.01, `rows`, `cols`, row-major `cover` codes `round(fraction * 250)` with 255 = no data, `height` codes in 0.5 m),
interpolating fraction and height bilinearly between cell centres. `carrier_rsrp(..., env=)` uses the class in place of
the carrier's own only for f <= 2200 MHz (the Hata / COST-231 receiver terms). `make_vectors.py` writes
`tile_canberra_z13_buildings.json` (the carrier-area tile plus a synthetic grid in `bldSouth`, `bldWest`, `bldRows`,
`bldCols`, `bldCover`, `bldHeight`); the other vectors carry no grid and are unchanged.
