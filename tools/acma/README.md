# ACMA reference importer

Python reference for the Signal Atlas "Import rules" (1-10). The Kotlin importer
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

Together with the real rows (W units, EIRP=0 fall-through, Telstra n5) the fixture covers the band plan,
power units, omni sectors, null antennas, rounding and skip precedence (band -> power -> site).

## Interpretation notes (the Kotlin importer must match)

- `FREQUENCY` and `BANDWIDTH` in device_details are in Hz; the band plan compares `FREQUENCY / 1e6`.
- Rules 4-5 as amended in the plan (commit 557f87d): B26/n26 and n77 added. Rule 5: a non-blank `EIRP` that is <= 0 in a linear unit (W/mW/kW) is treated as not supplied
  and falls through to `TRANSMITTER_POWER` + antenna gain - feeder loss. The real export has
  EIRP=0 W beside a valid transmitter power (9625 kept-licence T devices on the 2026-10-22 export).
  Any other unusable value (unparseable number, unknown unit, blank/unusable transmitter power) is `skippedNoPower`.
- Skip precedence per device: unknown band, then no power, then bad site (each device counted once).
- `antennas` counts distinct non-blank antenna IDs on kept sectors that exist in antenna.csv.

## Full export (Task 12 reference)

Export 2026-10-22, extract stamp 202610221934 (`spectra_rrl.zip`, 68 MB), with band plan including B26/n26 and n77.
Reference wall-clock: 15.1 s.

```
sites 25359, sectors 537540, carriers 852634, antennas 1803,
skippedUnknownBand 304, skippedNoPower 10, skippedBadSite 0
carriersByNetwork: NBN 204154, OPTUS 190082, OPTUS_TPG_JV 23815, TELSTRA 231490, TPG 203093
```
