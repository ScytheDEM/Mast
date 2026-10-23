# ACMA reference importer

Python reference for the Signal Atlas "Import rules" (1-11). The Kotlin importer
must reproduce its counts exactly. Stdlib only; always run with `python3 -I`
(the export is untrusted data; keep it outside this directory).

- `reference.py <rrl.zip> <expected.json>` applies the rules and writes the ImportReport counts
  (sorted keys, indent 2).
- `make_fixture.py <rrl.zip> <out.zip>` trims a real export to sites 101160, 41428, 9002575,
  9019760 (all their device rows, plus referenced site/licence/client/antenna/antenna_pattern rows)
  and appends synthetic rows: bad sites 999999001 (blank latitude) and 999999002 (lat 51.5, lon -0.1)
  with devices SDD_ID 999999201/202; 999999101 (derived power: 40 W), 999999102 (no power),
  999999103 (450 MHz, unknown band). Output is deterministic.

## Regenerate

```
python3 -I tools/acma/make_fixture.py /path/to/spectra_rrl.zip core/acma/src/test/resources/acma/fixture.zip
python3 -I tools/acma/reference.py core/acma/src/test/resources/acma/fixture.zip core/acma/src/test/resources/acma/expected.json
```

## Synthetic fixture rows (all copy the first kept device, a TPG device; only listed fields change)

| SDD_ID | Change | Exercises |
|---|---|---|
| 999999201 / 202 | SITE_ID 999999001 (blank lat) / 999999002 (lat 51.5, lon -0.1) | skippedBadSite (missing coordinates, out of bounds) |
| 999999101 | EIRP blank, TRANSMITTER_POWER 40 W | derived power (+ antenna gain - feeder loss) |
| 999999102 | EIRP and TRANSMITTER_POWER blank | skippedNoPower |
| 999999103 | FREQUENCY 450 MHz | skippedUnknownBand |
| 999999301 | Telstra licence 9263433, 862 MHz | n26 NR (Telstra override of B26) |
| 999999302 | Optus licence 9460720, 862 MHz | B26 LTE |
| 999999303 | 3920 MHz | n77 NR |
| 999999304 / 305 / 306 | EIRP 500 mW / 40 dBm / 10 dBW | mW, dBm, dBW conversion |
| 999999307 | blank AZIMUTH | omni sector (null azimuth key) |
| 999999308 | blank ANTENNA_ID | null antenna in the key |
| 999999309 | AZIMUTH 22.25 | key rounds to 22.3 (floor(x*10+0.5)/10) |
| 999999310 | 450 MHz on site 999999001 | band+site both fail: counts skippedUnknownBand |
| 999999311 | 450 MHz, blank EIRP and power | band+power both fail: counts skippedUnknownBand |
| ABC | SDD_ID "ABC" | rule 11: skippedMalformed (checked before band) |
| 999999401 | ANTENNA_ID "N/A" | rule 11: invalid antenna -> null antenna (same sector as 999999308) |
| 999999402 | SITE_ID "X1" | rule 11: invalid site -> skippedBadSite |
| 999999403 | SITE_ID "0101160" | rule 11: leading zeros allowed, counts as site 101160 |

Together with the real rows (W units, EIRP=0 fall-through, Telstra n5) the fixture covers the band plan,
power units, omni sectors, null antennas, rounding and skip precedence (band -> power -> site).

## Interpretation notes (the Kotlin importer must match)

- `FREQUENCY` and `BANDWIDTH` in device_details are in Hz; the band plan compares `FREQUENCY / 1e6`.
- Rules 4-5 as amended in the plan (commit 557f87d): B26/n26 and n77 added. Rule 5: a non-blank `EIRP` that is <= 0 in a linear unit (W/mW/kW) is treated as not supplied
  and falls through to `TRANSMITTER_POWER` + antenna gain - feeder loss. The real export has
  EIRP=0 W beside a valid transmitter power (9625 kept-licence T devices on the 2026-10-22 export).
  Any other unusable value (unparseable number, unknown unit, blank/unusable transmitter power) is `skippedNoPower`.
- Skip precedence per device: unknown band, then no power, then bad site (each device counted once).
- Rule 11: SDD_ID, SITE_ID, ANTENNA_ID and CLIENT_NO are ASCII decimal integers after trimming
  (leading zeros allowed; SDD_ID <= 2^63-1, others <= 2^31-1) and are keyed as integers. A kept device
  with an invalid SDD_ID is `skippedMalformed` (before band); an invalid ANTENNA_ID is a null antenna;
  an invalid SITE_ID is a missing site. Rows in site.csv/antenna.csv/client.csv with invalid IDs are ignored.
- `antennas` counts distinct non-blank antenna IDs on kept sectors that exist in antenna.csv.

## Full export (Task 12 reference)

Export 2026-10-22, extract stamp 202610221934 (`spectra_rrl.zip`, 68 MB), with band plan including B26/n26 and n77
and rule 11 identifiers. Reference wall-clock: 16.3 s.

```
sites 25359, sectors 537540, carriers 852634, antennas 1803,
skippedUnknownBand 304, skippedNoPower 10, skippedBadSite 0, skippedMalformed 0
carriersByNetwork: NBN 204154, OPTUS 190082, OPTUS_TPG_JV 23815, TELSTRA 231490, TPG 203093
```

`reference.py` also exposes `load(zip)`. It returns the report plus the kept carriers (de-duplicated on
SDD_ID, first wins), the kept sites and the antennas. `tools/engine/make_vectors.py` and the HRP tools use
it. The CLI and its output are unchanged: `expected.json` and the full-export counts above re-verified.

# HRP reference (horizontal radiation patterns)

Python reference for the "HRP import rules" 1-6. The Kotlin `HrpImporter` must reproduce
`hrp_expected.json` exactly when it imports `hrp_fixture.zip` against the `towers.db` built from `fixture.zip`.

- `hrp_reference.py <hrp.zip> <rrl.zip> <expected.json> [SDD ...]`
  - The known SDDs are the carriers `reference.py` keeps from `<rrl.zip>`.
  - It writes the HrpReport and, for each SDD given, `sdd, patternId, peakBearing, peakDbw, att0_10`
    (the first 11 quantised values) and `att` (all 360).
- `make_hrp_fixture.py <hrp.zip URL or file> <rrl_fixture.zip> <out.zip> [<prefix.bin>]`
  - Reads the real zip from offset 0 with HTTP Range requests (256 KiB each) and inflates the single
    entry from its local header. It stops after the first 40 complete devices.
  - The 2026-10-23 build read 262 144 bytes (one request) of `https://cdn.acma.gov.au/rrl/spectra_licence_hrp.zip`,
    entry timestamp 2026-10-21 00:00.
  - `<prefix.bin>` keeps those bytes (in the scratchpad, never the repo). Rebuilding from it is byte-identical.
- `test_hrp_reference.py`: unit checks of the rules on synthetic zips.

## Regenerate

```
python3 -I tools/acma/test_hrp_reference.py
python3 -I tools/acma/make_hrp_fixture.py https://cdn.acma.gov.au/rrl/spectra_licence_hrp.zip \
    core/acma/src/test/resources/acma/fixture.zip core/acma/src/test/resources/acma/hrp_fixture.zip <scratch>/hrp/prefix.bin
python3 -I tools/acma/hrp_reference.py core/acma/src/test/resources/acma/hrp_fixture.zip \
    core/acma/src/test/resources/acma/fixture.zip core/acma/src/test/resources/acma/hrp_expected.json \
    2908964477 2909790812 2908989497 2909001795 2909790836
```

**Caution.** The CDN has served two different zips under the same ETag `0x8DF2EDC716D83D0`. They have
entry timestamps 2026-10-23 and 2026-10-21, and a different device order from the fourth device on. Use
the saved prefix to reproduce the committed fixture.

## Fixture contents (`hrp_fixture.zip`, entry `LICENCE_.csv`, CRLF, 16 199 rows)

| Devices | SDD_ID | Exercises |
|---|---|---|
| 40 real complete devices, in file order | 30 keep their real SDD_IDs | `skippedUnknown` (30) |
| ... of which devices 0, 4, ..., 36 | 2908964477, 2909790812, 2908970467, 2909790822, 2908975948, 2909790823, 2908976001, 2909790824, 2908989497, 2909790832 (fixture TELSTRA/OPTUS carriers, interleaved) | kept devices; the real shapes de-duplicate to 4 patterns (pattern 3 is shared by 6 devices with different peak bearings) |
| copy of real device 1, angle 359 dropped | 2908994717 | incomplete (359 rows) -> `skippedIncomplete` |
| copy of real device 2, rows for angles 10 and 11 swapped | 2909790833 | out of order -> `skippedIncomplete` |
| copy of real device 3 | 999999999 | SDD not in the tower fixture -> `skippedUnknown` |
| one real shape rotated by +90° / +200° | 2909001795 / 2909790836 | identical `q`, different peak bearings (211 / 321) -> one pattern, shared with 2909790812 |

`hrp_expected.json`: devices 12, patterns 4, skippedIncomplete 2, skippedUnknown 31, rows 16 199.

## Interpretation notes (the Kotlin importer must match)

- **Runs.** A device is a maximal run of consecutive rows with the same parsed SDD_ID. An unparseable
  SDD_ID is its own unknown run.
- **Order of checks.** A run whose SDD is not a carrier counts once in `skippedUnknown`, before any
  completeness check.
- **Complete run.** Exactly 360 rows, where row i has `START_ANGLE == i`, and every `POWER` parses as a
  finite number. Anything else counts once in `skippedIncomplete`. `STOP_ANGLE` is not checked.
- **Repeats.** A later complete run for an SDD that is already stored is ignored (the first wins). It is
  not counted.
- **`rows`** is every data row read (the header excluded).
- **Quantisation.** `q = clamp(floor((peak − P)·4 + 0.5), 0, 255)` in double. Real POWER values have at
  most one decimal, so `(peak − P)·4` is never within rounding error of a .5 tie, and float32 parsing gives
  the same codes.
- **Pattern ids** are assigned 1..N in first-seen order of the kept devices. `peakDbw` is the parsed
  maximum POWER.
