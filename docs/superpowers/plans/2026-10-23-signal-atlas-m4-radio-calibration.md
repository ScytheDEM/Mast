# Signal Atlas — Milestone 4: Radio & Calibration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The app reads the phone's live connection (carrier from the SIM, serving and neighbour cells, band, signal,
timing advance), shows which tower you're on, and learns from real measurements. It corrects each sector's predicted
signal and a fine local grid along the places you go, all on the phone.

**Architecture:**
- **`:core:radio` (new)** wraps `TelephonyManager`/`SubscriptionManager` (API 29–30 `PhoneStateListener`, API 31+
  `TelephonyCallback`) into a `RadioState` flow, and maps channels to frequency/band and SIM codes to networks.
- **`:core:calibration` (new)** matches the serving cell to an ACMA sector, logs throttled samples into
  `calibration.db`, and estimates per-sector offset and slope plus a 100 m residual grid.
- **The engine** gains per-carrier calibration terms, a per-tile residual raster, and a point query used for matching.
- **The app** gains a connected-tower info bar, map highlight, permission flow, and a Calibration screen.

**Tech Stack:** As in M2/M3a. No new libraries.
**Spec:** `docs/superpowers/specs/2026-10-22-signal-atlas-design.md` §6 (calibration), §8 (radio).
**Builds on:** the `m3a-terrain` branch once M3a is complete. Calibration composes with terrain: residuals are measured
against the best available prediction.

## Global Constraints

- **M1–M3a constraints still apply.**
- **Permissions:** `READ_PHONE_STATE` (subscriptions, display info) plus `ACCESS_FINE_LOCATION` (required by
  `getAllCellInfo`).
  - Rationale text: "Signal Atlas reads your phone's mobile network details (carrier, tower, band, signal) to show
    which tower you're using and improve predictions. It never reads calls, messages or phone numbers."
  - Everything degrades gracefully if permission is denied.
- **Calibration:**
  - On by default after the permission is granted. Settings toggle: "Improve predictions with my signal (stays on this
    phone)".
  - Data never leaves the device except through the user's own CSV export.
  - Samples are logged only while the app is in the foreground. Trip-mode logging arrives in M5.
- **Update cadence:** cell info about every 2 s while the map is visible (`requestCellInfoUpdate` on API 29+); stopped in
  the background.

### Radio rules

1. **Network from the SIM** (MCC-MNC of the active data subscription; MVNOs use their host's code):
   - `505-01`, `505-11`, `505-71`, `505-72` → TELSTRA
   - `505-02`, `505-90` → OPTUS
   - `505-03`, `505-06`, `505-12` → TPG
   - Otherwise unknown. The display name comes from `SubscriptionInfo.carrierName`, e.g. "Boost", "amaysim".
   - Roaming (`isNetworkRoaming`) shows "Roaming".
2. **Channel → downlink frequency:**
   - **LTE** (TS 36.101 Table 5.7.3-1): `F_DL = F_DL_low + 0.1·(N_DL − N_Offs-DL)` for bands 1, 3, 5, 7, 8, 26, 28, 40, 42.
     The table is in code with tests per band.
   - **NR** (TS 38.104 §5.4.2.1): `F = F_REF-Offs + ΔF_Global·(N_REF − N_REF-Offs)` with the three standard ranges.
   - **Band:** the API's `bands` where present (API 30+), else derived from the frequency via M1's `BandPlan`.
3. **Timing advance → distance:** LTE `d ≈ TA · 78.12 m` (valid when `0 ≤ TA ≤ 1282`). NR TA is not used in M4.
4. **5G NSA:** an `LTE` serving cell plus `TelephonyDisplayInfo.overrideNetworkType ∈ {NR_NSA, NR_ADVANCED}` shows "5G".
   NR cells from `getAllCellInfo` (registered or not) are listed as NR secondaries when present.

### Calibration rules (shared by `tools/calibration/reference.py` and Kotlin; change both together)

1. **Candidates** for the serving cell:
   - carriers of the serving network whose frequency is within ±0.15 MHz of the channel frequency;
   - on sites within 40 km of the phone fix;
   - fix accuracy must be ≤ 50 m, else no sample.
2. **Match score per candidate** (lower is better):
   `|P̂ − M| + (TA available ? |d − TA_m|/150 m · 3 dB : 0) + (d > R_max ? 100 : 0)`, where `P̂` is the uncalibrated
   predicted RSRP at the fix and `M` the measured RSRP. Best wins.
   - **Confidence:** "Likely" when the best beats the second by ≥ 3 points, else "Uncertain".
   - **Confirmed:** after ≥ 5 Likely matches of the same (network, PCI, channel), or (network, cell ID) where the cell ID
     is available, to the same sector. The learned mapping is persisted and then used directly.
3. **Sample:** stored only when the match is Likely or Confirmed, and throttled to one per 50 m moved or 5 s, whichever
   comes later. Fields: time, lat, lon, accuracy, network, tech, channel, PCI, cell ID, TA, RSRP, RSRQ, SINR, matched
   sddId, sector ID, predicted RSRP (uncalibrated), distance, area class.
4. **Sector offset:** Bayesian normal with prior N(0, 8²) and measurement σ_m = 6 dB, so
   `μ = (Σr/σ_m²)/(1/8² + n/σ_m²)` with r = M − P̂, clamped to ±20 dB.
   - **Slope** (only with n ≥ 30 and max/min distance ≥ 3): ridge regression of r on `log10(d/d_median)` with prior
     slope σ 10 dB/decade.
   - Applied as `+μ + b·log10(d/d_median)` to that sector's carriers.
5. **Residual grid:** cells of 0.001° × 0.001° (about 100 m) per network. Each holds the mean of the residual after the
   sector correction, plus a count. Applied as `w·mean` with `w = n/(n+5)`, only where n ≥ 3, to every carrier of that
   network at pixels in the cell.
6. **σ reduction** for a calibrated sector: `σ_eff = max(4, σ·sqrt(10/(10+n)))`.
7. **Recompute** sector estimates incrementally per sample. The calibration version increments at most every 30 s, and
   coverage tiles re-render for affected regions only.

## Review Focus

1. **No permission, no SIM, airplane mode, or a dual-SIM switch** → no crash; the info bar explains; calibration
   pauses. *Task 1.*
2. **Wrong-sector matches poisoning calibration** → only Likely or Confirmed matches are stored; a sector's offset needs
   n ≥ 3 before it is applied; there are offset clamps; the user can reset per sector. *Task 3.*
3. **Vendor quirks** (Samsung Exynos/Pixel Tensor: `CellInfoNr` with unavailable PCI, `Int.MAX_VALUE` sentinels, empty
   lists) → treated as unavailable fields, never as values. *Task 1.*
4. **Battery** → no cell polling or logging in the background; polling stops when the map isn't visible. *Task 1.*
5. **Calibration changing tiles** → only affected regions invalidate, and versions never mix. *Task 5.*

---

### Task 1: `:core:radio`
`RadioState` (subscription, network, carrier name, roaming, serving and neighbours as `ObservedCell(tech, channel,
freqMhz, band, pci, cellId, tac, rsrp, rsrq, sinr, taMeters?)`, display 5G flag), adapters for both callback APIs,
channel tables, MCC-MNC map, the sentinel filter, and `RadioMonitor(context, lifecycle)`. Robolectric shadows and table
tests per band. Commit `feat(radio): live cell and SIM state`.

### Task 2: Engine point query + calibration terms
- C++ `predictPoint(CarrierArrays, PatternTable, lat, lon, area, indoor, outRsrp[n])`.
- `CarrierArrays` gains `calOffsetDb`, `calSlope`, `calDmedKm`, `calSigmaScale`.
- `renderTile` gains an optional per-tile residual raster (`int8` at 0.25 dB per pixel, a network mask) added to every
  carrier's RSRP at the pixel.
- Branch-and-bound: the bound adds `max(0, offset + slope·…)` and the raster's maximum, so it stays a true upper bound.
- Python reference updated, with parity and brute-force tests extended.
- Commit `feat(engine): point prediction and calibration terms`.

### Task 3: `:core:calibration` — matcher, sample store, estimator
`calibration.db` (samples, sector_estimate, cell_map, residual_cell), `CellMatcher` (rules 1–2), `SampleLogger`
(rule 3 throttle), `Estimator` (rules 4–6), `CalibrationStore` (snapshot flow with a version), and CSV export.
`tools/calibration/reference.py` provides vectors for the estimator and score. Commit
`feat(calibration): matching, logging and estimation`.

### Task 4: Coverage integration
- `prepare` applies the sector terms.
- Tiles get a residual raster built from the residual cells.
- `TileKey` gains `calibrationVersion` per region.
- `valueAt` adds "Calibrated (n samples)".
- Commit `feat(coverage): calibrated predictions`.

### Task 5: App UI
- **Phone-state permission flow** with the rationale text.
- **Info bar** at the bottom, e.g. "Telstra 5G · B28 + n78 · −94 dBm · tower 1.3 km NE · Likely". Tapping it opens the
  site sheet.
- **Estimated speed:** `min(0.75·log2(1+SINR), 5.5 LTE | 7.4 NR) · BW · 0.75`, shown as "≈ 85 Mbps".
- **Map:** the connected sector is highlighted with a pulse ring, a thin line from your location, and faint
  neighbours.
- **Settings toggle**, plus a **Calibration screen**: a sample count, calibrated sectors (offset, n, a scatter of
  measured vs predicted drawn on Canvas), "Export CSV" (share sheet), "Delete all" (confirm), and per-sector reset.
- Commit `feat(app): connected tower and calibration UI`.

### Task 6: Verification
- **Emulator** (its simulated LTE cell): permission flow, info bar, matching against a synthetic fixture,
  export/delete.
- **The user's phone:** an APK delivered to the user, with a short checklist (info bar correct, tower highlight
  plausible, calibration count grows while walking).
- `docs/verification/m4.md`. Commit `docs: M4 verification results`.
