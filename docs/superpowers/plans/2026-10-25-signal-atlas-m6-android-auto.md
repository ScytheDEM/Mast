# Signal Atlas — Milestone 6: Android Auto Implementation Plan

> Fast mode. Requested by the user 2026-10-25: "widgets on Android Auto that show the signal status of the phone when
> moving (band, CA status etc); on the map view show a line to the tower the user is connected to; support dual SIM;
> optimised for Android Auto with Material You design."

**Goal:** In the car:
- a live signal panel for each SIM (carrier, 4G/5G, primary band, CA count and aggregated bandwidth, signal bars,
  RSRP/SINR, distance and bearing to the serving tower);
- a map with your position, nearby towers and a line to the connected tower (one line per SIM, in carrier colours),
  with coverage optional;
- trip alerts, and trips that start and stop automatically with Android Auto.

## Platform facts (verified against Android docs; implementer re-checks the Car App Library version)
- **Dashboard widgets:** third-party apps cannot add widgets to the Android Auto dashboard (Coolwalk). Navigation-category
  apps render their own map surface, which Android Auto shows in the dashboard's map slot. That is how the "widget"
  works here: the signal panel is drawn on our map surface.
  - Custom surfaces need the navigation category, which the Play Store only allows for turn-by-turn apps. So:
  - The `fullMap` flavour (sideload) declares `androidx.car.app.category.NAVIGATION`.
  - The `play` flavour is template-only: a status `PaneTemplate`/`ListTemplate`, refreshed within the template refresh
    limits.
- **Carrier aggregation for normal apps:**
  - `ServiceState.getCellBandwidths()` gives the aggregated carrier count and bandwidths.
  - `TelephonyDisplayInfo` override `LTE_CA` / `LTE_ADVANCED_PRO` / `NR_NSA` / `NR_ADVANCED` gives the LTE-A / 5G / 5G+
    labels.
  - Secondary-cell bands (`PhysicalChannelConfig`) need the privileged `READ_PRECISE_PHONE_STATE` and are **not
    available**. The UI says "CA ×3 · 60 MHz", not the secondary band names.
- **Material You in the car:** templates are styled by the car host. Android Auto applies the app's `carColorPrimary`
  and `carColorSecondary` (light and dark). Our own map surface uses the app palette, adapted for car contrast. Dynamic
  wallpaper colour is not available in the car context, so the app's tonal palette is the fallback.

## Batch A: radio CA + car module skeleton
- **`:core:radio`:** add `ca: CarrierAggregation?` (count, bandwidthsKhz, total) from `ServiceState.cellBandwidths`, and
  a `displayLabel` (LTE / LTE-A / 5G / 5G+) from `TelephonyDisplayInfo`, per SIM. Unit tests.
- **`:car` module:** Car App Library (latest stable), a `CarAppService` with two flavours. The `fullMap` flavour declares
  the navigation category and the `play` flavour declares IOT, or whichever category fits a status app; check the docs
  and document the choice. A min car API level, and a session with a root screen.

## Batch B: map surface (fullMap)
- **Rendering:** a `SurfaceCallback` renders a MapLibre map into the car surface. Use MapLibre Android's surface or
  offscreen rendering API if it exists in 13.7.1; otherwise use a `MapSnapshotter` loop at ≤ 2 fps over the visible
  area. Verify and document which.
- **What it shows:**
  - the clean style with the dark variant at night, using the car host's `isDarkMode`;
  - the user's position and heading;
  - nearby towers, with the connected tower(s) highlighted;
  - a **line from you to the connected tower for each SIM**, in carrier colours (dashed for the second SIM);
  - optionally, the coverage layer for the trip network.
- **Signal panel (the "widget"):** drawn on the surface in a corner, rounded, tonal, and large enough to glance at.
  Each SIM gets one row: carrier, tech label (5G+ etc.), primary band, CA count and total MHz, signal bars, dBm, and
  tower distance and bearing.
- **`NavigationTemplate` action strip:** Start/Stop trip, Coverage on/off, Recenter, and Zoom in/out (map action strip).
  Pan and zoom gestures are supported on hosts that support them.

## Batch C: status screens, alerts, auto trip
- **"Signal" screen (`PaneTemplate`, both flavours):** per-SIM rows as above, plus neighbour count and the
  measured-vs-predicted line. Refreshed every 2 s within template limits.
- **Trip alerts:** shown as car `Alert`s ("Leaving Telstra coverage in 1.8 km · dead zone 6 km"), plus TTS through the
  car audio focus.
- **Auto trip:** `CarConnection.type == PROJECTION` starts the trip, which is allowed because the car app is in the
  foreground. Disconnecting stops it, unless it was started manually.

## Batch D: tests + smoke
- Unit tests for CA mapping, panel text and the screen models. Smoke test in the Desktop Head Unit (DHU) if installable
  from the SDK (`extras;google;auto`); otherwise use the Car App Library test harness (`TestCarContext`).
- Write `docs/verification/m6.md`.
