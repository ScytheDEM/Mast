"""One site's carriers with their ACMA horizontal patterns, for the app's radiation-pattern tests.

Usage: python3 -I tools/acma/site_patterns.py <rrl.zip> <hrp_rows.csv> <site_id> <out.json>

<hrp_rows.csv> is LICENCE_.csv from the HRP zip filtered to the site's SDD_IDs (the full entry is 10 GB; see
README "Site patterns"). Carriers come from reference.load(<rrl.zip>) (the tower DB's import rules); patterns from
hrp_reference.rasterise / normalise (the patterns.db rules). Each carrier is written with its sector id, network,
band, frequency, azimuth, height, antenna id and, when it has an accepted pattern, peakBearing plus att: the 360
quantised attenuations (0.25 dB steps, relative to the peak bearing) as hex. Stdlib only.
"""
import csv
import importlib.util
import json
import os
import sys


def _module(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(os.path.dirname(os.path.abspath(__file__)), file))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main():
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    rrl_zip, rows_csv, site_id, out = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
    reference = _module("acma_reference", "reference.py")
    hrp = _module("acma_hrp_reference", "hrp_reference.py")
    data = reference.load(rrl_zip)
    carriers = [c for c in data["carriers"] if c["site_id"] == site_id]
    wanted = {c["sdd_id"] for c in carriers}
    rows = {}
    with open(rows_csv, newline="") as f:
        for r in csv.reader(f):
            if not r or r[0] == "SDD_ID":
                continue
            sdd = int(r[0])
            if sdd in wanted:
                rows.setdefault(sdd, []).append((r[2], r[3], r[4]))
    lat, lon = data["sites"][site_id]
    result = []
    for c in sorted(carriers, key=lambda c: c["sdd_id"]):
        item = {k: c[k] for k in ("sdd_id", "sector_id", "network", "tech", "band", "freq_hz", "azimuth_deg", "height_m", "antenna_id")}
        powers = hrp.rasterise(rows[c["sdd_id"]]) if c["sdd_id"] in rows else None
        if powers is not None:
            _, peak_bearing, q = hrp.normalise(powers)
            item["peakBearing"] = peak_bearing
            item["att"] = bytes(q).hex()
        result.append(item)
    with open(out, "w") as f:
        json.dump({"site_id": site_id, "lat": lat, "lon": lon, "carriers": result}, f, indent=1, sort_keys=True)
        f.write("\n")
    print(f"site {site_id}: {len(result)} carriers, {sum('att' in r for r in result)} with patterns")


if __name__ == "__main__":
    main()
