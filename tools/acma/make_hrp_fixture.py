"""Build the HRP test fixture from the start of the real ACMA HRP zip.

Usage: python3 -I tools/acma/make_hrp_fixture.py <hrp.zip URL or local file> <rrl_fixture.zip> <out.zip> [<prefix.bin>]

The single deflate entry LICENCE_.csv is inflated from its local header and scanned device by device
(a device = one run of rows with the same SDD_ID) until it has
  - the first 40 devices that HRP rule 2 accepts, and
  - the first real example of each interval shape: merged rows, half-degree grid, a row wrapping past
    360, shuffled order, [0,1) missing; and a real POWER sentinel (> 200) rejection,
reading no further than that (MAX_BYTES at most). A real 60-degree-only sector (with valid POWER) is
used if one was seen by then; otherwise it is synthetic (real ones carry the 2400 sentinel).

With an https URL the bytes come from HTTP Range requests pinned to the first response's ETag and
Last-Modified (If-Match / If-Unmodified-Since). A 412, a change of ETag, Last-Modified or
Content-Range total, or a range that does not start where asked aborts the scan, which restarts from
offset 0 (up to 3 attempts) so every byte comes from one version. A local file (the real zip or a saved
prefix) is read the same way.

Then 10 devices get SDD_IDs of TELSTRA/OPTUS carriers in <rrl_fixture.zip> (5 of the first 40 plus the
5 interval-shape examples). The rejection examples and synthetic devices (4 empty bins, a conflicting
overlap, a rotated de-duplication pair, possibly the 60-degree sector) get further carrier SDDs, and
the rest keep their real (unknown) SDD_IDs. Output: a zip with one entry LICENCE_.csv (same header,
CRLF, fixed timestamp), deterministic for the same source bytes. Pass <prefix.bin> to keep the bytes
read (outside the repo): rebuilding from that file is byte-identical. The CDN has served different zips
under one ETag, so the entry timestamp is printed too.
"""
import csv
import importlib.util
import io
import math
import os
import struct
import sys
import urllib.error
import urllib.request
import zipfile
import zlib

CHUNK = 1024 * 1024
MAX_BYTES = 256 * 1024 * 1024
BASE_DEVICES = 40
BASE_REMAPPED = (0, 8, 16, 24, 32)          # indexes into the first 40 accepted devices
SHAPES = ("wrap", "half", "merged", "shuffled", "missing0")   # accepted interval shapes (remapped)
REJECTS = ("sentinel", "sector60")                            # rejected shapes (real when found)
REQUIRED = SHAPES + ("sentinel",)          # the scan stops once these (and the 40) are found
UNKNOWN_SDD = 999999999                     # not a carrier in the tower fixture
HEADER = "SDD_ID,DEVICE_REGISTRATION_IDENTIFIER,START_ANGLE,STOP_ANGLE,POWER"


def _load(name, rel):
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), rel)
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


hrp = _load("hrp_reference", "hrp_reference.py")


ATTEMPTS = 3


class SourceChanged(IOError):
    """The remote zip changed (or a CDN edge served another version) between Range requests."""


def chunks(source, info):
    """Yield the source's bytes from offset 0 in CHUNK pieces (at most MAX_BYTES)."""
    if not source.startswith("https://"):
        with open(source, "rb") as f:
            while f.tell() < MAX_BYTES:
                data = f.read(CHUNK)
                if not data:
                    return
                yield data
        return
    offset = 0
    pin = None
    while offset < MAX_BYTES:
        headers = {"Range": "bytes=%d-%d" % (offset, offset + CHUNK - 1)}
        if pin:
            headers["If-Match"] = pin["etag"]
            headers["If-Unmodified-Since"] = pin["last_modified"]
        try:
            with urllib.request.urlopen(urllib.request.Request(source, headers=headers), timeout=60) as resp:
                if resp.status != 206:
                    raise IOError("expected 206 Partial Content, got %d" % resp.status)
                got = {"etag": resp.headers.get("ETag"), "last_modified": resp.headers.get("Last-Modified")}
                content_range = resp.headers.get("Content-Range") or ""
                data = resp.read()
        except urllib.error.HTTPError as e:
            if e.code == 412:
                raise SourceChanged("412 Precondition Failed at offset %d (pinned %r)" % (offset, pin))
            raise
        if not content_range.startswith("bytes %d-" % offset):
            raise IOError("Content-Range %r does not start at %d" % (content_range, offset))
        got["total"] = content_range.rsplit("/", 1)[-1]
        if pin is None:
            if not got["etag"] or not got["last_modified"]:
                raise IOError("response lacks ETag or Last-Modified")
            pin = got
            info.update(pin)
        elif got != pin:
            raise SourceChanged("source changed between requests: %r != %r" % (got, pin))
        if not data:
            return
        yield data
        offset += len(data)


def inflated_lines(source, consumed, info):
    """Yield the text lines (without line endings) of the zip's first entry, inflating lazily.
    Every byte read is appended to `consumed`; `info` receives the entry's DOS timestamp."""
    def tracked():
        for data in chunks(source, info):
            consumed.append(data)
            yield data

    it = tracked()
    buf = b""
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
    if buf[30:30 + name_len].decode("ascii") != "LICENCE_.csv":
        raise ValueError("unexpected entry")
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


def devices_of(lines):
    """Yield each run of rows with the same SDD_ID text as a list of 5-field rows (text as in the file).
    The final, possibly truncated, run is never yielded."""
    if next(lines) != HEADER:
        raise ValueError("unexpected header")
    run = []
    for line in lines:
        row = next(csv.reader([line]))
        if len(row) != 5:
            raise ValueError("unexpected row %r" % line)
        if run and row[0] != run[0][0]:
            yield run
            run = []
        run.append(row)


def covered_bins(start, stop):
    """Degrees held by the interval [start, stop) (rule 2; stop < start wraps)."""
    if stop < start:
        stop += 360.0
    first = math.ceil(start)
    return {d % 360 for d in range(first, min(math.ceil(stop), first + 360))}


def shapes_of(rows):
    """The interval shapes / real rejection a device shows (empty set: plain integer grid, sorted)."""
    try:
        starts = [float(r[2]) for r in rows]
        stops = [float(r[3]) for r in rows]
        powers = [float(r[4]) for r in rows]
    except ValueError:
        return {"malformed"}
    covered = set()
    for a, b in zip(starts, stops):
        covered |= covered_bins(a, b)
    if hrp.rasterise([tuple(r[2:5]) for r in rows]) is None:
        if any(p > hrp.MAX_POWER for p in powers):
            return {"sentinel"}
        if len(covered) == 60:
            return {"sector60"}
        return {"rejected"}
    found = set()
    half = any(v != int(v) for v in starts + stops)
    if half:
        found.add("half")
    if any(b < a for a, b in zip(starts, stops)):
        found.add("wrap")
    if len(rows) > 1 and any((b - a) % 360 > 1 for a, b in zip(starts, stops)):
        found.add("merged")     # several rows, some spanning more than 1 degree
    rot = starts.index(min(starts))
    if starts[rot:] + starts[:rot] != sorted(starts):
        found.add("shuffled")
    if not half and 0 not in covered:
        found.add("missing0")
    return found


def scan(source):
    consumed, info = [], {}
    base, examples = [], {}
    wanted = set(SHAPES) | set(REJECTS)
    required = set(REQUIRED)
    for rows in devices_of(inflated_lines(source, consumed, info)):
        shapes = shapes_of(rows)
        accepted = not (shapes & {"sentinel", "sector60", "rejected", "malformed"})
        if accepted and len(base) < BASE_DEVICES:
            base.append(rows)
        for shape in (s for s in SHAPES + REJECTS if s in shapes and s in wanted):
            if not any(rows is r for r in examples.values()):
                examples[shape] = rows
                wanted.discard(shape)
                required.discard(shape)
                break
        if len(base) == BASE_DEVICES and not required:
            return base, examples, consumed, info
    raise ValueError("not all shapes found within %d bytes; missing %s" % (MAX_BYTES, sorted(required)))


def with_sdd(rows, sdd):
    return [[str(sdd)] + r[1:] for r in rows]


def is_plain(rows):
    return len(rows) == 360 and all(r[2] == str(i) and r[3] == str(i + 1) for i, r in enumerate(rows))


def rotated(rows, sdd, shift):
    """A plain device's powers rotated by `shift` degrees (same shape, peak bearing + shift)."""
    return [[str(sdd), rows[0][1], str(a), str(a + 1), rows[(a - shift) % 360][4]] for a in range(360)]


def rotation_keeps_shape(rows, shifts):
    qs = set()
    for s in (0,) + shifts:
        powers = hrp.rasterise([tuple(r[2:5]) for r in rotated(rows, 0, s)])
        qs.add(bytes(hrp.normalise(powers)[2]))
    return len(qs) == 1


def main():
    if len(sys.argv) not in (4, 5):
        sys.exit("usage: make_hrp_fixture.py <hrp.zip URL or file> <rrl_fixture.zip> <out.zip> [<prefix.bin>]")
    source, fixture, out_path = sys.argv[1:4]
    acma = _load("acma_reference", "reference.py")
    carriers = acma.load(fixture)["carriers"]

    def real_sdds(network):
        return sorted(c["sdd_id"] for c in carriers if c["network"] == network and not 999999000 <= c["sdd_id"] <= 999999999)

    known = [s for pair in zip(real_sdds("TELSTRA"), real_sdds("OPTUS")) for s in pair]
    fixture_sdds = {c["sdd_id"] for c in carriers}

    for attempt in range(1, ATTEMPTS + 1):
        try:
            base, examples, consumed, info = scan(source)
            break
        except SourceChanged as e:   # start again from offset 0 so all bytes come from one version
            print("attempt %d: %s" % (attempt, e), file=sys.stderr)
            if attempt == ATTEMPTS:
                raise
    print("source ETag %s, Last-Modified %s, entry timestamp %s, %d bytes read"
          % (info.get("etag", "(file)"), info.get("last_modified", "(file)"), info["timestamp"],
             sum(map(len, consumed))))
    if len(sys.argv) == 5:
        with open(sys.argv[4], "wb") as f:
            f.write(b"".join(consumed))
    real = base + [d for d in examples.values() if not any(d is b for b in base)]
    if any(int(d[0][0]) in fixture_sdds for d in real) or UNKNOWN_SDD in fixture_sdds:
        raise ValueError("a real SDD already exists in the tower fixture; pick other devices")

    take = iter(known)
    sdd_of = {}                                  # id(device rows) -> remapped SDD
    for i in BASE_REMAPPED:
        sdd_of[id(base[i])] = next(take)
    for shape in SHAPES:
        sdd_of[id(examples[shape])] = next(take)
    for shape in REJECTS:
        if shape in examples:
            sdd_of[id(examples[shape])] = next(take)
    missing4_sdd, conflict_sdd, dedup_a, dedup_b = next(take), next(take), next(take), next(take)

    devices = [with_sdd(d, sdd_of[id(d)]) if id(d) in sdd_of else d for d in real]
    plain = [d for d in base if is_plain(d)]
    devices.append([r for r in with_sdd(plain[1], missing4_sdd) if r[2] not in ("100", "101", "102", "103")])
    conflict = with_sdd(plain[2], conflict_sdd)
    conflict.append([str(conflict_sdd), conflict[0][1], "10", "12", "%.1f" % (float(conflict[10][4]) + 1.0)])
    devices.append(conflict)                                   # [10,12) disagrees with [10,11)
    devices.append(with_sdd(plain[3], UNKNOWN_SDD))            # SDD not in the tower fixture
    if "sector60" not in examples:                             # real 60-degree devices carry sentinels
        sector_sdd = next(take)
        devices.append([r for r in with_sdd(plain[4], sector_sdd) if int(r[2]) < 60])
    shape = next(d for d in plain if rotation_keeps_shape(d, (90, 200)))
    devices.append(rotated(shape, dedup_a, 90))                # same shape, different peak bearing,
    devices.append(list(reversed(rotated(shape, dedup_b, 200))))   # listed in reverse order

    sdds = [d[0][0] for d in devices]
    if len(set(sdds)) != len(sdds):
        raise ValueError("duplicate SDD_ID among fixture devices")

    buf = io.StringIO()
    buf.write(HEADER + "\r\n")
    for d in devices:
        for r in d:
            buf.write(",".join(r) + "\r\n")
    zinfo = zipfile.ZipInfo("LICENCE_.csv", date_time=(2026, 10, 20, 0, 0, 0))
    zinfo.compress_type = zipfile.ZIP_DEFLATED
    zinfo.external_attr = 0o644 << 16
    with zipfile.ZipFile(out_path, "w") as out:
        out.writestr(zinfo, buf.getvalue().encode("iso-8859-1"))

    print("base remapped: %s" % " ".join(str(sdd_of[id(base[i])]) for i in BASE_REMAPPED))
    for shape in SHAPES + REJECTS:
        d = examples.get(shape)
        if d is None:
            print("%-9s synthetic (rows 0..59 of a plain device) -> %d" % (shape, sector_sdd))
        else:
            print("%-9s real SDD %s (%d rows) -> %d" % (shape, d[0][0], len(d), sdd_of[id(d)]))
    print("synthetic: missing 4 bins %d, conflicting overlap %d, unknown %d, dedup pair %d %d"
          % (missing4_sdd, conflict_sdd, UNKNOWN_SDD, dedup_a, dedup_b))


if __name__ == "__main__":
    main()
