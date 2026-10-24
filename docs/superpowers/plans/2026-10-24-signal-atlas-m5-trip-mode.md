# Signal Atlas — Milestone 5: Trip Mode Implementation Plan

> Fast mode (user request): one builder per batch, no per-task reviews, one combined review later.

**Goal:** Start a trip and get warned before you lose coverage: "Leaving Telstra 4G coverage in ~1.8 km · dead zone ~6 km",
then "Back in coverage". Shown as a notification with optional voice, an Android 16 Live Update (a status-bar chip, and
the Samsung Now Bar on One UI 8), and a coverage strip of the road ahead. Calibration logs while driving.

**Architecture:**
- **`:feature:trip` (new)** holds a location foreground service (`TripService`), a pure-Kotlin `LookAhead` (predicts
  the path ahead), a pure-Kotlin `AlertEngine` (decides alerts), `TripNotifications` (Live Update or ongoing
  notification), `TripVoice` (TextToSpeech), and `TripTileService` (Quick Settings).
- **Coverage along the path** comes from `CoverageTileService.predictAlong(points, selection)`. It uses the engine's
  `predictPoint` over prepared corridor carriers (quick, terrain where cached, calibration), giving RSRP, σ and P per
  point.

**Spec:** §6 (Trip mode, alerts), §8 (Live Updates, QS tile). **Builds on:** `m4-radio-calibration`.

## Decisions (fast mode, recorded as rulings)
- **Path prediction:** M5 uses a **track-based look-ahead**, not road snapping. It extrapolates along the current heading
  with the turn rate from the last 30 s of fixes (a circular arc), and straightens beyond 1 km.
  - Road-graph following needs an offline road network (MVT decoding of the base map), which moves to M5b alongside the
    M3b region packs.
  - Cost: on winding roads the look-ahead drifts off-road beyond about 1 km. Mitigated by keeping the default window
    at 2 km and smoothing.
- **Starting a trip:** manually (map button) or from the Quick Settings tile only. Auto-start on car Bluetooth or
  Android Auto needs background-location start rights and moves to M6 (Android Auto), so M5 needs no
  `ACCESS_BACKGROUND_LOCATION`.
- **Stationary detection:** GPS speed only (no Play Services). Speed < 1 m/s for 60 s means stationary, and the fix rate
  drops from 1 Hz to every 5 s.

## Global Constraints
- **Manifest:** a foreground service of type `location`, plus `FOREGROUND_SERVICE_LOCATION`. Start the trip only from
  the foreground (app UI or QS tile tap).
- **Live Updates:** API 36+ uses `Notification.ProgressStyle` with segments and `setRequestPromotedOngoing(true)`, plus
  `setShortCriticalText` with the distance text. API < 36 uses a standard ongoing notification with the same text.
  - Request `POST_PROMOTED_NOTIFICATIONS` where it exists.
  - Fall back gracefully if promotion is denied.
- **Alert channel:** `coverage_alerts` (high importance, sound + vibration). The ongoing channel is `trip_status` (low).
- **Defaults:** warning window 90 s or 2 km, whichever comes first, scaled with speed (`window = clamp(speed·90 s, 500 m, 5 km)`).
  - Minimum dead-zone length 300 m.
  - Tech floor "lose all data" (other options: "lose 5G", "lose 4G+").
  - Voice off.
- **Network:** the SIM network from `RadioMonitor`, falling back to the coverage selection network.
- **Battery:** stop everything when the trip stops. The service must survive the screen being off.

### Alert rules (pure Kotlin; the tests replay synthetic tracks)
1. **Sampling:** every 2 s, sample points every 50 m along the look-ahead to `window + 10 km`.
2. Each point gets P(coverage) for the selected network and tech floor. The threshold is −118 dBm, or −110 dBm for
   "lose 5G", where only NR carriers count.
3. **Dead stretch:** a run of consecutive points with P < 0.5 whose length is at least the minimum dead-zone length.
4. **"Leaving coverage" fires** when the first dead stretch starts within the window and we are currently covered
   (P ≥ 0.5 at the current position, or measured RSRP ≥ threshold + 3 dB).
   - Text: "Leaving {Network} {Tech} coverage in ~{d} · dead zone ~{len}", with d and len rounded (< 1 km → 100 m steps;
     else 0.1 km).
5. **Early trigger:** if measured RSRP has dropped by ≥ 10 dB in 30 s and is now ≤ threshold + 6, fire early with "Signal
   dropping fast".
6. **"Back in coverage"** fires when we were in a dead stretch (alerted) and the measured or predicted state has been
   covered for ≥ 200 m.
7. **Hysteresis:** no repeat of "Leaving" for the same dead stretch, matched by its start within ±300 m. There is a
   minimum of 60 s between alerts of the same kind.
8. **While in a dead stretch,** the ongoing notification shows "Coverage returns in ~{d}" when a covered stretch ≥ 300 m
   appears ahead within 10 km.

## Batches
- **A: trip core.** `:feature:trip` with LookAhead, AlertEngine (rules 1–8), the `predictAlong` API, and TripService
  (location, 1 Hz / stationary, RadioMonitor + SampleLogger running), wired in AppContainer. Tests cover look-ahead
  geometry and the alert engine replaying tracks: approach a dead zone, pass through it, hysteresis, early trigger,
  stationary.
- **B: delivery.** TripNotifications (Live Update ProgressStyle segments = the coverage strip ahead, coloured covered,
  weak and dead, with a tracker icon; the API < 36 fallback), voice (TextToSpeech, "Coverage ends in 1.8
  kilometres"), the Quick Settings tile, the map "Start trip" button with a trip banner, and Settings for window, minimum
  dead zone, tech floor and voice. Tests cover the notification builders and strings.
- **C: smoke.** On the emulator, replay a GPX route with `adb emu geo fix` steps near a known dead zone (pick one on a
  rural highway using predictions). Alerts fire, the Live Update shows on API 36+, and the tile toggles. Write
  `docs/verification/m5.md` and deliver an APK.
