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
  - Inflates the single entry from its local header and scans it device by device. It stops once it has
    the first 40 devices that rule 2 accepts, plus the first real example of each interval shape and of
    the sentinel rejection (`MAX_BYTES` 256 MiB at most).
  - **Range reads (1 MiB each) are pinned.** The first response's ETag and Last-Modified are sent as
    `If-Match` / `If-Unmodified-Since`. A 412, a changed ETag / Last-Modified / Content-Range total, or a
    range not starting where asked aborts the scan. It then restarts from offset 0, up to 3 attempts, so
    every byte comes from one version.
  - **The 2026-10-23 build** read 139 460 608 bytes: ETag `0x8DF2EDC716D83D0`, Last-Modified 2026-10-20
    19:00:44 GMT, entry timestamp 2026-10-21 00:00.
    - The first two attempts hit 412s: the CDN was serving a newer zip, ETag `0x8DF307A679241A2`,
      Last-Modified 2026-10-22 20:23:59 GMT, 1 562 257 938 bytes.
    - The bytes read equal the first 139 460 608 bytes of the full 2026-10-21 copy (sha256 `847f73ee…1aa4`),
      so the fixture can be rebuilt from either.
  - `<prefix.bin>` keeps the bytes read (in the scratchpad, never the repo). Rebuilding from it is byte-identical.
- `test_hrp_reference.py`: unit checks of the rules on synthetic zips (rasterising, gap fill, every rejection).

## Regenerate

```
python3 -I tools/acma/test_hrp_reference.py
python3 -I tools/acma/make_hrp_fixture.py https://cdn.acma.gov.au/rrl/spectra_licence_hrp.zip \
    core/acma/src/test/resources/acma/fixture.zip core/acma/src/test/resources/acma/hrp_fixture.zip <scratch>/hrp/prefix2.bin
# or, offline: make_hrp_fixture.py <scratch>/hrp/prefix2.bin (or the full 2026-10-21 zip) ... (same output)
python3 -I tools/acma/hrp_reference.py core/acma/src/test/resources/acma/hrp_fixture.zip \
    core/acma/src/test/resources/acma/fixture.zip core/acma/src/test/resources/acma/hrp_expected.json \
    2908964477 2909790823 2908976001 2909790824 2908989497 2909790832 2909790836 2909006783
```

**Caution.** The CDN has served different zips under the same ETag `0x8DF2EDC716D83D0` (entry timestamps
2026-10-23 and 2026-10-21). Since 2026-10-23 it also serves a newer zip (`0x8DF307A679241A2`), with edges
mixing versions. Use the saved prefix to reproduce the committed fixture.

Ruling BB (for Task 4): the on-device HRP download must verify the entry CRC-32 (from the central
directory or data descriptor) against the inflated bytes, because an ETag match does not guarantee the same
bytes. A mismatch fails the import and keeps the existing `patterns.db` (see "Ruling BB, as implemented" below).

## Fixture contents (`hrp_fixture.zip`, entry `LICENCE_.csv`, CRLF, 18 044 rows, 52 devices)

Fixture SDD_IDs are TELSTRA/OPTUS carriers of the tower fixture, interleaved, in this order.

| Devices | SDD_ID | Exercises |
|---|---|---|
| 40 real accepted devices, in file order (integer grid, sorted) | 35 keep their real SDD_IDs | `skippedUnknown` (35) |
| ... of which devices 0, 8, 16, 24, 32 | 2908964477, 2909790812, 2908970467, 2909790822, 2908975948 | kept; patterns 1, 2, 2, 2, 3 |
| real 2909253965 (361 rows, integer grid, a row wrapping past 360, starts at 211°) | 2909790823 | wrap; pattern 5 |
| real 2909304224 (353 rows, half-degree grid `[0,0.5)`, `[0.5,1.5)`, ... `[359.5,360)`) | 2908976001 | half grid; pattern 6 |
| real 2909304226 (353 rows, half grid with rows wider than 1°) | 2909790824 | merged rows; pattern 6 (same shape as the half example) |
| real 2909149665 (360 rows, not a rotation of sorted order) | 2908989497 | shuffled; pattern 2 |
| real 2909149801 (359 rows, `[0,1)` missing) | 2909790832 | degree 0 filled by interpolation; pattern 4 |
| real 2909202009 (1 row `330,30`, POWER 2400) | 2908994717 | sentinel (and 60°) -> `skippedIncomplete` |
| copy of a plain real device with degrees 100-103 removed | 2909790833 | 4 empty bins -> `skippedIncomplete` |
| copy of a plain real device plus a row `[10,12)` with POWER + 1.0 | 2909001795 | conflicting overlap -> `skippedIncomplete` |
| copy of a plain real device | 999999999 | SDD not in the tower fixture -> `skippedUnknown` |
| one plain real shape rotated by +90° / +200°, the second listed in reverse order | 2909790836 / 2909006783 | identical `q`, peak bearings 211 / 321 -> one pattern (7) |
| rows 0..59 of a plain real device | 2909790842 | 60°-only sector (300 empty bins) -> `skippedIncomplete` |

The 60°-only device is synthetic. The real 60° devices seen in the scan carry the 2400 sentinel, and the
first one is the sentinel example above.

`hrp_expected.json`: devices 12, patterns 7, skippedIncomplete 4, skippedUnknown 36, rows 18 044. The
samples are 8 SDDs: one base device, wrap, half, merged, shuffled, missing-0, and the de-duplication pair.

## Interpretation notes (the Kotlin importer must match)

- **Runs.** A device is a maximal run of consecutive rows with the same parsed SDD_ID. The file keeps each
  SDD in one run. An unparseable SDD_ID is its own unknown run.
- **Order of checks.** A run whose SDD is not a carrier counts once in `skippedUnknown`, before rule 2.
- **Rasterising (rule 2).**
  - START, STOP and POWER are decimal numbers. A row with ≠ 5 fields, or a field that does not parse as a
    finite number, rejects the device.
  - When STOP < START, STOP += 360. Bin `d mod 360` takes the row's POWER for every integer d with
    `START ≤ d < STOP`: `d` runs from `ceil(START)` to `ceil(STOP) − 1`, at most 360 values.
  - So `[0,0.5)` holds degree 0, `[359.5,360)` holds none, `[359.5,0.5)` holds 0, and a full-circle row
    `[0,360)` holds all 360. A zero-width row (START = STOP) holds none.
  - Reject the device when a bin gets two different POWERs (exact comparison of the parsed doubles), when
    any POWER > 200, or when more than 3 bins are empty.
  - Each empty bin k is filled from the nearest *filled* bins L = k − a and R = k + b (circular):
    `P[L] + (P[R] − P[L])·a/(a + b)`, in double, in that operation order.
- **Repeats.** A later accepted run for an SDD that is already stored is ignored (the first wins). It is
  not counted.
- **`rows`** is every data row read (the header excluded).
- **Quantisation.** `q = clamp(floor((peak − P)·4 + 0.5), 0, 255)` in double, over the 360 rasterised values.
  - Real POWER values have at most two decimals, so `(peak − P)·4` is never near a .5 tie for raw bins.
  - Interpolated bins can land near a tie, so the importer must use double, not float32, for POWER,
    interpolation and quantisation.
- **Pattern ids** are assigned 1..N in first-seen order of the kept devices. `peakDbw` is the maximum
  rasterised POWER (diagnostic only; see rule 2).

## Full HRP file (Task 4 `HrpFullTest`)

`spectra_licence_hrp.zip` 1,562,523,638 bytes, ETag `0x8DF2EDC716D83D0`, Last-Modified 2026-10-20 19:00:44 GMT,
entry `LICENCE_.csv` 10,060,668,828 bytes, entry timestamp 2026-10-21 00:00, CRC-32 `0xcde3120b`, against the
towers.db built from the 2026-10-22 export (852,634 carriers).

Rule 2 as amended 2026-10-23 (rows are angle intervals, commit 9de290b; reference 9483d80):

```
devices 836256, patterns 14507, skippedIncomplete 597, skippedUnknown 17857, rows 280553129
SDD 2909149471: pattern 1, peak bearing 265, peak 33.6 dBW, floor code 100 (peak - 25.0 dB)
```

- `hrp_reference.py`: 857 s wall-clock, 1.0 GB peak RSS; identical counts.
- Kotlin `HrpImporter` (host JVM, 256 MB heap cap): 45.9 s (towers 0.05 s, stream 45.9 s); `patterns.db` 27.1 MB;
  peak heap used 30.9 MB (baseline 4.9 MB). Byte parser alone: ~11 M rows/s on the host.
- Run: `ACMA_HRP_ZIP=<scratch>/hrp/spectra_licence_hrp.zip ACMA_FULL_ZIP=<scratch>/acma/spectra_rrl.zip ./gradlew :core:acma:test --tests '*HrpFullTest*'`
- 836,256 of the 852,634 carriers get a pattern (98.1%); 15,781 carriers have no HRP rows.
- The shape analysis (`.superpowers/sdd/2026-10-23-signal-atlas-m2-coverage-engine/hrp-shape-analysis.md`)
  predicted 836,137 because it excluded |POWER| > 200. Rule 2 rejects only POWER > 200, so the 119 carriers
  whose only out-of-range values are *negative* sentinels (e.g. −2,146,826,368, −380.3) are accepted: their
  sentinel bins quantise to 255 (≥ 63.75 dB down). The 597 rejected carriers: 596 with positive sentinels (2400
  etc.; the real 60°-only sectors are among them) and 1 conflicting overlap.
- Before the amendment (rows read as 1° samples in order): devices 95,319, patterns 3,390, skippedIncomplete
  741,534; Kotlin 32.3 s, patterns.db 3.73 MB.

**Ruling BB, as implemented.** `java.util.zip.ZipFile`'s entry stream does *not* check the CRC (verified on JDK 17:
a zip with a flipped CRC reads without error; only `ZipInputStream` checks). `HrpImporter` therefore CRC-32s and
counts the inflated bytes itself and throws `ZipException` at end of stream on a CRC or size mismatch; `HrpWorker`
then discards the zip and its sidecar (`<zip>.etag`: ETag, Content-Length, Last-Modified) and retries.

**Download pinning (Rulings BB/HH; the CDN's edges mix versions).** A resumed request sends `Range`, `If-Range`,
`If-Match` and `If-Unmodified-Since`; a 412 discards the partial file and restarts from byte 0. A response whose
ETag, Last-Modified or length differs from the HEAD discards the file (`RemoteChangedException`); `HrpWorker` then
HEADs again and downloads from 0, up to 3 times per run, before retrying later.

# Site patterns (`site_patterns.py`)

One site's carriers with their quantised HRPs, for `app/src/test/resources/site_<id>_patterns.json` (BartonPatternTest).

```
cut -d, -f1 <site devices> | sed 's/^/^/; s/$/,/' > sdds.txt   # the site's SDD_IDs from device_details.csv (SITE_ID column)
unzip -p <scratch>/hrp/spectra_licence_hrp.zip LICENCE_.csv | LC_ALL=C grep -E -f sdds.txt > rows.csv   # ~40 s
python3 -I tools/acma/site_patterns.py <scratch>/acma/spectra_rrl.zip rows.csv 101160 app/src/test/resources/site_101160_patterns.json
```

Barton 101160 (2026-10-22 RRL, 2026-10-21 HRP): 111 carriers in 69 sector rows, all with patterns. Every directional
device peaks within 12° of its licensed azimuth (ACMA angles are compass bearings), front-to-back 25–45 dB; antenna
40081 (6 Telstra carriers) is filed as a single flat 0–360° row.
