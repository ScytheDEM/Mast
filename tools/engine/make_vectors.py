"""Write the engine test vectors from tools/engine/reference.py.

Usage: python3 -I tools/engine/make_vectors.py <rrl_fixture.zip> <out_dir>

Writes pathloss.csv, antenna.csv, patterns.csv, eirp_re.csv, tile_canberra_z13.json,
tile_canberra_z13_urban_indoor_{hi,mid}.json, tile_canberra_z13_carrier_area.json and tile_edge_z12.json (the first fringe tile found outward from Canberra) into
<out_dir> (normally engine/host/test/vectors). Floats are written with 6 decimals, "nan" (CSV) or
null (JSON) means "not supplied". Output is deterministic.
"""
import importlib.util
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
acma = _load("acma_reference", os.path.join("..", "acma", "reference.py"))

NETWORK_ORDINAL = {"TELSTRA": 0, "OPTUS": 1, "TPG": 2, "OPTUS_TPG_JV": 3, "NBN": 4}  # core.model.Network
TECH_ORDINAL = {"LTE": 0, "NR": 1}                                                  # core.model.Tech
TILE_POINT = (-35.2809, 149.1300)
TILE_Z = 13
FRINGE_Z = 12


def fmt(v):
    if v is None or (isinstance(v, float) and not math.isfinite(v)):
        return "nan"
    if isinstance(v, float):
        return "%.6f" % v
    return str(v)


def r6(v):
    """The value exactly as written with 6 decimals (so the renderer uses what the JSON holds)."""
    return None if v is None else float("%.6f" % v)


def write_csv(path, header, rows):
    with open(path, "w", encoding="ascii", newline="\n") as f:
        f.write(",".join(header) + "\n")
        for row in rows:
            f.write(",".join(fmt(v) for v in row) + "\n")


def pathloss_rows():
    for f in (700.0, 900.0, 1800.0, 2100.0, 2600.0, 3500.0, 26000.0):
        for d in (0.05, 0.3, 1.0, 3.0, 10.0, 30.0):
            for h in (10.0, 30.0, 60.0):
                for area in ref.AREAS:
                    pl, sigma, model = ref.path_loss(f, d, h, area)
                    yield (f, d, h, area, model, pl, sigma)


def patterns():
    """Two synthetic relative patterns (quantised 0.25 dB): a 65-degree and an asymmetric 33-degree."""
    p1 = [min(ref.round_half_up(4 * 12 * (ref.wrap180(float(k)) / 65.0) ** 2), 100) for k in range(360)]
    p2 = [min(ref.round_half_up(4 * 12 * (ref.wrap180(float(k)) / 33.0) ** 2), 120) + (k % 3) for k in range(360)]
    return [p1, p2]


def antenna_rows(pats):
    def row(bearing, az, hbw, fb, gain, tilt, h, d, f, pid=0, peak=0):
        att = pats[pid - 1] if pid else None
        a = ref.antenna_attenuation(bearing, d, az, hbw, fb, gain, tilt, h, f, att, peak)
        return (bearing, ref.elevation_deg(h, d), az, hbw, fb, gain, tilt, h, d, a, f, pid, peak)

    for bearing in (0.0, 60.0, 90.0, 180.0, 270.0, 359.6):
        for az in (None, 0.0, 90.0, 300.0):
            for hbw, fb, gain in ((None, None, None), (65.0, 25.0, 17.0), (33.0, 30.0, 21.0), (90.0, 20.0, 8.0)):
                for tilt in (0.0, 5.0, -2.0):
                    for h in (10.0, 30.0, 60.0):
                        for d in (20.0, 200.0, 2000.0, 20000.0):
                            yield row(bearing, az, hbw, fb, gain, tilt, h, d, 900.0)
    # Height-band edges and clamping, and the +2 degree millimetre-wave default tilt.
    for f in (3500.0, 24250.0, 26000.0):
        for h in (0.0, 2.0, 14.9, 15.0, 29.9, 30.0, 50.0, 50.1, 200.0):
            for d in (5.0, 100.0, 1000.0):
                yield row(30.0, 0.0, 65.0, 25.0, 17.0, 0.0, h, d, f)
    # HRP patterns: rounding of the bearing, the peak offset and wrap-around.
    for pid in (1, 2):
        for peak in (0, 37, 300):
            for az in (None, 120.0):
                for bearing in [float(b) for b in range(0, 360, 15)] + [0.5, 0.49, 359.5, 359.49, 37.5]:
                    yield row(bearing, az, 65.0, 25.0, 17.0, 0.0, 30.0, 200.0, 900.0, pid, peak)


def eirp_rows():
    cases = []
    lte_bw = [1_400_000, 3_000_000, 5_000_000, 10_000_000, 15_000_000, 20_000_000,
              3_840_000, 4_900_000, 9_800_000, 14_900_000, 0, 2_000_000]
    cases += [("LTE", f, bw) for f in (900.0, 2655.0) for bw in lte_bw]
    nr15 = [m * 1_000_000 for m in (5, 10, 15, 20, 25, 30, 40, 50)] + [7_000_000, 0]
    cases += [("NR", f, bw) for f in (700.0, 872.5) for bw in nr15]
    nr30 = [m * 1_000_000 for m in (10, 15, 20, 25, 30, 40, 50, 60, 70, 80, 90, 100)] + [95_000_000, 5_000_000, 0]
    cases += [("NR", 3500.0, bw) for bw in nr30]
    nr120 = [m * 1_000_000 for m in (50, 100, 200, 400)] + [800_000_000, 20_000_000, 0]
    cases += [("NR", 26000.0, bw) for bw in nr120]
    cases += [("NR", f, 20_000_000) for f in (2299.999, 2300.0, 7125.0, 7200.0, 24249.999, 24250.0)]
    cases += [("NR", f, 100_000_000) for f in (2299.999, 2300.0, 24250.0)]
    for tech, f, bw in cases:
        for eirp in (60.0, 43.21):
            yield (tech, f, bw, eirp, ref.eirp_per_re(eirp, tech, f, bw))


def family(f_mhz):
    """Path-loss model family of a frequency: Hata, COST-231 or TR 38.901."""
    return "<1500" if f_mhz < 1500.0 else "1500-2200" if f_mhz <= 2200.0 else ">2200"


def tile_carriers(model, networks, families=None):
    sites = model["sites"]
    antennas = model["antennas"]
    kept = sorted((c for c in model["carriers"] if c["network"] in networks
                   and (families is None or family(c["freq_hz"] / 1e6) in families)),
                  key=lambda c: (c["site_id"], c["sdd_id"]))
    cols = {k: [] for k in ("sddId", "band", "lat", "lon", "hTx", "az", "tilt", "eirpDbm", "bwHz", "eirpRe",
                            "freqMhz", "hBw", "fb", "gain", "rMaxKm", "tech", "sectorKey", "freqKey",
                            "hrpId", "hrpPeak")}
    for c in kept:
        lat, lon = sites[c["site_id"]]
        ant = antennas.get(c["antenna_id"]) or {"gain": None, "f2b": None, "hbw": None}
        f_mhz = r6(c["freq_hz"] / 1e6)
        e_re = r6(ref.eirp_per_re(c["eirp_dbm"], c["tech"], f_mhz, c["bw_hz"]))
        h = r6(c["height_m"])
        cols["sddId"].append(c["sdd_id"])
        cols["band"].append(c["band"])
        cols["lat"].append(r6(lat))
        cols["lon"].append(r6(lon))
        cols["hTx"].append(h)
        cols["az"].append(r6(c["azimuth_deg"]))
        cols["tilt"].append(r6(c["tilt_deg"]))
        cols["eirpDbm"].append(r6(c["eirp_dbm"]))
        cols["bwHz"].append(c["bw_hz"])
        cols["eirpRe"].append(e_re)
        cols["freqMhz"].append(f_mhz)
        cols["hBw"].append(r6(ant["hbw"]))
        cols["fb"].append(r6(ant["f2b"]))
        cols["gain"].append(r6(ant["gain"]))
        cols["rMaxKm"].append(r6(ref.culling_range_km(e_re, f_mhz, h)))  # rule 9: max over area classes
        cols["tech"].append(TECH_ORDINAL[c["tech"]])
        cols["sectorKey"].append(c["sector_id"])
        cols["freqKey"].append(NETWORK_ORDINAL[c["network"]] * 1_000_000 + (c["freq_hz"] + 50_000) // 100_000)
        cols["hrpId"].append(0)
        cols["hrpPeak"].append(0)
    cols["n"] = len(kept)
    return cols


def json_value(v):
    if isinstance(v, bool):
        return "true" if v else "false"
    if v is None or (isinstance(v, float) and not math.isfinite(v)):
        return "null"
    if isinstance(v, float):
        return "%.6f" % v
    if isinstance(v, int):
        return str(v)
    if isinstance(v, str):
        return '"' + v.replace("\\", "\\\\").replace('"', '\\"') + '"'
    if isinstance(v, (list, tuple)):
        return "[" + ",".join(json_value(x) for x in v) + "]"
    if isinstance(v, dict):
        return "{" + ",".join(json_value(k) + ":" + json_value(x) for k, x in v.items()) + "}"
    raise TypeError(type(v))


def with_carrier_areas(model, carriers):
    """Adds the `carrierArea` column (rule 5 amended 2026-10-24: AREAS index from the sites within 3 km of each
    carrier's site, any network)."""
    sites = list(model["sites"].values())
    by_site = {}
    carriers["carrierArea"] = [ref.AREAS.index(by_site.setdefault((lat, lon), ref.site_area(sites, lat, lon)[0]))
                               for lat, lon in zip(carriers["lat"], carriers["lon"])]
    return carriers


def render(model, z, x, y, grid=None, networks=("TELSTRA",), area=None, indoor=False, families=None,
           carrier_areas=False):
    """Render a tile; `area` overrides the tile-centre class (the renderer's fallback, an input). `carrier_areas`
    gives every carrier its own class (rule 5 as amended)."""
    derived, n_sites = ref.tile_area(model["sites"].values(), z, x, y)
    area = area or derived
    grid = grid or (128 if z <= 13 else 256)
    carriers = tile_carriers(model, networks, families)
    if carrier_areas:
        carriers = with_carrier_areas(model, carriers)
    out = ref.render_tile(carriers, [], z, x, y, grid, area, indoor, -118.0)
    return {"area": area, "derivedArea": derived, "sites": n_sites, "grid": grid, "networks": list(networks),
            "families": None if families is None else list(families), "indoor": indoor, "carriers": carriers, "out": out}


def uncovered_share(out):
    return sum(1 for c in out["out"]["rsrpCodes"] if c == 0) / 65536.0


def find_fringe_tile(model, z, max_ring=40):
    """The first z tile, in rings outward from the tile containing TILE_POINT (ring by ring, then
    row-major), with 20-80% of pixels at RSRP code 0. A 16-point grid screens candidates first."""
    cx, cy = ref.tile_for(TILE_POINT[0], TILE_POINT[1], z)
    for r in range(max_ring + 1):
        ring = sorted((dy, dx) for dy in range(-r, r + 1) for dx in range(-r, r + 1) if max(abs(dx), abs(dy)) == r)
        for dy, dx in ring:
            x, y = cx + dx, cy + dy
            if not 0.25 <= uncovered_share(render(model, z, x, y, 16)) <= 0.75:
                continue
            result = render(model, z, x, y)
            if 0.2 <= uncovered_share(result) <= 0.8:
                return x, y, result
    raise ValueError("no fringe tile within %d rings" % max_ring)


def write_tile(path, z, x, y, rendered):
    area, n_sites, grid, carriers, out = (rendered[k] for k in ("area", "sites", "grid", "carriers", "out"))
    selection = {"networks": rendered["networks"], "hrp": False,
                 "source": "core/acma/src/test/resources/acma/fixture.zip"}
    if rendered["families"] is not None:
        selection["families"] = rendered["families"]
    if area != rendered["derivedArea"]:
        selection["areaForced"] = True
        selection["derivedArea"] = rendered["derivedArea"]
    doc = [
        ("z", z), ("x", x), ("y", y), ("grid", grid), ("area", area), ("sitesWithin3km", n_sites),
        ("indoor", rendered["indoor"]), ("thrDbm", -118.0),
        ("selection", selection),
        ("patterns", []), ("carriers", carriers), ("evaluated", out["evaluated"]),
        ("rsrpCodes", out["rsrpCodes"]), ("sinrCodes", out["sinrCodes"]),
        ("probCodes", out["probCodes"]), ("best", out["best"]),
    ]
    with open(path, "w", encoding="ascii", newline="\n") as f:
        f.write("{\n")
        f.write(",\n".join(json_value(k) + ": " + json_value(v) for k, v in doc))
        f.write("\n}\n")
    covered = sum(1 for c in out["rsrpCodes"] if c)
    print("%s: z%d/%d/%d area %s (%d sites within 3 km), indoor %s, %s: %d carriers, %d evaluated, "
          "%d/65536 pixels covered, RSRP codes %d..%d"
          % (os.path.basename(path), z, x, y, area, n_sites, rendered["indoor"], "+".join(rendered["networks"]),
             carriers["n"], out["evaluated"], covered, min(out["rsrpCodes"]), max(out["rsrpCodes"])),
          file=sys.stderr)


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: make_vectors.py <rrl_fixture.zip> <out_dir>")
    fixture, out_dir = sys.argv[1], sys.argv[2]
    write_csv(os.path.join(out_dir, "pathloss.csv"),
              ["f_mhz", "d_km", "h_tx", "area", "model", "pl_db", "sigma_db"], pathloss_rows())
    pats = patterns()
    write_csv(os.path.join(out_dir, "patterns.csv"), ["id", "att"],
              [(i + 1, " ".join(str(q) for q in p)) for i, p in enumerate(pats)])
    write_csv(os.path.join(out_dir, "antenna.csv"),
              ["bearing", "elev_deg", "az", "hbw", "fb", "gain", "tilt", "h_tx", "d_m", "a_db",
               "f_mhz", "hrp_id", "hrp_peak"], antenna_rows(pats))
    write_csv(os.path.join(out_dir, "eirp_re.csv"),
              ["tech", "f_mhz", "bw_hz", "eirp_dbm", "eirp_re_dbm"], eirp_rows())
    model = acma.load(fixture)
    x, y = ref.tile_for(TILE_POINT[0], TILE_POINT[1], TILE_Z)
    write_tile(os.path.join(out_dir, "tile_canberra_z13.json"), TILE_Z, x, y, render(model, TILE_Z, x, y))
    for suffix, fam in (("hi", ">2200"), ("mid", "1500-2200")):
        urban = render(model, TILE_Z, x, y, networks=("TELSTRA", "OPTUS"), area="URBAN", indoor=True,
                       families=(fam,))
        best = [b for b in urban["out"]["best"] if b != ref.NO_BEST]
        share = sum(1 for b in best if family(urban["carriers"]["freqMhz"][b]) == fam) / max(len(best), 1)
        if not best or share < 0.9:
            raise ValueError("%s tile: best server from %s on only %.1f%% of covered pixels" % (suffix, fam, share * 100))
        write_tile(os.path.join(out_dir, "tile_canberra_z13_urban_indoor_%s.json" % suffix), TILE_Z, x, y, urban)
    per_carrier = render(model, TILE_Z, x, y, networks=("TELSTRA", "OPTUS"), carrier_areas=True)
    if ref.AREAS.index(per_carrier["area"]) in per_carrier["carriers"]["carrierArea"]:
        raise ValueError("carrier-area tile: some carrier has the tile's own class, so the vector cannot tell them apart")
    write_tile(os.path.join(out_dir, "tile_canberra_z13_carrier_area.json"), TILE_Z, x, y, per_carrier)
    x, y, rendered = find_fringe_tile(model, FRINGE_Z)
    write_tile(os.path.join(out_dir, "tile_edge_z12.json"), FRINGE_Z, x, y, rendered)


if __name__ == "__main__":
    main()
