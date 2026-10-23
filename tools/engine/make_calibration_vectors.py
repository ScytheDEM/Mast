"""Write the M4 engine vectors (point query, calibration terms, residual rasters) from tools/engine/reference.py.

Usage: python3 -I tools/engine/make_calibration_vectors.py <vector_dir>

Reads <vector_dir>/tile_canberra_z13.json (its carriers) and writes, into <vector_dir>/calibration/:
- point_canberra.json: the carriers plus synthetic calibration columns and a `network` column, a list of points
  (area, indoor, lat, lon) and the expected `predict_point` RSRP of every carrier at each (null = unusable);
- tile_canberra_z13_calibrated.json: the same tile rendered with those columns and two residual rasters
  (base64 int8 codes and their network masks), in the tile-vector format.
Floats have 6 decimals and every input is rounded to them before use. Output is deterministic.
"""
import base64
import importlib.util
import json
import math
import os
import sys


def _load(name, rel):
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), rel)
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


ref = _load("engine_reference", "reference.py")
vec = _load("engine_vectors", "make_vectors.py")


def r6(v):
    return None if v is None else float("%.6f" % v)


def add_calibration(carriers):
    """Synthetic terms: every third carrier fully calibrated, the next offset-only, the next none (null)."""
    n = carriers["n"]
    cols = {k: [] for k in ("calOffsetDb", "calSlope", "calDmedKm", "calSigmaScale")}
    for i in range(n):
        if i % 3 == 0:
            cols["calOffsetDb"].append(r6(-12.0 + (i * 7) % 25 + 0.25))
            cols["calSlope"].append(r6((i % 5 - 2) * 6.5))
            cols["calDmedKm"].append(r6(0.5 + (i % 4) * 1.5))
            cols["calSigmaScale"].append(r6(math.sqrt(10.0 / (10.0 + (i * 13) % 60))))
        elif i % 3 == 1:
            cols["calOffsetDb"].append(5.5)
            cols["calSlope"].append(None)
            cols["calDmedKm"].append(None)
            cols["calSigmaScale"].append(None)
        else:
            for k in cols:
                cols[k].append(None)
    out = dict(carriers)
    out.update(cols)
    out["network"] = [i % 2 for i in range(n)]
    return out


def rasters():
    """A: smooth +-3 dB for network 0 (mask 1); B: +10 dB squares for networks 0 and 1 (mask 3)."""
    a = [ref.round_half_up(12.0 * math.sin(px / 23.0) * math.cos(py / 31.0)) for py in range(256) for px in range(256)]
    b = [40 if (px // 64 + py // 64) % 2 == 0 and py < 128 else 0 for py in range(256) for px in range(256)]
    return [(a, 1), (b, 3)]


def b64(codes):
    return base64.b64encode(bytes(c & 0xFF for c in codes)).decode("ascii")


def points():
    out = []
    centre = ref.tile_centre(13, 7489, 4954)
    offsets = [(0.0, 0.0), (0.01, 0.02), (-0.02, 0.015), (0.03, -0.04), (-0.05, -0.05), (0.2, 0.1), (-0.6, 0.9),
               (1.1, -1.3)]
    areas = ("RURAL", "SUBURBAN", "URBAN", "DENSE_URBAN")
    for k, (dlat, dlon) in enumerate(offsets):
        out.append({"lat": r6(centre[0] + dlat), "lon": r6(centre[1] + dlon), "area": areas[k % 4],
                    "indoor": k % 3 == 1})
    return out


def write(path, doc):
    with open(path, "w", encoding="ascii", newline="\n") as f:
        f.write("{\n")
        f.write(",\n".join(vec.json_value(k) + ": " + vec.json_value(v) for k, v in doc))
        f.write("\n}\n")


def main():
    vector_dir = sys.argv[1]
    with open(os.path.join(vector_dir, "tile_canberra_z13.json"), encoding="ascii") as f:
        base = json.load(f)
    carriers = add_calibration(base["carriers"])
    out_dir = os.path.join(vector_dir, "calibration")
    os.makedirs(out_dir, exist_ok=True)

    pts = points()
    for p in pts:
        p["rsrp"] = [r6(v) for v in ref.predict_point(carriers, [], p["lat"], p["lon"], p["area"], p["indoor"])]
    write(os.path.join(out_dir, "point_canberra.json"), [("carriers", carriers), ("points", pts)])
    print("point_canberra.json: %d carriers x %d points" % (carriers["n"], len(pts)), file=sys.stderr)

    z, x, y, grid = base["z"], base["x"], base["y"], base["grid"]
    res = rasters()
    out = ref.render_tile(carriers, [], z, x, y, grid, base["area"], False, -118.0, residuals=res)
    changed = sum(1 for a, b in zip(out["rsrpCodes"], base["rsrpCodes"]) if a != b)
    doc = [
        ("z", z), ("x", x), ("y", y), ("grid", grid), ("area", base["area"]), ("indoor", False), ("thrDbm", -118.0),
        ("patterns", []), ("carriers", carriers),
        ("residuals", [{"mask": mask, "codes": b64(codes)} for codes, mask in res]),
        ("evaluated", out["evaluated"]), ("pixelsChangedFromUncalibrated", changed),
        ("rsrpCodes", out["rsrpCodes"]), ("sinrCodes", out["sinrCodes"]),
        ("probCodes", out["probCodes"]), ("best", out["best"]),
    ]
    write(os.path.join(out_dir, "tile_canberra_z13_calibrated.json"), doc)
    print("tile_canberra_z13_calibrated.json: %d evaluated, %d pixels differ from the uncalibrated tile"
          % (out["evaluated"], changed), file=sys.stderr)


if __name__ == "__main__":
    main()
