"""Expected int16 elevation chunks for the :core:geo tests, computed by the Python reference decoder.

Usage: python3 -I tools/engine/make_geo_fixture.py <out.bin.gz>

Builds small synthetic GLO-90 style COGs (1200 x 1200 float32, 2 x 2 internal tiles of 640, DEFLATE + floating-point
predictor 3, PixelIsPoint, whole-degree tiepoint) from an integer formula that core/geo's SyntheticCog.kt repeats
exactly, serves them to tools/engine/glo90_crop.py through a patched http_range, and writes the chunks that
module's `crop` (the reference decode + round-half-away resample) produces:
  A: latIdx 217, lonIdx 1314 (inside S36 E148)
  B: latIdx 216, lonIdx 1315 (its first row and last column lie on whole degrees: S36/S37 x E148/E149)
  C: chunk B with the S37 E149 COG missing (404 -> 0)
as 301 x 301 int16 little-endian, south row first, concatenated A, B, C, gzip (mtime 0).
"""
import gzip
import importlib.util
import os
import struct
import sys
import zlib

W = 1200
TILE = 640
SIZE = 301


def _load(name, rel):
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), rel)
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


g = _load("glo90_crop", "glo90_crop.py")


def seed_of(lat_n, lon_w):
    return (lat_n + 100) * 131 + (lon_w + 200) * 17


def source_value(seed, r, c):
    """The synthetic elevation (float32-exact): multiples of 1/8 m with many x.5 values; NaN on a lattice."""
    if (r * 7 + c * 3) % 1009 == 0:
        return float("nan")
    n = (r * 31 + c * 17 + (r * c) % 97 + seed * 7) % 6000
    return n / 8.0 - 120.0


def predictor3_encode_row(vals):
    be = struct.pack(">%df" % len(vals), *vals)
    w = len(vals)
    planes = bytearray(4 * w)
    for k in range(4):
        planes[k * w:(k + 1) * w] = be[k::4]
    out = bytearray(planes)
    for i in range(len(out) - 1, 0, -1):
        out[i] = (out[i] - out[i - 1]) & 0xFF
    return bytes(out)


def build_cog(lat_n, lon_w):
    seed = seed_of(lat_n, lon_w)
    tiles = []
    for ty in range(2):
        for tx in range(2):
            raw = bytearray()
            for rr in range(TILE):
                r = ty * TILE + rr
                vals = [source_value(seed, r, tx * TILE + cc) if r < W and tx * TILE + cc < W else 0.0
                        for cc in range(TILE)]
                raw += predictor3_encode_row(vals)
            tiles.append(zlib.compress(bytes(raw), 6))
    entries = []   # (tag, type, count, data bytes)

    def short(t, vs): entries.append((t, 3, len(vs), struct.pack("<%dH" % len(vs), *vs)))
    def long_(t, vs): entries.append((t, 4, len(vs), struct.pack("<%dI" % len(vs), *vs)))
    def dbl(t, vs): entries.append((t, 12, len(vs), struct.pack("<%dd" % len(vs), *vs)))
    short(256, [W]); short(257, [W]); short(258, [32]); short(259, [8]); short(262, [1]); short(277, [1])
    short(284, [1]); short(317, [3]); short(322, [TILE]); short(323, [TILE])
    long_(324, [0] * 4); long_(325, [len(t) for t in tiles]); short(339, [3])
    dbl(33550, [1.0 / 1200, 1.0 / 1200, 0.0]); dbl(33922, [0.0, 0.0, 0.0, float(lon_w), float(lat_n), 0.0])
    short(34735, [1, 1, 0, 3, 1024, 0, 1, 2, 1025, 0, 1, 2, 2048, 0, 1, 4326])
    ifd_off = 8
    data_off = ifd_off + 2 + 12 * len(entries) + 4
    blobs, patches = bytearray(), {}
    ents = bytearray()
    for tag, typ, count, data in entries:
        if len(data) <= 4:
            ents += struct.pack("<HHI", tag, typ, count) + data.ljust(4, b"\0")
        else:
            patches[tag] = data_off + len(blobs)
            ents += struct.pack("<HHII", tag, typ, count, data_off + len(blobs))
            blobs += data
    tiles_off = data_off + len(blobs)
    offs, pos = [], tiles_off
    for t in tiles:
        offs.append(pos)
        pos += len(t)
    # patch TileOffsets (inline-sized no: 4 longs = 16 bytes, stored in blobs)
    o = patches[324] - data_off
    blobs[o:o + 16] = struct.pack("<4I", *offs)
    out = bytearray(b"II*\0" + struct.pack("<I", ifd_off))
    out += struct.pack("<H", len(entries)) + ents + struct.pack("<I", 0) + blobs
    for t in tiles:
        out += t
    return bytes(out)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    cogs = {}
    for lat_n in (-36, -35):         # N edge -35 = file S36; N edge -36 = file S37 (the node on a whole degree is row 0)
        for lon_w in (148, 149):
            cogs[g.cog_name(lat_n, lon_w)] = build_cog(lat_n, lon_w)

    def fake_range(url, start, length, etag=None):
        for name, blob in cogs.items():
            if name in url:
                if start + length > len(blob):
                    raise IOError("range past end")
                return blob[start:start + length], '"%s"' % name
        raise g.Missing(url)

    g.http_range = fake_range
    out = bytearray()
    for missing, lat_idx, lon_idx in (((), 217, 1314), ((), 216, 1315), ((g.cog_name(-36, 149),), 216, 1315)):
        saved = dict(cogs)
        for m in missing:
            del cogs[m]
        si = lat_idx * 300 - 108000
        wi = lon_idx * 300 - 216000
        rows, _ = g.crop(si, si + SIZE - 1, wi, wi + SIZE - 1)
        assert len(rows) == SIZE and all(len(r) == SIZE for r in rows)
        for row in rows:
            out += struct.pack("<%dh" % SIZE, *row)
        cogs.update(saved)
    with open(sys.argv[1], "wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as z:
            z.write(bytes(out))
    print("wrote %d bytes raw" % len(out), file=sys.stderr)


if __name__ == "__main__":
    main()
