"""Reference implementation of the Signal Atlas "Import rules" 1-11.

Usage: python3 -I tools/acma/reference.py <rrl.zip> <expected.json>

Stdlib only. Reads client/licence/site/antenna/device_details from an ACMA RRL
zip and writes the ImportReport counts as JSON (sorted keys, indent 2).
"""
import csv
import io
import json
import math
import re
import sys
import time
import zipfile

NETWORK_BY_LICENSEE = {
    "telstra limited": "TELSTRA",
    "telstra 3g spectrum holdings pty ltd": "TELSTRA",
    "optus mobile pty limited": "OPTUS",
    "vodafone hutchison australia pty limited": "TPG",
    "vodafone australia pty limited": "TPG",
    "tpg internet pty ltd": "TPG",
    "mobile jv pty limited": "OPTUS_TPG_JV",
    "nbn co limited": "NBN",
}
LICENCE_TYPES = {"Spectrum", "PTS"}
LINEAR_UNITS = ("W", "mW", "kW")
INT32_MAX = 2**31 - 1
INT64_MAX = 2**63 - 1

# (lo MHz, hi MHz, band, tech); inclusive, first match wins. B5/B26 are NR for TELSTRA.
BANDS = [
    (758, 803, "B28", "LTE"),
    (859, 868.999, "B26", "LTE"),
    (869, 894, "B5", "LTE"),
    (925, 960, "B8", "LTE"),
    (1805, 1880, "B3", "LTE"),
    (2110, 2170, "B1", "LTE"),
    (2300, 2400, "B40", "LTE"),
    (2620, 2690, "B7", "LTE"),
    (3300, 3800, "n78", "NR"),
    (3800.001, 4200, "n77", "NR"),
    (24250, 27499.999, "n258", "NR"),
    (27500, 29500, "n257", "NR"),
]


def read_csv(zf, name):
    """Yield dict rows keyed by header name (rule 1)."""
    with zf.open(name) as raw:
        text = io.TextIOWrapper(raw, encoding="iso-8859-1", newline="")
        yield from csv.DictReader(text)


def num(s):
    """Parse a finite float, or None when blank/unparseable."""
    if s is None:
        return None
    s = s.strip()
    if not s:
        return None
    try:
        v = float(s)
    except ValueError:
        return None
    return v if math.isfinite(v) else None


def parse_id(s, hi):
    """Rule 11: a decimal integer in 0..hi after trimming (leading zeros allowed), else None.
    ASCII digits only (str.isdigit would also accept superscripts in ISO-8859-1)."""
    s = s.strip() if s is not None else ""
    if not s or any(c not in "0123456789" for c in s):
        return None
    v = int(s)
    return v if v <= hi else None


def band_for(freq_mhz, network):
    if freq_mhz is None:
        return None
    for lo, hi, band, tech in BANDS:
        if lo <= freq_mhz <= hi:
            if network == "TELSTRA" and band in ("B5", "B26"):
                return ("n" + band[1:], "NR")
            return (band, tech)
    return None


def to_dbm(value, unit):
    """Convert value in unit to dBm; None if unit unknown or linear value <= 0."""
    if unit in LINEAR_UNITS:
        if value <= 0:
            return None
        base = 10 * math.log10(value)
        return base + {"W": 30, "mW": 0, "kW": 60}[unit]
    if unit == "dBW":
        return value + 30
    if unit == "dBm":
        return value
    return None


def eirp_dbm(dev, antennas):
    """Rule 5. A non-blank EIRP that is <= 0 W counts as "not supplied" (the real
    export carries EIRP=0 W beside a valid TRANSMITTER_POWER) and falls through to
    the derived path; any other unusable value (bad number or unit) is a skip."""
    eirp = dev["EIRP"].strip()
    if eirp:
        v = num(eirp)
        unit = dev["EIRP_UNIT"].strip()
        if v is None:
            return None
        if unit in LINEAR_UNITS and v <= 0:
            eirp = ""  # treated as absent -> derive from transmitter power
        else:
            d = to_dbm(v, unit)
            return None if d is None else round(d, 2)
    tp = dev["TRANSMITTER_POWER"].strip()
    if not tp:
        return None
    v = num(tp)
    d = None if v is None else to_dbm(v, dev["TRANSMITTER_POWER_UNIT"].strip())
    if d is None:
        return None
    ant = antennas.get(parse_id(dev["ANTENNA_ID"], INT32_MAX))
    gain = ant["gain"] if ant and ant["gain"] is not None else 0.0
    loss = num(dev["FEEDER_LOSS"]) or 0.0
    return round(d + gain - loss, 2)


# Rule 12: DAS site classification (tower DB schema v2 `site.site_kind`: 0 MACRO, 1 DAS_BUILDING, 2 DAS_TUNNEL).
TUNNEL_RE = re.compile(r"\b(tunnel|tunl)\b", re.I | re.A)
DAS_NAME_RE = re.compile(
    r"\b(ibc|in[- ]?building|das|distributed antenna|shopping (centre|center)|westfield|stadium|arena|station"
    r"|airport terminal|hospital|university|plaza|tower \d+(?!\.\d)|level \d+(?!\.\d)|carpark)\b",
    re.I | re.A,
)
# Phrases blanked out of the name before DAS_NAME_RE: macro structure types ("Lattice Tower 21", "50m tower 39 ...")
# and street / rural "station" uses ("Station Pier", "Repeater Station Road", "pump station").
DAS_NAME_NEG_RE = re.compile(
    r"\b(lattice|monopole|guyed|mast|pole|steel|\d+ ?m) tower\b"
    r"|\bstation (road|rd|street|st|avenue|ave|pier|drive|dr|lane|ln|way|parade|pde|creek|hill|point)\b"
    r"|\b(repeater|pump|pumping|fire|police|petrol|service|power|cattle|sheep|weather) station\b",
    re.I | re.A,
)
MACRO, DAS_BUILDING, DAS_TUNNEL = 0, 1, 2


def site_kind(name, max_height_m, max_eirp_dbm, all_wide):
    """Rule 12. `all_wide`: every sector is omni (null azimuth) or has an antenna with H_BW >= 90."""
    if TUNNEL_RE.search(name):
        return DAS_TUNNEL
    if DAS_NAME_RE.search(DAS_NAME_NEG_RE.sub(" ", name)) or (max_height_m <= 6 and max_eirp_dbm <= 40 and all_wide):
        return DAS_BUILDING
    return MACRO


def site_kinds(data):
    """Site id -> site_kind for every kept site of load()'s result."""
    stats = {}  # site -> [max height, max eirp, all_wide]
    for c in data["carriers"]:
        hbw = data["antennas"].get(c["antenna_id"], {}).get("hbw")
        wide = c["azimuth_deg"] is None or (hbw is not None and hbw >= 90)
        st = stats.setdefault(c["site_id"], [c["height_m"], c["eirp_dbm"], True])
        st[0] = max(st[0], c["height_m"])
        st[1] = max(st[1], c["eirp_dbm"])
        st[2] = st[2] and wide
    return {sid: site_kind(data["site_names"][sid], *stats[sid]) for sid in stats}


def round1(x):
    """Rule 6 rounding: floor(x * 10 + 0.5) / 10 in double, -0.0 -> 0.0."""
    return math.floor(x * 10 + 0.5) / 10 + 0.0


def positive_or_none(s):
    v = num(s)
    return None if v is None or v == 0 else v


def run(zip_path):
    """The ImportReport counts for an RRL zip."""
    return load(zip_path)["report"]


def load(zip_path):
    """Apply the import rules; return {"report", "carriers", "sites", "antennas"}.

    "carriers" lists the kept carriers in export order as dicts (sdd_id, site_id, sector_id,
    network, tech, band, freq_hz, bw_hz, eirp_dbm, tilt_deg, height_m, azimuth_deg,
    antenna_id), de-duplicated on SDD_ID with the first device winning (as the Kotlin
    importer's INSERT OR IGNORE). "sites" maps kept site id -> (lat, lon); "site_names" maps
    kept site id -> trimmed NAME (last duplicate row wins); "antennas" maps
    antenna id -> {"gain", "f2b", "hbw"} (None = not supplied).
    """
    zf = zipfile.ZipFile(zip_path)

    network_by_client = {}
    for r in read_csv(zf, "client.csv"):
        net = NETWORK_BY_LICENSEE.get(r["LICENCEE"].strip().lower())
        cid = parse_id(r["CLIENT_NO"], INT32_MAX)
        if net and cid is not None:
            network_by_client[cid] = net

    licence_network = {}
    for r in read_csv(zf, "licence.csv"):
        if r["LICENCE_TYPE_NAME"].strip() in LICENCE_TYPES:
            net = network_by_client.get(parse_id(r["CLIENT_NO"], INT32_MAX))
            if net:
                licence_network[r["LICENCE_NO"].strip()] = net

    sites = {}
    site_names = {}
    for r in read_csv(zf, "site.csv"):
        sid = parse_id(r["SITE_ID"], INT32_MAX)
        lat, lon = num(r["LATITUDE"]), num(r["LONGITUDE"])
        if sid is not None and lat is not None and lon is not None and -55 <= lat <= -9 and 72 <= lon <= 169:
            sites[sid] = (lat, lon)
            site_names[sid] = r["NAME"].strip()

    antennas = {}
    for r in read_csv(zf, "antenna.csv"):
        aid = parse_id(r["ANTENNA_ID"], INT32_MAX)
        if aid is None:
            continue
        antennas[aid] = {
            "gain": positive_or_none(r["GAIN"]),
            "f2b": positive_or_none(r["FRONT_TO_BACK"]),
            "hbw": positive_or_none(r["H_BEAMWIDTH"]),
        }

    skipped_band = skipped_power = skipped_site = skipped_malformed = 0
    sector_ids = {}          # key -> id (first-seen order)
    sector_site = {}         # id -> site id
    sector_antenna = {}      # id -> antenna id or None
    carrier_count = {}       # network -> count
    carriers = {}            # sdd id -> kept carrier (first device wins)
    for dev in read_csv(zf, "device_details.csv"):
        if dev["DEVICE_TYPE"].strip() != "T":
            continue
        network = licence_network.get(dev["LICENCE_NO"].strip())
        if network is None:
            continue
        if parse_id(dev["SDD_ID"], INT64_MAX) is None:
            skipped_malformed += 1
            continue
        freq = num(dev["FREQUENCY"])
        if band_for(None if freq is None else freq / 1e6, network) is None:
            skipped_band += 1
            continue
        eirp = eirp_dbm(dev, antennas)
        if eirp is None:
            skipped_power += 1
            continue
        site_id = parse_id(dev["SITE_ID"], INT32_MAX)
        if site_id is None or site_id not in sites:
            skipped_site += 1
            continue
        az = num(dev["AZIMUTH"])
        az = None if az is None else round1(az)
        h = num(dev["HEIGHT"])
        h = 0.0 if h is None else round1(h)
        ant_id = parse_id(dev["ANTENNA_ID"], INT32_MAX)  # blank or invalid -> null antenna
        key = (site_id, network, ant_id, az, h)
        if key not in sector_ids:
            sid = len(sector_ids) + 1
            sector_ids[key] = sid
            sector_site[sid] = site_id
            sector_antenna[sid] = ant_id
        carrier_count[network] = carrier_count.get(network, 0) + 1
        sdd = parse_id(dev["SDD_ID"], INT64_MAX)
        if sdd not in carriers:
            band, tech = band_for(freq / 1e6, network)
            bw = num(dev["BANDWIDTH"])
            carriers[sdd] = {
                "sdd_id": sdd, "site_id": site_id, "sector_id": sector_ids[key],
                "network": network, "tech": tech, "band": band,
                "freq_hz": math.floor(freq + 0.5),
                "bw_hz": 0 if bw is None else math.floor(bw + 0.5),
                "eirp_dbm": eirp, "tilt_deg": num(dev["TILT"]) or 0.0,
                "height_m": h, "azimuth_deg": az, "antenna_id": ant_id,
            }

    kept_antennas = {a for a in sector_antenna.values() if a is not None and a in antennas}
    report = {
        "sites": len(set(sector_site.values())),
        "sectors": len(sector_ids),
        "carriers": sum(carrier_count.values()),
        "antennas": len(kept_antennas),
        "skippedUnknownBand": skipped_band,
        "skippedNoPower": skipped_power,
        "skippedBadSite": skipped_site,
        "skippedMalformed": skipped_malformed,
        "carriersByNetwork": {k: v for k, v in carrier_count.items() if v > 0},
    }
    kept_sites = set(sector_site.values())
    return {
        "report": report,
        "carriers": list(carriers.values()),
        "sites": {s: sites[s] for s in sorted(kept_sites)},
        "antennas": antennas,
        "site_names": site_names,
    }


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: reference.py <rrl.zip> <expected.json>")
    t0 = time.time()
    report = run(sys.argv[1])
    elapsed = time.time() - t0
    with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as f:
        json.dump(report, f, sort_keys=True, indent=2)
        f.write("\n")
    print("reference wall-clock: %.1f s" % elapsed, file=sys.stderr)


if __name__ == "__main__":
    main()
