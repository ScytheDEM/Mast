"""Write the terrain test vectors from tools/engine/terrain_reference.py.

Usage:
  python3 -I tools/engine/make_terrain_vectors.py fetch-dem <out_dir>
      Fetch the GLO-90 crop for the Canberra z12 tile + 40 km into <out_dir>/dem_canberra_glo90.json
      (network; done once, the file is committed).
  python3 -I tools/engine/make_terrain_vectors.py <rrl_fixture.zip> <out_dir>
      Offline: write profiles.csv, tables.json and tile_canberra_terrain_z12.json into <out_dir>
      (normally engine/host/test/vectors/terrain) from the committed crop. Output is deterministic.
"""
import base64
import importlib.util
import json
import math
import os
import struct
import sys


def _load(name, rel):
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), rel)
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


ter = _load("terrain_reference", "terrain_reference.py")
mv = _load("make_vectors", "make_vectors.py")
ref = mv.ref

TILE_POINT = (-35.2809, 149.1300)
TILE_Z = 12
MARGIN_M = 40000.0
DEM_FILE = "dem_canberra_glo90.json"
FREQS = (700.0, 1800.0, 3500.0)


def b64(data):
    return base64.b64encode(bytes(data)).decode("ascii")


def int16_b64(values):
    return b64(struct.pack("<%dh" % len(values), *values))


def load_dem(path):
    with open(path, encoding="ascii") as f:
        doc = json.load(f)
    n = doc["rows"] * doc["cols"]
    h = list(struct.unpack("<%dh" % n, base64.b64decode(doc["h"])))
    return doc, ter.DemGrid(doc["southIndex"], doc["westIndex"], doc["rows"], doc["cols"], h)


def crop_bounds(z, x, y, margin_m=MARGIN_M):
    """The tile's lat/lon box expanded by `margin_m`: latitude by margin / (R * pi/180), longitude by that
    over cos(expanded south edge) (the widest the margin gets inside the box, southern hemisphere)."""
    s, n, w, e = ref.tile_bounds(z, x, y)
    dlat = margin_m / (ref.EARTH_R * math.pi / 180.0)
    far = max(abs(s - dlat), abs(n + dlat))
    dlon = dlat / math.cos(math.radians(far))
    return s - dlat, n + dlat, w - dlon, e + dlon


# ---------------------------------------------------------------- profiles.csv

def _round_half_away(x):
    return int(math.floor(abs(x) + 0.5)) * (1 if x >= 0 else -1)


def profile_cases():
    """(case, d_m, ground heights (m) at the 90 m samples and the endpoint, tx AGL, rx AGL)."""
    def ground(d_m, fn):
        return [_round_half_away(fn(x)) for x in ter.profile_distances_m(d_m)]

    def ridge(x, centre, half_width, top, base):
        return base + max(0.0, top * (1.0 - abs(x - centre) / half_width))

    cases = []
    cases.append(("flat", 10000.0, ground(10000.0, lambda x: 600.0), 30.0, ter.H_RX))
    single = ground(10000.0, lambda x: 0.0)
    single[56] = 60                                     # one sample, d1 = 5040 m (the hand-checked knife edge)
    cases.append(("single_ridge", 10000.0, single, 30.0, ter.H_RX))
    cases.append(("double_ridge", 20000.0,
                  ground(20000.0, lambda x: max(ridge(x, 6300.0, 400.0, 120.0, 100.0),
                                                ridge(x, 14400.0, 300.0, 100.0, 100.0))), 30.0, ter.H_RX))
    cases.append(("valley", 15000.0,
                  ground(15000.0, lambda x: 500.0 - max(0.0, 200.0 * (1.0 - abs(x - 7500.0) / 4500.0))), 30.0,
                  ter.H_RX))
    # Mast to mast (60 m each end) over a 5 m hill: nu_max < -0.78 everywhere, so every loss is 0.
    cases.append(("los_hill", 10000.0, ground(10000.0, lambda x: ridge(x, 5000.0, 1000.0, 5.0, 0.0)), 60.0, 60.0))
    cases.append(("rural_60km", 60000.0,
                  ground(60000.0, lambda x: 300.0 + 60.0 * math.sin(2 * math.pi * x / 7300.0)
                         + 35.0 * math.sin(2 * math.pi * x / 2900.0 + 1.0)
                         + 20.0 * math.sin(2 * math.pi * x / 1100.0 + 2.0)), 40.0, ter.H_RX))
    return cases


def profile_rows():
    for name, d_m, h, h_tx, h_rx in profile_cases():
        dist = ter.profile_distances_m(d_m)
        assert len(dist) == len(h)
        for f in FREQS:
            act, ref_, terr = ter.terrain_excess(dist, [float(v) for v in h], float(h[0]), float(h[-1]), h_tx, h_rx, f)
            yield (name, f, d_m, ter.STEP_M, float(h[0]), float(h[-1]), h_tx, h_rx, " ".join(str(v) for v in h),
                   act, ref_, terr)


# ---------------------------------------------------------------- tables.json (synthetic DEM)

SYN_SOUTH, SYN_WEST, SYN_N = -42600, 178680, 361      # -35.5, 148.9; 0.3 x 0.3 degree
SYN_SITES = (("A", -35.35, 149.05, 30.0, 1800.0), ("B", -35.47, 148.93, 45.0, 700.0))


def synthetic_dem():
    """Rolling hills, a north-south range east of site A and a lake (0 m) to the north-west."""
    h = []
    for r in range(SYN_N):
        lat = (SYN_SOUTH + r) / 1200.0
        for c in range(SYN_N):
            lon = (SYN_WEST + c) / 1200.0
            v = 550.0 + 80.0 * math.sin(lat * 157.0) * math.cos(lon * 113.0) + 30.0 * math.sin(lon * 611.0)
            v += 350.0 * max(0.0, 1.0 - abs(lon - 149.09 - 0.05 * (lat + 35.35)) / 0.012)
            if (lat + 35.26) ** 2 + ((lon - 148.96) * 0.82) ** 2 < 0.03 ** 2:
                v = 0.0
            h.append(_round_half_away(v))
    return ter.DemGrid(SYN_SOUTH, SYN_WEST, SYN_N, SYN_N, h)


def dem_doc(dem):
    return [("stepsPerDegree", ter.STEPS_PER_DEG), ("southIndex", dem.south_index), ("westIndex", dem.west_index),
            ("rows", dem.rows), ("cols", dem.cols),
            ("layout", "node (row, col) at lat (southIndex + row)/1200, lon (westIndex + col)/1200; row 0 is "
                       "the south edge; int16 little-endian metres, row-major, base64"),
            ("h", int16_b64(dem.h))]


def table_doc(codes, mask):
    complete = sum(bin(b).count("1") for b in mask)
    return [("complete", complete), ("nonZero", sum(1 for c in codes if c)), ("maxCode", max(codes)),
            ("capCells", sum(1 for c in codes if c == ter.CODE_MAX)),
            ("codes", b64(codes)), ("mask", b64(mask))]


TABLE_LAYOUT = ("codes[b * 256 + j]: uint8 L_terr / 0.25 dB (cap 255) on radial b (bearing b deg true) at "
                "r_j = 50 m * 1600^(j/255); mask: bit k = byte k >> 3, bit k & 7 (LSB first), 1 = profile "
                "complete; codes are 0 where the mask is 0; base64")


# ---------------------------------------------------------------- the Canberra terrain tile

def tile_tables(carriers, dem):
    """Rule 6 grouping: one table per (site, 10 MHz frequency key, 1 m height key) in first-carrier order,
    built with the key height and the key frequency."""
    keys = []
    groups = {}
    for i in range(carriers["n"]):
        key = (carriers["lat"][i], carriers["lon"][i], ter.frequency_key_mhz(carriers["freqMhz"][i]),
               ter.height_key_m(carriers["hTx"][i]))
        if key not in groups:
            groups[key] = []
            keys.append(key)
        groups[key].append(i)
    tables = []
    carrier_table = [-1] * carriers["n"]
    for t, key in enumerate(keys):
        lat, lon, fkey, h_tx = key
        codes, mask = ter.polar_table(dem, lat, lon, h_tx, float(fkey))
        for i in groups[key]:
            carrier_table[i] = t
        tables.append({"key": key, "hTx": h_tx, "codes": codes, "mask": mask, "carriers": groups[key],
                       "ground": dem.height(lat, lon)})
        print("table %d/%d: site (%.6f, %.6f) %d MHz h %d: %d complete, %d non-zero, max %d, %d at the cap"
              % (t + 1, len(keys), lat, lon, fkey, h_tx, sum(bin(b).count("1") for b in mask),
                 sum(1 for c in codes if c), max(codes), sum(1 for c in codes if c == ter.CODE_MAX)),
              file=sys.stderr)
    return tables, carrier_table


def write_json(path, doc):
    with open(path, "w", encoding="ascii", newline="\n") as f:
        f.write("{\n")
        f.write(",\n".join(mv.json_value(k) + ": " + json_value_nested(v) for k, v in doc))
        f.write("\n}\n")


def json_value_nested(v):
    """mv.json_value, with lists of (key, value) pairs written as ordered objects."""
    if isinstance(v, list) and v and all(isinstance(x, tuple) and len(x) == 2 and isinstance(x[0], str) for x in v):
        return "{" + ",".join(mv.json_value(k) + ":" + json_value_nested(x) for k, x in v) + "}"
    if isinstance(v, list) and v and isinstance(v[0], list):
        return "[" + ",".join(json_value_nested(x) for x in v) + "]"
    return mv.json_value(v)


def write_tile(path, model, dem_doc_src, dem):
    x, y = ref.tile_for(TILE_POINT[0], TILE_POINT[1], TILE_Z)
    area, n_sites = ref.tile_area(model["sites"].values(), TILE_Z, x, y)
    grid = 128
    carriers = mv.tile_carriers(model, ("TELSTRA",))
    tables, carrier_table = tile_tables(carriers, dem)

    def terrain(i, bearing, d2):
        t = carrier_table[i]
        return ter.table_lookup(tables[t]["codes"], tables[t]["mask"], bearing, d2)

    out = ref.render_tile(carriers, [], TILE_Z, x, y, grid, area, False, -118.0, terrain)
    quick = ref.render_tile(carriers, [], TILE_Z, x, y, grid, area, False, -118.0)
    covered = sum(1 for c in out["rsrpCodes"] if c)
    full = sum(1 for c, k in zip(out["rsrpCodes"], out["terrainComplete"]) if c and k)
    changed = sum(1 for a, b in zip(out["rsrpCodes"], quick["rsrpCodes"]) if a != b)
    terrain_doc = [
        ("dem", [("file", DEM_FILE), ("southIndex", dem.south_index), ("westIndex", dem.west_index),
                 ("rows", dem.rows), ("cols", dem.cols), ("cogs", [s["name"] + " " + s["etag"]
                                                                   for s in dem_doc_src["cogs"]])]),
        ("tableLayout", TABLE_LAYOUT),
        ("carrierTable", carrier_table),
        ("tables", [[("siteLat", t["key"][0]), ("siteLon", t["key"][1]), ("freqKeyMhz", t["key"][2]),
                     ("hTxAgl", t["key"][3]), ("groundM", t["ground"])] + table_doc(t["codes"], t["mask"])
                    for t in tables]),
        ("capCells", sum(1 for t in tables for c in t["codes"] if c == ter.CODE_MAX)),
        ("coveredPixels", covered), ("terrainPixels", full),
        ("accuracy", "TERRAIN" if covered and full >= 0.95 * covered else "QUICK"),
        ("pixelsChangedFromQuick", changed),
    ]
    doc = [
        ("z", TILE_Z), ("x", x), ("y", y), ("grid", grid), ("area", area), ("sitesWithin3km", n_sites),
        ("indoor", False), ("thrDbm", -118.0),
        ("selection", [("networks", ["TELSTRA"]), ("hrp", False),
                       ("source", "core/acma/src/test/resources/acma/fixture.zip")]),
        ("patterns", []), ("carriers", carriers), ("terrain", terrain_doc), ("evaluated", out["evaluated"]),
        ("rsrpCodes", out["rsrpCodes"]), ("sinrCodes", out["sinrCodes"]),
        ("probCodes", out["probCodes"]), ("best", out["best"]),
    ]
    write_json(path, doc)
    print("%s: z%d/%d/%d %s, %d carriers, %d tables, %d evaluated, %d/65536 covered, %d with full profiles, "
          "%d pixels differ from the quick model" % (os.path.basename(path), TILE_Z, x, y, area, carriers["n"],
                                                     len(tables), out["evaluated"], covered, full, changed),
          file=sys.stderr)


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "fetch-dem":
        crop = _load("glo90_crop", "glo90_crop.py")
        x, y = ref.tile_for(TILE_POINT[0], TILE_POINT[1], TILE_Z)
        crop.write_crop(os.path.join(sys.argv[2], DEM_FILE), *crop_bounds(TILE_Z, x, y))
        return
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    fixture, out_dir = sys.argv[1], sys.argv[2]
    mv.write_csv(os.path.join(out_dir, "profiles.csv"),
                 ["case", "f_mhz", "total_dist_m", "step_m", "g_tx", "g_rx", "h_tx_agl", "h_rx_agl", "heights",
                  "l_uc_actual", "l_uc_ref", "l_terr"],
                 profile_rows())
    dem = synthetic_dem()
    sites = []
    for name, lat, lon, h_tx, f in SYN_SITES:
        codes, mask = ter.polar_table(dem, lat, lon, h_tx, f)
        sites.append([("name", name), ("siteLat", lat), ("siteLon", lon), ("hTxAgl", h_tx), ("freqMhz", f),
                      ("groundM", dem.height(lat, lon))] + table_doc(codes, mask))
        print("tables.json %s: %s" % (name, ", ".join("%s %d" % kv for kv in sites[-1][6:10])), file=sys.stderr)
    write_json(os.path.join(out_dir, "tables.json"),
               [("dem", dem_doc(dem)), ("tableLayout", TABLE_LAYOUT), ("tables", sites)])
    dem_src, real = load_dem(os.path.join(out_dir, DEM_FILE))
    write_tile(os.path.join(out_dir, "tile_canberra_terrain_z12.json"), mv.acma.load(fixture), dem_src, real)


if __name__ == "__main__":
    main()
