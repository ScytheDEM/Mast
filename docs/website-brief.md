# Website brief: Mast

Build a marketing website for an Android app called **Mast** (the name may change, so keep it easy to swap).
Below is everything you need to know about the product. Write the copy yourself in a clear, confident, friendly
Australian-English voice. Don't invent features, numbers or partnerships that aren't listed here.

## One-line pitch
See every mobile tower in Australia, and where the signal really reaches: on your phone, offline, and in your car.

## What the app is
Mast is an Android app for anyone who wants to understand mobile coverage in Australia: road-trippers, regional
and rural people, people choosing a carrier, tech enthusiasts, and people who work in the field. It shows every
Telstra, Optus, TPG (Vodafone) and NBN fixed-wireless transmitter from the official government register (ACMA), predicts
where each network's signal reaches, and warns you before you drive out of coverage.

## Key features (group these into website sections)
1. **Every tower in Australia.** About 25,000 sites and 850,000 transmitters from the ACMA Register of
   Radiocommunications Licences, updated automatically each week on Wi-Fi. Search by suburb, postcode or site ID.
   Filter by network, 4G/5G and band.
2. **Tower details and real antenna patterns.** Tap a tower to see every sector: which way it faces, which bands it
   carries (e.g. "Telstra · 80° · B28 · B3 · n78"), power, height and antenna model. Its actual radiation pattern is drawn
   on the map and as a polar plot, using ACMA's measured antenna patterns for about 98% of transmitters.
3. **Predicted coverage map.** A colour map of predicted signal strength (Excellent / Good / Fair / Weak) for the
   network you choose, with 4G+5G or 5G-only, an indoor mode, and a "reliability" view showing the chance you'll actually
   have signal. Long-press anywhere for "Predicted −97 dBm · Good · 82% reliable · Best sector: Telstra n78 · 1.2 km".
   - Built on recognised radio-planning methods (ITU and 3GPP propagation models), not guesswork.
   - Accounts for **terrain** (hills and valleys block signal, using Copernicus elevation data) and **buildings**
     (built-up areas, using building-footprint data).
   - A native C++ engine runs on the phone and draws on the GPU, so it's fast and needs no server.
4. **Your phone's live connection.** A Material You card shows your carrier, 4G/5G (including 5G+ / LTE-A), band,
   carrier-aggregation status, signal strength, and the tower you're connected to, drawn as a line to it on the map.
   Dual-SIM supported. Tap it for full cellular details: serving cell, neighbour cells and bands.
5. **Gets more accurate the more you use it.** Self-calibration compares your phone's real signal with the prediction
   and corrects the map along the places you go. This learning **stays on your phone**.
6. **Trip mode: "You're about to leave coverage."** Start a trip and the app looks ahead along the road you're on and
   warns you before a dead zone: "Leaving Telstra coverage in ~1.8 km · dead zone ~6 km", then "Back in coverage".
   - Optional voice alerts.
   - On Android 16 it appears as a Live Update in the status bar (and in the Samsung Now Bar), with a coloured strip of
     the coverage ahead.
   - Quick Settings tile.
7. **Tunnels and in-building coverage (DAS).** Recognises in-building and tunnel antenna systems (DAS) and tells you
   when you're connected to one ("DAS detected"). Keeps your position moving through tunnels when GPS drops out, and can
   use Bluetooth beacons to stay accurate.
8. **Works offline.** Download whole states, cities or highway corridors (base map and terrain) for use with no signal,
   which is exactly when you need it.
9. **Android Auto.** A signal panel in the car (carrier, 5G, band, carrier aggregation, signal bars), a map with a line
   to your connected tower, trip alerts on the car screen, and trips that start automatically when you plug in.
10. **Designed for modern Android.** Material You dynamic colour, light and dark themes, smooth 120 Hz-targeted maps,
    and layouts optimised for Pixel and Samsung phones and foldables (Galaxy Z Fold/Flip, Pixel Fold).

## Privacy (worth a short section)
- No account, no sign-up, no ads, no tracking or analytics.
- Tower data comes from the public ACMA register, and map, terrain and building data come from free open sources.
- Calibration measurements stay on the device. Users can view, export and delete them.
- Permissions (location, phone state, Bluetooth for beacons) are used only for the features above. The app never reads
  calls, messages or phone numbers.

## Platform and availability
- Android 10 or newer. Optimised for Google Pixel and Samsung Galaxy, including foldables.
- **Not on Google Play yet** (coming soon). Use a "Coming soon to Google Play" or "Join the waitlist" call to action, not
  a working store badge.
- The full Android Auto map works in the sideloaded version; the Play Store version will show the car signal screen
  and alerts.

## Accuracy and wording rules (important)
- Coverage is a **prediction**. Say "predicted coverage" and "estimates", never "guaranteed" or "exact".
- Don't claim to be affiliated with or endorsed by ACMA, Telstra, Optus, TPG/Vodafone, NBN Co, Google or Samsung. You may
  say "uses public data from the ACMA register".
- Use carrier names only factually, not their logos or brand colours as the site's branding.
- Don't quote speed tests, user numbers, ratings or reviews; there aren't any yet.

## Suggested site structure
- **Hero:** pitch line, a phone mock-up with the coverage map, and the Coming soon CTA.
- **Feature sections** for groups 1–9 above, each with a screenshot slot (screenshots will be supplied separately as
  `01-…png` to `10-…png`; use labelled placeholders for now).
- **"How it works":** ACMA data → antenna patterns → terrain and buildings → on-device engine → your phone's real
  measurements improve it.
- **Privacy section, FAQ** (offline? which carriers? does it drain battery? why Android only?), and a footer with the
  data attributions:
  - "Contains data from the ACMA Register of Radiocommunications Licences"
  - "Map data © OpenStreetMap contributors"
  - "Elevation: Copernicus DEM, © ESA / DLR / Airbus"
  - "Building footprints: Microsoft"

## Design direction
Clean, modern and calm, matching the app's Material You look: generous whitespace, rounded cards, soft tonal colours,
and a light/dark mode. The app's coverage colours are deep blue `#0B5FA5` (excellent), teal `#2A9D8F` (good), amber
`#E9C46A` (fair) and orange `#F4A261` (weak), and they make a good accent palette. Make it responsive and fast, with
accessible contrast.
