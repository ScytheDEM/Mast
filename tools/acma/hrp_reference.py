"""Reference implementation of the Signal Atlas "HRP import rules" 1-6.

Usage: python3 -I tools/acma/hrp_reference.py <hrp.zip> <rrl.zip> <expected.json> [SDD ...]

Stdlib only. Streams LICENCE_.csv from the HRP zip, keeps devices whose SDD_ID is a carrier that
tools/acma/reference.py keeps from <rrl.zip> (= the carriers of the towers.db built from it), and
writes the HrpReport plus, for each SDD given, its patternId / peakBearing / peakDbw / att0_10 (the
first 11 quantised values) / att (all 360), sorted keys, indent 2.
"""
import csv
import importlib.util
import io
import json
import math
import os
import sys
import zipfile

ENTRY = "LICENCE_.csv"
HEADER = ["SDD_ID", "DEVICE_REGISTRATION_IDENTIFIER", "START_ANGLE", "STOP_ANGLE", "POWER"]
INT64_MAX = 2**63 - 1


def _parse_int(s, hi):
    s = s.strip()
    if not s or any(c not in "0123456789" for c in s):
        return None
    v = int(s)
    return v if v <= hi else None


def _parse_power(s):
    try:
        v = float(s.strip())
    except ValueError:
        return None
    return v if math.isfinite(v) else None


def normalise(powers):
    """Rule 3: (peak dBW, peak bearing, 360 quantised attenuations in 0.25 dB steps)."""
    peak = max(powers)
    peak_bearing = powers.index(peak)  # first maximum
    q = []
    for k in range(360):
        rel = peak - powers[(peak_bearing + k) % 360]
        q.append(min(max(int(math.floor(rel * 4.0 + 0.5)), 0), 255))
    return peak, peak_bearing, q


def run(hrp_zip, known_sdds):
    """Apply rules 1-4 and 6. `hrp_zip` is a path or binary file object.

    Returns (report, devices, patterns): devices maps sdd -> (pattern id, peak bearing, peak dBW)
    in first-seen order; patterns[id - 1] is the 360-entry quantised attenuation."""
    zf = zipfile.ZipFile(hrp_zip)
    counts = {"devices": 0, "patterns": 0, "skippedIncomplete": 0, "skippedUnknown": 0, "rows": 0}
    devices = {}
    pattern_ids = {}
    patterns = []

    def finish(sdd, angles, powers):
        if sdd is None or sdd not in known_sdds:
            counts["skippedUnknown"] += 1
            return
        complete = len(angles) == 360 and all(a == i for i, a in enumerate(angles)) and None not in powers
        if not complete:
            counts["skippedIncomplete"] += 1
            return
        if sdd in devices:  # a repeated complete run of a stored SDD: the first wins
            return
        peak, peak_bearing, q = normalise(powers)
        key = bytes(q)
        if key not in pattern_ids:
            patterns.append(q)
            pattern_ids[key] = len(patterns)
        devices[sdd] = (pattern_ids[key], peak_bearing, peak)

    with zf.open(ENTRY) as raw:
        reader = csv.reader(io.TextIOWrapper(raw, encoding="iso-8859-1", newline=""))
        header = next(reader)
        if [h.strip() for h in header] != HEADER:
            raise ValueError("unexpected header %r" % header)
        started = False
        current = None      # parsed SDD_ID of the current run (None = unparseable)
        angles, powers = [], []
        for row in reader:
            counts["rows"] += 1
            if len(row) != 5:
                row = (row + [""] * 5)[:5]
            sdd = _parse_int(row[0], INT64_MAX)
            if not started or sdd != current:
                if started:
                    finish(current, angles, powers)
                started = True
                current = sdd
                angles, powers = [], []
            angle = _parse_int(row[2], 359)
            angles.append(-1 if angle is None else angle)
            powers.append(_parse_power(row[4]))
        if started:
            finish(current, angles, powers)

    counts["devices"] = len(devices)
    counts["patterns"] = len(patterns)
    return counts, devices, patterns


def load_known_sdds(rrl_zip):
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "reference.py")
    spec = importlib.util.spec_from_file_location("acma_reference", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return {c["sdd_id"] for c in mod.load(rrl_zip)["carriers"]}


def main():
    if len(sys.argv) < 4:
        sys.exit("usage: hrp_reference.py <hrp.zip> <rrl.zip> <expected.json> [SDD ...]")
    report, devices, patterns = run(sys.argv[1], load_known_sdds(sys.argv[2]))
    samples = []
    for s in sys.argv[4:]:
        sdd = int(s)
        if sdd not in devices:
            sys.exit("SDD %d has no pattern" % sdd)
        pid, peak_bearing, peak = devices[sdd]
        samples.append({"sdd": sdd, "patternId": pid, "peakBearing": peak_bearing, "peakDbw": peak,
                        "att0_10": patterns[pid - 1][:11], "att": patterns[pid - 1]})
    out = dict(report)
    out["samples"] = samples
    with open(sys.argv[3], "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, sort_keys=True, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
