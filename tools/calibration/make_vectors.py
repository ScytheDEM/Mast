"""Write the calibration test vectors from tools/calibration/reference.py.

Usage: python3 -I tools/calibration/make_vectors.py <out.json>
(normally core/calibration/src/test/resources/calibration/vectors.json). Deterministic (fixed LCG).

Sections: `scores` (rule 2 inputs -> score), `confidence` (score lists -> LIKELY / UNCERTAIN), `throttle` (rule 3),
`fits` (rule 4: per-sector residuals and distances -> n, offset, slope, dMedianKm, slopeFitted), `sigma` (rule 6) and
`residual` (rule 5: samples plus the fits of their sectors -> cells).
"""
import importlib.util
import json
import math
import os
import sys

_spec = importlib.util.spec_from_file_location(
    "calibration_reference", os.path.join(os.path.dirname(os.path.abspath(__file__)), "reference.py"))
ref = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ref)


class Lcg:
    def __init__(self, seed):
        self.s = seed

    def next(self):
        self.s = (self.s * 6364136223846793005 + 1442695040888963407) % (1 << 64)
        return (self.s >> 11) / float(1 << 53)

    def uni(self, lo, hi):
        return lo + (hi - lo) * self.next()

    def gauss(self, sigma):
        u1, u2 = max(self.next(), 1e-12), self.next()
        return sigma * math.sqrt(-2.0 * math.log(u1)) * math.cos(2.0 * math.pi * u2)


def scores(rng):
    out = []
    for k in range(40):
        pred, meas = rng.uni(-125, -60), rng.uni(-125, -60)
        d = rng.uni(20, 45000)
        ta = None if k % 3 == 0 else d + rng.uni(-2000, 2000)
        r_max = rng.uni(2, 60)
        out.append({"predicted": pred, "measured": meas, "distanceM": d, "taM": ta, "rMaxKm": r_max,
                    "score": ref.match_score(pred, meas, d, ta, r_max)})
    return out


def confidences():
    cases = [[], [7.0], [1.0, 4.0], [1.0, 3.999], [5.0, 2.0, 9.0], [10.0, 10.0], [0.0, 3.0, 3.0], [12.5, 2.0, 4.9]]
    return [{"scores": c, "result": ref.confidence(c)} for c in cases]


def throttles():
    base = (1_000_000, -35.0, 149.0)
    cases = [(None, 1_000_000, -35.0, 149.0), (base, 1_004_999, -35.001, 149.0), (base, 1_005_000, -35.001, 149.0),
             (base, 1_090_000, -35.0003, 149.0), (base, 1_005_000, -35.00045, 149.0), (base, 1_005_000, -35.0, 149.00056)]
    out = []
    for last, now, lat, lon in cases:
        out.append({"last": None if last is None else {"ms": last[0], "lat": last[1], "lon": last[2]},
                    "nowMs": now, "lat": lat, "lon": lon, "ok": ref.throttle_ok(last, now, lat, lon)})
    return out


def sector_samples(rng, n, true_mu, true_b, d_lo, d_hi):
    ds = [math.exp(rng.uni(math.log(d_lo), math.log(d_hi))) for _ in range(n)]
    med = ref.median(ds) if ds else 1.0
    rs = [true_mu + true_b * math.log10(d / med) + rng.gauss(6.0) for d in ds]
    return rs, ds


def fits(rng):
    specs = [(0, 0, 0, 0.5, 5), (1, 4, 0, 0.5, 5), (2, -9, 0, 0.3, 2), (3, 6, 0, 0.3, 2), (10, -30, 0, 0.2, 8),
             (29, 3, -15, 0.1, 9), (30, 3, -15, 0.1, 9), (40, -5, 12, 0.2, 6), (40, -5, 12, 1.0, 2.5),
             (120, 25, -20, 0.05, 20), (60, 0, 0, 1.0, 3.0)]
    out = []
    for n, mu, b, lo, hi in specs:
        rs, ds = sector_samples(rng, n, mu, b, lo, hi)
        f = ref.fit_sector(rs, ds)
        out.append({"residuals": rs, "distancesKm": ds, **f})
    return out


def residual(rng):
    sectors = {1: (8.0, 0.0), 2: (-4.0, 10.0), 3: (2.0, 0.0)}
    samples = []
    by_sector = {}
    for sid, (mu, b) in sectors.items():
        n = {1: 12, 2: 40, 3: 2}[sid]
        for _ in range(n):
            lat = -35.2805 + rng.uni(-0.0018, 0.0018)
            lon = 149.1302 + rng.uni(-0.0018, 0.0018)
            d = rng.uni(0.2, 3.0)
            r = mu + b * math.log10(d) + rng.gauss(4.0)
            s = {"network": 0 if sid != 3 else 1, "lat": lat, "lon": lon, "sector": sid, "r": r, "dKm": d}
            samples.append(s)
            by_sector.setdefault(sid, []).append(s)
    fit = {sid: ref.fit_sector([s["r"] for s in ss], [s["dKm"] for s in ss]) for sid, ss in by_sector.items()}
    return {"samples": samples, "fits": {str(k): v for k, v in fit.items()}, "cells": ref.residual_cells(samples, fit)}


def main():
    rng = Lcg(20261023)
    doc = {
        "scores": scores(rng), "confidence": confidences(), "throttle": throttles(), "fits": fits(rng),
        "sigma": [{"sigma": s, "n": n, "sigmaEff": ref.sigma_eff(s, n), "scale": ref.sigma_scale(n)}
                  for s in (8.0, 6.0, 4.4, 9.2) for n in (0, 3, 10, 40, 400)],
        "residual": residual(rng),
    }
    with open(sys.argv[1], "w", encoding="ascii", newline="\n") as f:
        json.dump(doc, f, indent=1, sort_keys=True)
        f.write("\n")
    print("wrote %s: %d scores, %d fits, %d residual cells" % (sys.argv[1], len(doc["scores"]), len(doc["fits"]),
                                                               len(doc["residual"]["cells"])), file=sys.stderr)


if __name__ == "__main__":
    main()
