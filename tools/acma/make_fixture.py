"""Trim a real ACMA RRL zip to a few sites and inject synthetic bad rows.

Usage: python3 -I tools/acma/make_fixture.py <rrl.zip> <out.zip>

Writes the 6 CSVs the importer reads (ISO-8859-1, CRLF, csv.QUOTE_MINIMAL).
Output is deterministic (fixed zip timestamps).
"""
import csv
import importlib.util
import io
import os
import sys
import zipfile

SITE_IDS = {"101160", "41428", "9002575", "9019760"}
TELSTRA_LIC = "9263433"  # TELSTRA LIMITED, Spectrum
OPTUS_LIC = "9460720"    # Optus Mobile Pty Limited, Spectrum
NAMES = ["client.csv", "licence.csv", "site.csv", "antenna.csv",
         "antenna_pattern.csv", "device_details.csv"]


def load_reference():
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "reference.py")
    spec = importlib.util.spec_from_file_location("reference", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def read(zf, name):
    """Return (header, row iterator); the zip entry stays open until exhausted."""
    r = csv.reader(io.TextIOWrapper(zf.open(name), encoding="iso-8859-1", newline=""))
    return next(r), r


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: make_fixture.py <rrl.zip> <out.zip>")
    ref = load_reference()
    zf = zipfile.ZipFile(sys.argv[1])

    dh, rows = read(zf, "device_details.csv")
    di = {c: i for i, c in enumerate(dh)}
    devices = [r for r in rows if r[di["SITE_ID"]].strip() in SITE_IDS]
    lic_nos = {r[di["LICENCE_NO"]].strip() for r in devices}
    ant_ids = {r[di["ANTENNA_ID"]].strip() for r in devices} - {""}

    lh, rows = read(zf, "licence.csv")
    li = {c: i for i, c in enumerate(lh)}
    licences = [r for r in rows if r[li["LICENCE_NO"]].strip() in lic_nos]
    client_nos = {r[li["CLIENT_NO"]].strip() for r in licences}

    ch, rows = read(zf, "client.csv")
    ci = {c: i for i, c in enumerate(ch)}
    clients = [r for r in rows if r[ci["CLIENT_NO"]].strip() in client_nos]

    sh, rows = read(zf, "site.csv")
    si = {c: i for i, c in enumerate(sh)}
    sites = [r for r in rows if r[si["SITE_ID"]].strip() in SITE_IDS]

    ah, rows = read(zf, "antenna.csv")
    ai = {c: i for i, c in enumerate(ah)}
    antennas = [r for r in rows if r[ai["ANTENNA_ID"]].strip() in ant_ids]

    ph, rows = read(zf, "antenna_pattern.csv")
    pi = {c: i for i, c in enumerate(ph)}
    patterns = [r for r in rows if r[pi["ANTENNA_ID"]].strip() in ant_ids]

    # first kept device: DEVICE_TYPE T on a licence that rule 2 keeps
    net_by_client = {r[ci["CLIENT_NO"]].strip(): ref.NETWORK_BY_LICENSEE.get(r[ci["LICENCEE"]].strip().lower())
                     for r in clients}
    kept_lic = {r[li["LICENCE_NO"]].strip() for r in licences
                if r[li["LICENCE_TYPE_NAME"]].strip() in ref.LICENCE_TYPES
                and net_by_client.get(r[li["CLIENT_NO"]].strip())}
    base = next(r for r in devices
                if r[di["DEVICE_TYPE"]].strip() == "T" and r[di["LICENCE_NO"]].strip() in kept_lic)

    def synth(sdd, **changes):
        r = list(base)
        r[di["SDD_ID"]] = str(sdd)
        for k, v in changes.items():
            r[di[k]] = v
        return r

    devices += [
        synth(999999201, SITE_ID="999999001"),
        synth(999999202, SITE_ID="999999002"),
        synth(999999101, EIRP="", TRANSMITTER_POWER="40", TRANSMITTER_POWER_UNIT="W"),
        synth(999999102, EIRP="", TRANSMITTER_POWER=""),
        synth(999999103, FREQUENCY="450000000"),
        # 3xx: amended band plan, units, sector-key and skip-precedence branches
        synth(999999301, LICENCE_NO=TELSTRA_LIC, FREQUENCY="862000000"),   # n26 (Telstra NR)
        synth(999999302, LICENCE_NO=OPTUS_LIC, FREQUENCY="862000000"),     # B26 (LTE)
        synth(999999303, FREQUENCY="3920000000"),                          # n77
        synth(999999304, EIRP="500", EIRP_UNIT="mW"),
        synth(999999305, EIRP="40", EIRP_UNIT="dBm"),
        synth(999999306, EIRP="10", EIRP_UNIT="dBW"),
        synth(999999307, AZIMUTH=""),                                      # omni sector
        synth(999999308, ANTENNA_ID=""),                                   # null antenna
        synth(999999309, AZIMUTH="22.25"),                                 # key rounds to 22.3
        synth(999999310, FREQUENCY="450000000", SITE_ID="999999001"),      # band+site -> band
        synth(999999311, FREQUENCY="450000000", EIRP="", TRANSMITTER_POWER=""),  # band+power -> band
    ]
    for sid, name, lat, lon in (("999999001", "SYNTHETIC BAD SITE 1", "", ""),
                                ("999999002", "SYNTHETIC BAD SITE 2", "51.5", "-0.1")):
        r = [""] * len(sh)
        r[si["SITE_ID"]], r[si["NAME"]] = sid, name
        r[si["LATITUDE"]], r[si["LONGITUDE"]] = lat, lon
        sites.append(r)

    tables = {
        "client.csv": (ch, clients), "licence.csv": (lh, licences), "site.csv": (sh, sites),
        "antenna.csv": (ah, antennas), "antenna_pattern.csv": (ph, patterns),
        "device_details.csv": (dh, devices),
    }
    with zipfile.ZipFile(sys.argv[2], "w", zipfile.ZIP_DEFLATED) as out:
        for name in NAMES:
            header, body = tables[name]
            buf = io.StringIO(newline="")
            w = csv.writer(buf, lineterminator="\r\n", quoting=csv.QUOTE_MINIMAL)
            w.writerow(header)
            w.writerows(body)
            info = zipfile.ZipInfo(name, date_time=(2026, 10, 22, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(info, buf.getvalue().encode("iso-8859-1"))
    print({n: len(t[1]) for n, t in tables.items()})


if __name__ == "__main__":
    main()
