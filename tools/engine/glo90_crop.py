"""Fetch a Copernicus GLO-90 crop with HTTP range reads and put it on the 3-arcsecond chunk grid.

Usage:
  python3 -I tools/engine/glo90_crop.py tags <S36_00_E148_00>
  python3 -I tools/engine/glo90_crop.py crop <south> <north> <west> <east> <out.json>

Stdlib only (urllib, zlib, struct). `tags` prints the first IFD of one COG. `crop` reads each COG's header
and then only the internal tiles the crop needs, every request of one COG pinned to the ETag of its first
response with If-Match (a 412 or a changed ETag restarts that COG from its header). Tiles are DEFLATE with
the floating-point predictor (3). Samples become int16 metres rounded half away from zero; NaN / no-data
become 0; a missing COG (404, ocean) is 0 everywhere.

GLO-90 facts checked by `tags` (2026-10-23, S35/S36 x E148/E149): little-endian classic TIFF, 1200 x 1200
float32, one 2048 x 2048 internal tile, compression 8, predictor 3, no GDAL_NODATA tag,
GTRasterTypeGeoKey = 2 (PixelIsPoint), ModelPixelScale 1/1200 degree, tiepoint (0, 0) -> (lon W, lat N)
on whole degrees. So COG node (r, c) lies at (N - r/1200, W + c/1200): exactly the chunk-grid nodes
(multiples of 1/1200 degree), and putting the data on the chunk grid needs no interpolation. The node on
a whole-degree latitude belongs to the COG to its south (its row 0); `crop` asserts this layout.
"""
import base64
import itertools
import json
import math
import struct
import sys
import urllib.error
import urllib.request
import zlib

URL = ("https://copernicus-dem-90m.s3.amazonaws.com/Copernicus_DSM_COG_30_{name}_DEM/"
       "Copernicus_DSM_COG_30_{name}_DEM.tif")
STEPS_PER_DEG = 1200          # chunk grid: 3 arcseconds
HEADER_BYTES = 65536

TAG_NAMES = {256: "ImageWidth", 257: "ImageLength", 258: "BitsPerSample", 259: "Compression",
             262: "Photometric", 277: "SamplesPerPixel", 284: "PlanarConfig", 317: "Predictor",
             322: "TileWidth", 323: "TileLength", 324: "TileOffsets", 325: "TileByteCounts",
             339: "SampleFormat", 33550: "ModelPixelScale", 33922: "ModelTiepoint",
             34735: "GeoKeyDirectory", 34736: "GeoDoubleParams", 34737: "GeoAsciiParams",
             42112: "GDAL_METADATA", 42113: "GDAL_NODATA"}
TYPE_FMT = {1: ("B", 1), 2: ("s", 1), 3: ("H", 2), 4: ("I", 4), 5: ("II", 8), 7: ("B", 1),
            11: ("f", 4), 12: ("d", 8), 16: ("Q", 8)}


class EtagChanged(Exception):
    pass


class Missing(Exception):
    pass


def cog_name(lat_n, lon_w):
    """COG name of the 1-degree cell with north edge `lat_n` and west edge `lon_w` (whole degrees). The
    file named S36 covers latitudes -36..-35: its name is its south edge."""
    lat_s = lat_n - 1
    ns = "S%02d" % (-lat_s) if lat_s < 0 else "N%02d" % lat_s
    ew = "W%03d" % (-lon_w) if lon_w < 0 else "E%03d" % lon_w
    return "%s_00_%s_00" % (ns, ew)


def http_range(url, start, length, etag=None):
    """Bytes [start, start + length) of `url`, as (data, etag). EtagChanged on 412 or a different ETag."""
    req = urllib.request.Request(url, headers={"Range": "bytes=%d-%d" % (start, start + length - 1)})
    if etag is not None:
        req.add_header("If-Match", etag)
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            got = resp.headers.get("ETag")
            if resp.status != 206:
                raise IOError("%s: expected 206, got %d" % (url, resp.status))
            if etag is not None and got != etag:
                raise EtagChanged(url)
            data = resp.read()
    except urllib.error.HTTPError as e:
        if e.code == 412:
            raise EtagChanged(url)
        if e.code == 404:
            raise Missing(url)
        raise
    if len(data) != length:
        raise IOError("%s: short read %d of %d" % (url, len(data), length))
    return data, got


class Cog:
    """The first (full-resolution) IFD of a little-endian TIFF/BigTIFF COG, read with range requests."""

    def __init__(self, url):
        self.url = url
        self._buf, self.etag = http_range(url, 0, HEADER_BYTES)
        self.tags = self._parse_ifd()

    def _bytes(self, off, n):
        if off + n > len(self._buf):
            extra, _ = http_range(self.url, len(self._buf), off + n - len(self._buf), self.etag)
            self._buf += extra
        return self._buf[off:off + n]

    def _parse_ifd(self):
        b = self._bytes(0, 16)
        if b[:2] != b"II":
            raise ValueError("only little-endian TIFF is supported")
        version = struct.unpack_from("<H", b, 2)[0]
        if version not in (42, 43):
            raise ValueError("not a TIFF")
        big = version == 43
        ifd = struct.unpack_from("<Q", b, 8)[0] if big else struct.unpack_from("<I", b, 4)[0]
        cnt_fmt, ent_size, cnt_field, off_fmt, inline = (("<Q", 20, "<Q", "<Q", 8) if big
                                                         else ("<H", 12, "<I", "<I", 4))
        n = struct.unpack(cnt_fmt, self._bytes(ifd, struct.calcsize(cnt_fmt)))[0]
        base = ifd + struct.calcsize(cnt_fmt)
        tags = {}
        for k in range(n):
            e = self._bytes(base + k * ent_size, ent_size)
            tag, typ = struct.unpack_from("<HH", e, 0)
            count = struct.unpack_from(cnt_field, e, 4)[0]
            fmt, size = TYPE_FMT[typ]
            total = size * count
            val_off = 12 if big else 8
            if total <= inline:
                raw = e[val_off:val_off + total]
            else:
                raw = self._bytes(struct.unpack_from(off_fmt, e, val_off)[0], total)
            if typ == 2:
                tags[tag] = raw.rstrip(b"\0").decode("ascii", "replace")
            else:
                tags[tag] = list(struct.unpack("<" + fmt * count, raw))
        return tags

    def tag(self, t, default=None):
        v = self.tags.get(t, default)
        return v[0] if isinstance(v, list) and len(v) == 1 else v

    def geokey(self, key):
        d = self.tags.get(34735)
        for k in range(d[3] if d else 0):
            kid, loc, _, val = d[4 + 4 * k:8 + 4 * k]
            if kid == key and loc == 0:
                return val
        return None

    def read_tile(self, index):
        data, _ = http_range(self.url, self.tags[324][index], self.tags[325][index], self.etag)
        return data


def undo_float_predictor(row, width):
    """TIFF predictor 3 for one row of float32: undo byte-wise horizontal differencing, then re-interleave
    the four byte planes (most significant first) into big-endian floats."""
    planes = bytes(itertools.accumulate(row, lambda a, b: (a + b) & 0xFF))
    be = bytearray(4 * width)
    for k in range(4):
        be[k::4] = planes[k * width:(k + 1) * width]
    return struct.unpack(">%df" % width, be)


class CogGrid:
    """One GLO-90 COG: node values by (row, col), internal tiles fetched and rows decoded on demand."""

    def __init__(self, lat_n, lon_w):
        self.name = cog_name(lat_n, lon_w)
        self.lat_n, self.lon_w = lat_n, lon_w
        self.missing = False
        self.tiles_read = 0
        self._raw = {}
        self._rows = {}
        try:
            self.cog = Cog(URL.format(name=self.name))
        except Missing:
            self.missing = True
            return
        c = self.cog
        assert c.tag(259) == 8 and c.tag(258) == 32 and c.tag(339) == 3 and c.tag(277, [1]) == 1, self.name
        assert c.tag(284, [1]) == 1 and c.tag(317, [1]) in (1, 3), self.name
        assert c.geokey(1025) == 2, "%s: expected PixelIsPoint" % self.name
        self.w, self.h = c.tag(256), c.tag(257)
        self.tw, self.th = c.tag(322), c.tag(323)
        self.pred = c.tag(317, [1])
        sx, sy = c.tags[33550][:2]
        tp = c.tags[33922]
        assert (self.w, self.h) == (STEPS_PER_DEG, STEPS_PER_DEG), "%s: size %r" % (self.name, (self.w, self.h))
        assert abs(sx * STEPS_PER_DEG - 1) < 1e-12 and abs(sy * STEPS_PER_DEG - 1) < 1e-12, self.name
        assert tp[:2] == [0.0, 0.0] and (tp[3], tp[4]) == (float(lon_w), float(lat_n)), "%s: tiepoint %r" % (self.name, tp)
        nod = c.tags.get(42113)
        self.nodata = float(nod) if nod not in (None, "") else None

    def _row(self, r, tx):
        ty = r // self.th
        key = (ty, tx)
        if key not in self._raw:
            per_row = (self.w + self.tw - 1) // self.tw
            raw = zlib.decompress(self.cog.read_tile(ty * per_row + tx))
            if len(raw) != self.tw * self.th * 4:
                raise ValueError("%s: tile %r decodes to %d bytes" % (self.name, key, len(raw)))
            self._raw[key] = raw
            self.tiles_read += 1
        rk = (r, tx)
        if rk not in self._rows:
            raw = self._raw[key]
            rb = self.tw * 4
            seg = raw[(r - ty * self.th) * rb:(r - ty * self.th + 1) * rb]
            self._rows[rk] = (undo_float_predictor(seg, self.tw) if self.pred == 3
                              else struct.unpack("<%df" % self.tw, seg))
        return self._rows[rk]

    def value(self, r, c):
        if self.missing:
            return 0.0
        v = self._row(r, c // self.tw)[c % self.tw]
        if not math.isfinite(v) or (self.nodata is not None and v == self.nodata):
            return 0.0
        return v


def round_half_away(x):
    return int(math.floor(abs(x) + 0.5)) * (1 if x >= 0 else -1)


def crop(south_i, north_i, west_i, east_i):
    """int16 rows (south to north) of chunk-grid nodes south_i..north_i x west_i..east_i (units of
    1/1200 degree), and the provenance of every COG used."""
    for attempt in range(3):
        grids = {}
        try:
            rows = []
            for ri in range(south_i, north_i + 1):
                lat_n = -((-ri) // STEPS_PER_DEG)          # ceil: a whole-degree node is row 0 of the COG south of it
                r = lat_n * STEPS_PER_DEG - ri
                row = []
                for ci in range(west_i, east_i + 1):
                    lon_w = ci // STEPS_PER_DEG
                    key = (lat_n, lon_w)
                    if key not in grids:
                        grids[key] = CogGrid(lat_n, lon_w)
                    row.append(round_half_away(grids[key].value(r, ci - lon_w * STEPS_PER_DEG)))
                rows.append(row)
            break
        except EtagChanged:
            if attempt == 2:
                raise
    sources = []
    for key in sorted(grids):
        g = grids[key]
        src = {"name": g.name, "missing": g.missing}
        if not g.missing:
            src.update({"etag": g.cog.etag, "width": g.w, "height": g.h, "tile": [g.tw, g.th],
                        "compression": 8, "predictor": g.pred, "rasterType": "PixelIsPoint",
                        "internalTilesRead": g.tiles_read})
        sources.append(src)
    return rows, sources


def write_crop(path, south, north, west, east):
    """Fetch the chunk-grid nodes covering [south, north] x [west, east] (degrees) and write the crop JSON."""
    si, ni = int(math.floor(south * STEPS_PER_DEG)), int(math.ceil(north * STEPS_PER_DEG))
    wi, ei = int(math.floor(west * STEPS_PER_DEG)), int(math.ceil(east * STEPS_PER_DEG))
    rows, sources = crop(si, ni, wi, ei)
    flat = [v for row in rows for v in row]
    doc = {"format": "signal-atlas-dem-crop-1",
           "stepsPerDegree": STEPS_PER_DEG, "southIndex": si, "westIndex": wi,
           "rows": len(rows), "cols": len(rows[0]),
           "layout": "node (row, col) at lat (southIndex + row)/1200, lon (westIndex + col)/1200; row 0 is "
                     "the south edge; int16 little-endian metres, row-major, base64",
           "source": "Copernicus GLO-90 DSM COG, https://copernicus-dem-90m.s3.amazonaws.com",
           "requested": [south, north, west, east],
           "cogs": sources,
           "h": base64.b64encode(struct.pack("<%dh" % len(flat), *flat)).decode("ascii")}
    with open(path, "w", encoding="ascii", newline="\n") as f:
        f.write(json.dumps(doc, indent=1))
        f.write("\n")
    print("%d x %d nodes, %d..%d m, %s" % (len(rows), len(rows[0]), min(flat), max(flat),
                                           ", ".join(s["name"] for s in sources)), file=sys.stderr)


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "tags":
        name = sys.argv[2]
        c = Cog(URL.format(name=name))
        for t, v in sorted(c.tags.items()):
            s = repr(v)
            print(t, TAG_NAMES.get(t, "?"), s if len(s) < 300 else s[:300] + "...")
        print("ETag", c.etag, "GTRasterTypeGeoKey", c.geokey(1025))
        return
    if len(sys.argv) == 7 and sys.argv[1] == "crop":
        write_crop(sys.argv[6], *(float(v) for v in sys.argv[2:6]))
        return
    sys.exit(__doc__)


if __name__ == "__main__":
    main()
