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


def _parse_number(s):
    """A finite decimal number, or None."""
    try:
        v = float(s.strip())
    except (ValueError, AttributeError):
        return None
    return v if math.isfinite(v) else None


MAX_POWER = 200.0
MAX_EMPTY_BINS = 3


def rasterise(rows):
    """Rule 2: a device's rows -> 360 per-degree POWER values, or None when the device is rejected.

    Each row is (START_ANGLE, STOP_ANGLE, POWER) text, read as the half-open interval [START, STOP)
    in degrees; STOP < START wraps through 360. Bin k takes the POWER of the row containing an angle
    congruent to k (mod 360), i.e. every integer d with START <= d < STOP (STOP + 360 when wrapping).
    Rejected: an unparseable / non-finite field, POWER > 200, two rows giving one bin different
    POWERs, or more than 3 empty bins. Up to 3 empty bins are filled by circular linear
    interpolation between the nearest filled bins on either side."""
    bins = [None] * 360
    for row in rows:
        if len(row) != 3:
            return None
        start, stop, power = (_parse_number(v) for v in row)
        if start is None or stop is None or power is None or power > MAX_POWER:
            return None
        if stop < start:
            stop += 360.0
        first, last = math.ceil(start), math.ceil(stop)   # integers d with start <= d < stop
        last = min(last, first + 360)
        for d in range(first, last):
            k = d % 360
            if bins[k] is None:
                bins[k] = power
            elif bins[k] != power:
                return None
    empty = [k for k in range(360) if bins[k] is None]
    if len(empty) > MAX_EMPTY_BINS:
        return None
    out = list(bins)
    for k in empty:
        a = 1
        while bins[(k - a) % 360] is None:
            a += 1
        b = 1
        while bins[(k + b) % 360] is None:
            b += 1
        lo, hi = bins[(k - a) % 360], bins[(k + b) % 360]
        out[k] = lo + (hi - lo) * a / (a + b)
    return out


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
    """Apply rules 1-4 and 6. `hrp_zip` is a path or binary file object. A device is a run of
    consecutive rows with the same parsed SDD_ID (the file keeps each SDD in one run).

    Returns (report, devices, patterns): devices maps sdd -> (pattern id, peak bearing, peak dBW)
    in first-seen order; patterns[id - 1] is the 360-entry quantised attenuation."""
    zf = zipfile.ZipFile(hrp_zip)
    counts = {"devices": 0, "patterns": 0, "skippedIncomplete": 0, "skippedUnknown": 0, "rows": 0}
    devices = {}
    pattern_ids = {}
    patterns = []

    def finish(sdd, rows):
        if sdd is None or sdd not in known_sdds:
            counts["skippedUnknown"] += 1
            return
        powers = rasterise(rows)
        if powers is None:
            counts["skippedIncomplete"] += 1
            return
        if sdd in devices:  # a repeated accepted run of a stored SDD: the first wins
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
        rows = []
        for row in reader:
            counts["rows"] += 1
            sdd = _parse_int(row[0], INT64_MAX) if row else None
            if not started or sdd != current:
                if started:
                    finish(current, rows)
                started = True
                current = sdd
                rows = []
            rows.append(tuple(row[2:5]) if len(row) == 5 else ())   # () = malformed row
        if started:
            finish(current, rows)

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
