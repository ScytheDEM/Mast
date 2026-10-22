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

## Interpretation notes (the Kotlin importer must match)

- `FREQUENCY` and `BANDWIDTH` in device_details are in Hz; the band plan compares `FREQUENCY / 1e6`.
- Rule 5: a non-blank `EIRP` that is <= 0 in a linear unit (W/mW/kW) is treated as not supplied
  and falls through to `TRANSMITTER_POWER` + antenna gain - feeder loss. The real export has
  EIRP=0 W beside a valid transmitter power (9625 kept-licence T devices on the 2026-10-22 export).
  Any other unusable value (unparseable number, unknown unit, blank/unusable transmitter power) is `skippedNoPower`.
- Skip precedence per device: unknown band, then no power, then bad site (each device counted once).
- `antennas` counts distinct non-blank antenna IDs on kept sectors that exist in antenna.csv.

## Full export (Task 12 reference)

Export 2026-10-22, extract stamp 202610221934 (`spectra_rrl.zip`, 68 MB). Reference wall-clock: 15.5 s.

```
sites 25349, sectors 530442, carriers 838129, antennas 1799,
skippedUnknownBand 14809, skippedNoPower 10, skippedBadSite 0
carriersByNetwork: NBN 203889, OPTUS 190082, OPTUS_TPG_JV 23815, TELSTRA 217250, TPG 203093
```
