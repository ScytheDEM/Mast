"""Build the HRP test fixture from the start of the real ACMA HRP zip.

Usage: python3 -I tools/acma/make_hrp_fixture.py <hrp.zip URL or local file> <rrl_fixture.zip> <out.zip> [<prefix.bin>]

With an https URL the zip is read with HTTP Range requests from offset 0, only as far as needed:
the single deflate entry LICENCE_.csv is inflated from its local header until the first 40
complete devices have been seen (no full download). A local file (the real zip, or any prefix of
it) is read the same way. Synthetic devices are then appended and 10 of the 40 real devices get
SDD_IDs of TELSTRA/OPTUS carriers in <rrl_fixture.zip>. Output: a zip with one entry LICENCE_.csv
(same header, CRLF, fixed timestamp); deterministic for the same source bytes. The SDDs worth
sampling in hrp_expected.json are printed to stdout, with the source entry's zip timestamp and the
number of bytes read. The CDN has been seen serving different versions of the zip under one ETag, so
pass <prefix.bin> to keep the bytes read (outside the repo): rebuilding from that file is
byte-identical.
"""
import csv
import importlib.util
import io
import os
import struct
import sys
import urllib.request
import zipfile
import zlib

CHUNK = 256 * 1024
REAL_DEVICES = 40
REMAPPED = 10
UNKNOWN_SDD = 999999999      # not a carrier in the tower fixture
HEADER = "SDD_ID,DEVICE_REGISTRATION_IDENTIFIER,START_ANGLE,STOP_ANGLE,POWER"


def _load(name, rel):
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), rel)
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def chunks(source):
    """Yield the source's bytes from offset 0 in CHUNK pieces."""
    if source.startswith("https://"):
        offset = 0
        while True:
            req = urllib.request.Request(source, headers={"Range": "bytes=%d-%d" % (offset, offset + CHUNK - 1)})
            with urllib.request.urlopen(req, timeout=60) as resp:
                if resp.status != 206:
                    raise IOError("expected 206 Partial Content, got %d" % resp.status)
                data = resp.read()
            if not data:
                return
            yield data
            offset += len(data)
    else:
        with open(source, "rb") as f:
            while True:
                data = f.read(CHUNK)
                if not data:
                    return
                yield data


def inflated_lines(source, consumed, info):
    """Yield the text lines (without line endings) of the zip's first entry, inflating lazily.
    Every byte read is appended to `consumed`; `info` receives the entry's DOS timestamp."""
    buf = b""

    def tracked():
        for data in chunks(source):
            consumed.append(data)
            yield data

    it = tracked()
    while len(buf) < 30:
        buf += next(it)
    sig, _, _, method, dos_time, dos_date, _, _, _, name_len, extra_len = struct.unpack("<IHHHHHIIIHH", buf[:30])
    info["timestamp"] = "%04d-%02d-%02d %02d:%02d:%02d" % (
        1980 + (dos_date >> 9), (dos_date >> 5) & 15, dos_date & 31,
        dos_time >> 11, (dos_time >> 5) & 63, (dos_time & 31) * 2)
    if sig != 0x04034B50 or method != 8:
        raise ValueError("not a deflated zip entry (signature %x, method %d)" % (sig, method))
    while len(buf) < 30 + name_len + extra_len:
        buf += next(it)
    name = buf[30:30 + name_len].decode("ascii")
    if name != "LICENCE_.csv":
        raise ValueError("unexpected entry %r" % name)
    inflater = zlib.decompressobj(-15)
    pending = inflater.decompress(buf[30 + name_len + extra_len:])
    rest = b""
    while True:
        rest += pending
        *lines, rest = rest.split(b"\n")
        for line in lines:
            yield line.rstrip(b"\r").decode("iso-8859-1")
        if inflater.eof:
            if rest:
                yield rest.rstrip(b"\r").decode("iso-8859-1")
            return
        try:
            pending = inflater.decompress(next(it))
        except StopIteration:
            return


def first_complete_devices(source, count, consumed, info):
    """The first `count` complete devices: lists of 5-field rows (text kept as in the source)."""
    lines = inflated_lines(source, consumed, info)
    if next(lines) != HEADER:
        raise ValueError("unexpected header")
    devices = []
    run = []

    def close(rows):
        if len(rows) == 360 and all(r[2] == str(i) for i, r in enumerate(rows)):
            devices.append(rows)

    for line in lines:
        row = next(csv.reader([line]))
        if len(row) != 5:
            raise ValueError("unexpected row %r" % line)
        if run and row[0] != run[0][0]:
            close(run)
            run = []
            if len(devices) == count:
                return devices
        run.append(row)
    raise ValueError("source ended before %d complete devices" % count)


def with_sdd(rows, sdd):
    return [[str(sdd)] + r[1:] for r in rows]


def rotated(rows, sdd, shift):
    """The device's powers rotated by `shift` degrees (same shape, peak bearing + shift)."""
    return [[str(sdd), rows[0][1], str(a), str(a + 1), rows[(a - shift) % 360][4]] for a in range(360)]


def rotation_keeps_shape(rows, normalise, shifts):
    """True when every rotation by `shifts` normalises to the same pattern (the first maximum
    must stay the same physical maximum, so a plateau must not wrap past 0 degrees)."""
    qs = {bytes(normalise([float(r[4]) for r in rotated(rows, 0, s)])[2]) for s in (0,) + shifts}
    return len(qs) == 1


def main():
    if len(sys.argv) not in (4, 5):
        sys.exit("usage: make_hrp_fixture.py <hrp.zip URL or file> <rrl_fixture.zip> <out.zip> [<prefix.bin>]")
    source, fixture, out_path = sys.argv[1:4]
    acma = _load("acma_reference", "reference.py")
    carriers = acma.load(fixture)["carriers"]

    def real_sdds(network):
        return sorted(c["sdd_id"] for c in carriers if c["network"] == network and not 999999000 <= c["sdd_id"] <= 999999999)

    telstra, optus = real_sdds("TELSTRA"), real_sdds("OPTUS")
    known = [s for pair in zip(telstra, optus) for s in pair][:REMAPPED + 4]
    fixture_sdds = {c["sdd_id"] for c in carriers}

    consumed, info = [], {}
    real = first_complete_devices(source, REAL_DEVICES, consumed, info)
    print("source entry timestamp %s, %d bytes read" % (info["timestamp"], sum(map(len, consumed))))
    if len(sys.argv) == 5:
        with open(sys.argv[4], "wb") as f:
            f.write(b"".join(consumed))
    if any(int(d[0][0]) in fixture_sdds for d in real) or UNKNOWN_SDD in fixture_sdds:
        raise ValueError("a real SDD already exists in the tower fixture; pick other devices")

    devices = []
    remapped = []
    for i, d in enumerate(real):
        if i % 4 == 0 and len(remapped) < REMAPPED:   # devices 0, 4, ..., 36
            sdd = known[len(remapped)]
            remapped.append(sdd)
            devices.append(with_sdd(d, sdd))
        else:
            devices.append(d)
    incomplete_sdd, out_of_order_sdd, dedup_a, dedup_b = known[REMAPPED:REMAPPED + 4]
    devices.append(with_sdd(real[1], incomplete_sdd)[:359])            # 359 rows
    swapped = with_sdd(real[2], out_of_order_sdd)
    swapped[10], swapped[11] = swapped[11], swapped[10]
    devices.append(swapped)                                            # angles ..., 9, 11, 10, 12, ...
    devices.append(with_sdd(real[3], UNKNOWN_SDD))                     # SDD not in the tower fixture
    normalise = _load("hrp_reference", "hrp_reference.py").normalise
    shape = next(d for d in real if rotation_keeps_shape(d, normalise, (90, 200)))
    devices.append(rotated(shape, dedup_a, 90))                        # same shape,
    devices.append(rotated(shape, dedup_b, 200))                       # different peak bearing

    sdds = [d[0][0] for d in devices]
    if len(set(sdds)) != len(sdds):
        raise ValueError("duplicate SDD_ID among fixture devices")

    buf = io.StringIO()
    buf.write(HEADER + "\r\n")
    for d in devices:
        for r in d:
            buf.write(",".join(r) + "\r\n")
    info = zipfile.ZipInfo("LICENCE_.csv", date_time=(2026, 10, 20, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    with zipfile.ZipFile(out_path, "w") as out:
        out.writestr(info, buf.getvalue().encode("iso-8859-1"))

    print("remapped (real shapes): %s" % " ".join(map(str, remapped)))
    print("incomplete %d, out of order %d, unknown %d, dedup pair %d %d"
          % (incomplete_sdd, out_of_order_sdd, UNKNOWN_SDD, dedup_a, dedup_b))


if __name__ == "__main__":
    main()
