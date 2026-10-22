"""Reference implementation of the Signal Atlas "Import rules" 1-10.

Usage: python3 -I tools/acma/reference.py <rrl.zip> <expected.json>

Stdlib only. Reads client/licence/site/antenna/device_details from an ACMA RRL
zip and writes the ImportReport counts as JSON (sorted keys, indent 2).
"""
import csv
import io
import json
import math
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
    ant = antennas.get(dev["ANTENNA_ID"].strip())
    gain = ant["gain"] if ant and ant["gain"] is not None else 0.0
    loss = num(dev["FEEDER_LOSS"]) or 0.0
    return round(d + gain - loss, 2)


def positive_or_none(s):
    v = num(s)
    return None if v is None or v == 0 else v


def run(zip_path):
    zf = zipfile.ZipFile(zip_path)

    network_by_client = {}
    for r in read_csv(zf, "client.csv"):
        net = NETWORK_BY_LICENSEE.get(r["LICENCEE"].strip().lower())
        if net:
            network_by_client[r["CLIENT_NO"].strip()] = net

    licence_network = {}
    for r in read_csv(zf, "licence.csv"):
        if r["LICENCE_TYPE_NAME"].strip() in LICENCE_TYPES:
            net = network_by_client.get(r["CLIENT_NO"].strip())
            if net:
                licence_network[r["LICENCE_NO"].strip()] = net

    sites = {}
    for r in read_csv(zf, "site.csv"):
        lat, lon = num(r["LATITUDE"]), num(r["LONGITUDE"])
        if lat is not None and lon is not None and -55 <= lat <= -9 and 72 <= lon <= 169:
            sites[r["SITE_ID"].strip()] = (lat, lon)

    antennas = {}
    for r in read_csv(zf, "antenna.csv"):
        antennas[r["ANTENNA_ID"].strip()] = {
            "gain": positive_or_none(r["GAIN"]),
            "f2b": positive_or_none(r["FRONT_TO_BACK"]),
            "hbw": positive_or_none(r["H_BEAMWIDTH"]),
        }

    skipped_band = skipped_power = skipped_site = 0
    sector_ids = {}          # key -> id (first-seen order)
    sector_site = {}         # id -> site id
    sector_antenna = {}      # id -> antenna id or None
    carrier_count = {}       # network -> count
    for dev in read_csv(zf, "device_details.csv"):
        if dev["DEVICE_TYPE"].strip() != "T":
            continue
        network = licence_network.get(dev["LICENCE_NO"].strip())
        if network is None:
            continue
        freq = num(dev["FREQUENCY"])
        if band_for(None if freq is None else freq / 1e6, network) is None:
            skipped_band += 1
            continue
        if eirp_dbm(dev, antennas) is None:
            skipped_power += 1
            continue
        site_id = dev["SITE_ID"].strip()
        if site_id not in sites:
            skipped_site += 1
            continue
        az = num(dev["AZIMUTH"])
        az = None if az is None else round(az, 1)
        h = num(dev["HEIGHT"])
        h = 0.0 if h is None else round(h, 1)
        ant_id = dev["ANTENNA_ID"].strip() or None
        key = (site_id, network, ant_id, az, h)
        if key not in sector_ids:
            sid = len(sector_ids) + 1
            sector_ids[key] = sid
            sector_site[sid] = site_id
            sector_antenna[sid] = ant_id
        carrier_count[network] = carrier_count.get(network, 0) + 1

    kept_antennas = {a for a in sector_antenna.values() if a is not None and a in antennas}
    return {
        "sites": len(set(sector_site.values())),
        "sectors": len(sector_ids),
        "carriers": sum(carrier_count.values()),
        "antennas": len(kept_antennas),
        "skippedUnknownBand": skipped_band,
        "skippedNoPower": skipped_power,
        "skippedBadSite": skipped_site,
        "carriersByNetwork": {k: v for k, v in carrier_count.items() if v > 0},
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
