"""Reference implementation of the Signal Atlas "Terrain model" rules 1-6.

Pure functions (stdlib `math` only). The C++ terrain stage (engine/src/main/cpp/terrain) must reproduce
these numbers; tools/engine/make_terrain_vectors.py writes them as test vectors. The equation forms and
units are listed in tools/engine/README.md ("Terrain model reference").

Units follow ITU-R P.526-16 section 4.5: path distances d, d_i in km, heights in metres above sea level,
wavelength lambda in metres, C_e = 1/a_e in 1/km. Profile sampling works in metres (90 m steps).
"""
import math

EARTH_R = 6371008.8          # sphere for great-circle geometry (same as reference.py)
K_FACTOR = 4.0 / 3.0         # rule 2: effective Earth radius factor
A_E_KM = K_FACTOR * 6371.0   # rule 2: a_e = k * 6371 km
C_E = 1.0 / A_E_KM           # P.526 effective Earth curvature (1/km)
C_LIGHT = 3.0e8              # lambda = c / f (as in reference.py)
H_RX = 1.5                   # rule 1: rx height above ground (m)
STEP_M = 90.0                # rule 1: profile sample spacing
STEPS_PER_DEG = 1200         # DEM grid: 3 arcseconds
N_RADIALS = 360              # rule 6
N_BINS = 256
R_MIN_M = 50.0
R_MAX_M = 80000.0
CODE_STEP_DB = 0.25
CODE_MAX = 255               # 63.75 dB


# ---------------------------------------------------------------- rules 3-5: Bullington and excess loss

def j_nu(nu):
    """P.526-16 eq. 31: knife-edge loss J(nu) (dB) for nu > -0.78, else 0."""
    if nu <= -0.78:
        return 0.0
    return 6.9 + 20.0 * math.log10(math.sqrt((nu - 0.1) ** 2 + 1.0) + nu - 0.1)


def _bulge_heights(d, h):
    """h_i + 500 C_e d_i (d - d_i): the Earth-curvature term of P.526-16 eqs. 41-44 (d in km, h in m)."""
    dd = d[-1]
    return [h[i] + 500.0 * C_E * d[i] * (dd - d[i]) for i in range(len(d))]


def nu_max_los(d, h, h_ts, h_rs, f_mhz):
    """P.526-16 eq. 44: the highest diffraction parameter over the interior points (line-of-sight case)."""
    dd = d[-1]
    lam = C_LIGHT / (f_mhz * 1e6)
    hb = _bulge_heights(d, h)
    best = -math.inf
    for i in range(1, len(d) - 1):
        di = d[i]
        nu = (hb[i] - (h_ts * (dd - di) + h_rs * di) / dd) * math.sqrt(0.002 * dd / (lam * di * (dd - di)))
        if nu > best:
            best = nu
    return best


def bullington(d, h, h_ts, h_rs, f_mhz):
    """P.526-16 section 4.5.3 eqs. 41-51, Bullington construction over a profile.

    `d` (km) and `h` (m amsl) include both terminals' ground points (d[0] = 0, d[-1] = path length);
    `h_ts`, `h_rs` are the antenna heights above sea level (m). Returns (L_uc, L_b) in dB."""
    n = len(d)
    if n < 3:
        return 0.0, 0.0                       # no interior point: nothing to diffract over
    dd = d[-1]
    lam = C_LIGHT / (f_mhz * 1e6)
    hb = _bulge_heights(d, h)
    # Eq. 41: highest slope from the transmitter to an interior point (m/km).
    s_tim = max((hb[i] - h_ts) / d[i] for i in range(1, n - 1))
    # Eq. 42: slope of the line from transmitter to receiver.
    s_tr = (h_rs - h_ts) / dd
    s_rim = None
    if s_tim >= s_tr:
        # Eq. 45: highest slope from the receiver to an interior point.
        s_rim = max((hb[i] - h_rs) / (dd - d[i]) for i in range(1, n - 1))
    if s_tim < s_tr or s_tim + s_rim <= 0.0:
        # Eqs. 43-44: line of sight (the guard covers exact grazing, where eq. 46 is 0/0 and nu = 0).
        l_uc = j_nu(nu_max_los(d, h, h_ts, h_rs, f_mhz))
    else:
        # Eq. 46: distance of the Bullington point from the transmitter (km).
        d_b = (h_rs - h_ts + s_rim * dd) / (s_tim + s_rim)
        # Eq. 47: diffraction parameter at the Bullington point; eq. 48: L_uc = J(nu_b).
        nu_b = ((h_ts + s_tim * d_b - (h_ts * (dd - d_b) + h_rs * d_b) / dd)
                * math.sqrt(0.002 * dd / (lam * d_b * (dd - d_b))))
        l_uc = j_nu(nu_b)
    # Eq. 49 (rule 3): L_b = L_uc + [1 - exp(-L_uc/6)](10 + 0.02 d).
    l_b = l_uc + (1.0 - math.exp(-l_uc / 6.0)) * (10.0 + 0.02 * dd)
    return l_uc, l_b


def smooth_terminal_heights(d, h, h_ts, h_rs):
    """P.526-16 section 4.5.3 eqs. 52-55 (Ruling A3): heights of the smooth-Earth surface at the
    transmitter and receiver (m amsl), from the least-squares straight line through the profile,
    lowered where the profile obstructs the terminal line, and never above the terminals' ground."""
    n = len(d)
    dd = d[-1]
    # Eq. 52: v1, v2 over all profile segments.
    v1 = 0.0
    v2 = 0.0
    for i in range(1, n):
        dx = d[i] - d[i - 1]
        v1 += dx * (h[i] + h[i - 1])
        v2 += dx * (h[i] * (2.0 * d[i] + d[i - 1]) + h[i - 1] * (d[i] + 2.0 * d[i - 1]))
    # Eq. 53: the least-squares line at each end.
    h_stip = (2.0 * v1 * dd - v2) / (dd * dd)
    h_srip = (v2 - v1 * dd) / (dd * dd)
    # Eq. 54: the highest obstruction above the terminal line (no curvature term) and its slopes.
    h_obs = -math.inf
    a_obt = -math.inf
    a_obr = -math.inf
    for i in range(1, n - 1):
        hi = h[i] - (h_ts * (dd - d[i]) + h_rs * d[i]) / dd
        h_obs = max(h_obs, hi)
        a_obt = max(a_obt, hi / d[i])
        a_obr = max(a_obr, hi / (dd - d[i]))
    if h_obs <= 0.0:
        h_stp, h_srp = h_stip, h_srip
    else:
        g_t = a_obt / (a_obt + a_obr)
        g_r = a_obr / (a_obt + a_obr)
        h_stp = h_stip - h_obs * g_t
        h_srp = h_srip - h_obs * g_r
    # Eq. 55: the smooth surface never rises above the ground at either terminal.
    return min(h_stp, h[0]), min(h_srp, h[-1])


def terrain_excess(dist_m, h, h_ts, h_rs, f_mhz):
    """Rules 3-5 for one profile: (L_bulla, L_bulls, L_terr) in dB.

    `dist_m` are the sample distances from the transmitter (m), `h` the ground heights (m amsl) at them,
    `h_ts` / `h_rs` the antenna heights (m amsl)."""
    if len(dist_m) < 3:
        return 0.0, 0.0, 0.0
    d = [x / 1000.0 for x in dist_m]
    l_bulla = bullington(d, h, h_ts, h_rs, f_mhz)[1]
    h_st, h_sr = smooth_terminal_heights(d, h, h_ts, h_rs)
    # Rule 4: the same construction with every height on the smooth surface (0) and the terminals
    # measured from it: h_ts' = h_ts - h_st, h_rs' = h_rs - h_sr.
    l_bulls = bullington(d, [0.0] * len(d), h_ts - h_st, h_rs - h_sr, f_mhz)[1]
    return l_bulla, l_bulls, max(0.0, l_bulla - l_bulls)


# ---------------------------------------------------------------- rule 1: profiles

def profile_distances_m(d_m):
    """Samples every 90 m from the transmitter while below d, then the endpoint d."""
    out = []
    i = 0
    while i * STEP_M < d_m:
        out.append(i * STEP_M)
        i += 1
    out.append(d_m)
    return out


def destination(lat, lon, bearing, dist_m):
    """Point at `dist_m` along the great circle leaving (lat, lon) at `bearing` (sphere EARTH_R)."""
    p1, l1, th = math.radians(lat), math.radians(lon), math.radians(bearing)
    delta = dist_m / EARTH_R
    sp = math.sin(p1) * math.cos(delta) + math.cos(p1) * math.sin(delta) * math.cos(th)
    p2 = math.asin(max(-1.0, min(1.0, sp)))
    l2 = l1 + math.atan2(math.sin(th) * math.sin(delta) * math.cos(p1), math.cos(delta) - math.sin(p1) * sp)
    return math.degrees(p2), (math.degrees(l2) + 540.0) % 360.0 - 180.0


def _unit(lat, lon):
    p, l = math.radians(lat), math.radians(lon)
    return math.cos(p) * math.cos(l), math.cos(p) * math.sin(l), math.sin(p)


def great_circle_point(lat1, lon1, lat2, lon2, frac):
    """Spherical interpolation from point 1 (frac 0) to point 2 (frac 1)."""
    a, b = _unit(lat1, lon1), _unit(lat2, lon2)
    dot = max(-1.0, min(1.0, a[0] * b[0] + a[1] * b[1] + a[2] * b[2]))
    omega = math.acos(dot)
    if omega < 1e-15:
        return lat1, lon1
    s = math.sin(omega)
    wa, wb = math.sin((1.0 - frac) * omega) / s, math.sin(frac * omega) / s
    x, y, z = (wa * a[k] + wb * b[k] for k in range(3))
    return math.degrees(math.atan2(z, math.hypot(x, y))), math.degrees(math.atan2(y, x))


def haversine_m(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2.0) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2.0) ** 2
    return 2.0 * EARTH_R * math.asin(min(1.0, math.sqrt(a)))


class DemGrid:
    """A mosaic on the 3-arcsecond chunk grid: node (row, col) at latitude (south_index + row)/1200 and
    longitude (west_index + col)/1200 degrees; row 0 is the south edge; `h` is row-major int16 metres."""

    def __init__(self, south_index, west_index, rows, cols, h):
        assert len(h) == rows * cols
        self.south_index, self.west_index = south_index, west_index
        self.rows, self.cols = rows, cols
        self.h = h
        self.south = south_index / float(STEPS_PER_DEG)
        self.west = west_index / float(STEPS_PER_DEG)

    def height(self, lat, lon):
        """Bilinear height (m) in lat/lon from the four surrounding nodes, or None outside the grid."""
        fr = (lat - self.south) * STEPS_PER_DEG
        fc = (lon - self.west) * STEPS_PER_DEG
        if not (0.0 <= fr <= self.rows - 1 and 0.0 <= fc <= self.cols - 1):
            return None
        r0 = min(int(math.floor(fr)), self.rows - 2)
        c0 = min(int(math.floor(fc)), self.cols - 2)
        tr, tc = fr - r0, fc - c0
        h, w = self.h, self.cols
        k = r0 * w + c0
        south = h[k] * (1.0 - tc) + h[k + 1] * tc
        north = h[k + w] * (1.0 - tc) + h[k + w + 1] * tc
        return south * (1.0 - tr) + north * tr


def point_excess(dem, site_lat, site_lon, h_tx_agl, lat, lon, f_mhz):
    """Rules 1-5 from a site to one point: (L_terr dB, profile complete). Missing DEM -> (0, False)."""
    d_m = haversine_m(site_lat, site_lon, lat, lon)
    dist = profile_distances_m(d_m)
    h = []
    for x in dist:
        p = great_circle_point(site_lat, site_lon, lat, lon, x / d_m) if d_m > 0.0 else (site_lat, site_lon)
        v = dem.height(p[0], p[1])
        if v is None:
            return 0.0, False
        h.append(v)
    return terrain_excess(dist, h, h[0] + max(h_tx_agl, 3.0), h[-1] + H_RX, f_mhz)[2], True


# ---------------------------------------------------------------- rule 6: polar tables

def range_bins_m():
    """r_j = 50 m * (80 000 / 50)^(j / 255), j = 0..255."""
    return [R_MIN_M * (R_MAX_M / R_MIN_M) ** (j / (N_BINS - 1.0)) for j in range(N_BINS)]


def quantise(l_db):
    """uint8 code in 0.25 dB steps, rounded half up, capped at 255 (63.75 dB)."""
    return min(CODE_MAX, int(math.floor(l_db / CODE_STEP_DB + 0.5)))


def mask_bit(mask, k):
    return (mask[k >> 3] >> (k & 7)) & 1


def polar_table(dem, site_lat, site_lon, h_tx_agl, f_mhz, radials=None):
    """Rule 6 table for one (site, frequency): (codes, mask).

    `codes[b * 256 + j]` is quantise(L_terr) on radial b (bearing b degrees true) at range r_j, 0 where the
    profile is incomplete. `mask` has one bit per cell (byte k >> 3, bit k & 7, LSB first): 1 when every
    profile sample, endpoint included, had DEM. The profile to cell (b, j) samples the radial at 90 m steps
    below r_j and at r_j itself (the great circle from the site toward the cell's point). `h_tx_agl` is
    clamped to >= 3 m (M2 rule 1). `radials` limits the bearings computed (tests); the others stay 0."""
    codes = [0] * (N_RADIALS * N_BINS)
    mask = bytearray(N_RADIALS * N_BINS // 8)
    ground = dem.height(site_lat, site_lon)
    if ground is None:
        return codes, mask
    h_ts = ground + max(h_tx_agl, 3.0)
    bins = range_bins_m()
    for b in (range(N_RADIALS) if radials is None else radials):
        # Shared radial samples at i * 90 m (None once a sample has no DEM).
        samples = [ground]
        i = 1
        while i * STEP_M < R_MAX_M:
            p = destination(site_lat, site_lon, float(b), i * STEP_M)
            v = dem.height(p[0], p[1])
            if v is None:
                break
            samples.append(v)
            i += 1
        for j, r in enumerate(bins):
            n_below = int(math.ceil(r / STEP_M)) - 1      # last i with i * 90 < r
            if n_below + 1 > len(samples):
                break                                     # this and every longer bin leave the DEM
            p = destination(site_lat, site_lon, float(b), r)
            end = dem.height(p[0], p[1])
            if end is None:
                continue
            dist = [k * STEP_M for k in range(n_below + 1)] + [r]
            h = samples[:n_below + 1] + [end]
            k = b * N_BINS + j
            codes[k] = quantise(terrain_excess(dist, h, h_ts, end + H_RX, f_mhz)[2])
            mask[k >> 3] |= 1 << (k & 7)
    return codes, mask


def table_lookup(codes, mask, bearing, r_m):
    """Rule 6 renderer lookup: (L_terr dB, complete), bilinear in bearing (wrapped) and ln r.

    r < 50 m -> (0, True); r > 80 km -> (0, False); any of the four cells incomplete -> (0, False)."""
    if r_m < R_MIN_M:
        return 0.0, True
    if r_m > R_MAX_M:
        return 0.0, False
    u = math.log(r_m / R_MIN_M) / math.log(R_MAX_M / R_MIN_M) * (N_BINS - 1)
    j0 = min(int(math.floor(u)), N_BINS - 1)
    j1 = min(j0 + 1, N_BINS - 1)
    tj = u - j0
    bf = math.floor(bearing)
    b0 = int(bf) % N_RADIALS
    b1 = (b0 + 1) % N_RADIALS
    tb = bearing - bf
    cells = (b0 * N_BINS + j0, b0 * N_BINS + j1, b1 * N_BINS + j0, b1 * N_BINS + j1)
    if not all(mask_bit(mask, k) for k in cells):
        return 0.0, False
    v00, v01, v10, v11 = (codes[k] * CODE_STEP_DB for k in cells)
    return ((v00 * (1.0 - tj) + v01 * tj) * (1.0 - tb) + (v10 * (1.0 - tj) + v11 * tj) * tb), True


def frequency_key_mhz(f_mhz):
    """Rule 6: carriers of a site share a table when their frequency rounds to the same 10 MHz."""
    return int(math.floor(f_mhz / 10.0 + 0.5)) * 10
