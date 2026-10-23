"""Reference implementation of the Signal Atlas "Coverage model" rules 1-9.

Pure functions (stdlib `math` only) plus a tile renderer. The C++ engine (engine/src/main/cpp)
must reproduce these numbers; tools/engine/make_vectors.py writes them as test vectors.
Interpretation choices are listed in tools/engine/README.md.

Conventions: frequencies in MHz, heights in metres, angles in degrees, `None` = "not supplied"
(the C++ side uses NaN). Rounding to an integer code is floor(x + 0.5).
"""
import math

AREAS = ("DENSE_URBAN", "URBAN", "SUBURBAN", "RURAL")
H_RX = 1.5                 # UE height (m)
C_LIGHT = 3.0e8            # TR 38.901 Table 7.4.1-1 note: c = 3.0e8 m/s
H_E = 1.0                  # h_E for d'BP: exact for h_UT < 13 m, where C(d2D, h_UT) = 0 (Table 7.4.1-1 note 1)
EARTH_R = 6371008.8        # mean Earth radius (m) for haversine
NO_COVERAGE_DBM = -200.0   # grid value where no carrier was evaluated (quantises to code 0)
NO_BEST = 65535
DEFAULT_HBW = 65.0
DEFAULT_FB = 25.0
DEFAULT_GAIN = 15.0
R_MAX_CAP_KM = 80.0
RSRP_FLOOR_DBM = -140.0


def round_half_up(x):
    return int(math.floor(x + 0.5))


def clamp(x, lo, hi):
    return lo if x < lo else hi if x > hi else x


def h_tx_clamped(h):
    """Rule 1: transmitter height above ground, at least 3 m."""
    return max(h, 3.0)


# ---------------------------------------------------------------- rule 2: EIRP per resource element

LTE_NRB = {1_400_000: 6, 3_000_000: 15, 5_000_000: 25, 10_000_000: 50, 15_000_000: 75, 20_000_000: 100}
NR_NRB_15 = {5: 25, 10: 52, 15: 79, 20: 106, 25: 133, 30: 160, 40: 216, 50: 270}
NR_NRB_30 = {10: 24, 15: 38, 20: 51, 25: 65, 30: 78, 40: 106, 50: 133, 60: 162, 70: 189, 80: 217,
             90: 245, 100: 273}
NR_NRB_120 = {50: 32, 100: 66, 200: 132, 400: 264}


def scs_khz(tech, f_mhz):
    """Sub-carrier spacing: LTE 15 kHz; NR 15 below 2.3 GHz, 120 from 24.25 GHz, else 30."""
    if tech == "LTE" or f_mhz < 2300.0:
        return 15
    if f_mhz >= 24250.0:
        return 120
    return 30


def n_rb(tech, f_mhz, bw_hz):
    scs = scs_khz(tech, f_mhz)
    if tech == "LTE":
        table = LTE_NRB
    else:
        table = {mhz * 1_000_000: v for mhz, v in {15: NR_NRB_15, 30: NR_NRB_30, 120: NR_NRB_120}[scs].items()}
    if bw_hz in table:
        return table[bw_hz]
    return max(6, int(math.floor(bw_hz * 0.9 / (12 * scs * 1000.0))))


def eirp_per_re(eirp_dbm, tech, f_mhz, bw_hz):
    return eirp_dbm - 10.0 * math.log10(12 * n_rb(tech, f_mhz, bw_hz))


# ---------------------------------------------------------------- rules 3-4: antenna

def wrap180(deg):
    """Wrapped difference in [-180, 180)."""
    return (deg + 180.0) % 360.0 - 180.0


def horizontal_attenuation(bearing, az, hbw, fb, hrp_att=None, hrp_peak=0):
    """Rule 3. `hrp_att` is the 360-entry quantised relative pattern (0.25 dB steps) or None."""
    if hrp_att is not None:
        return hrp_att[(round_half_up(bearing) - hrp_peak) % 360] * 0.25
    if az is None:
        return 0.0
    phi3 = DEFAULT_HBW if hbw is None else hbw
    fbv = DEFAULT_FB if fb is None else fb
    return min(12.0 * (wrap180(bearing - az) / phi3) ** 2, fbv)


def vertical_beamwidth(az, hbw, gain):
    phi3 = 360.0 if az is None else (DEFAULT_HBW if hbw is None else hbw)
    g = DEFAULT_GAIN if gain is None else gain
    return clamp(32400.0 / (10.0 ** (g / 10.0) * phi3), 4.0, 30.0)


def effective_tilt(tilt, h_tx, f_mhz):
    """Rule 4 downtilt (positive down): min(|ACMA tilt|, 15) when non-zero (Ruling CC; ACMA uses
    both signs for downtilt), else by height (+2 deg at >= 24.25 GHz)."""
    if tilt != 0.0:
        return min(abs(tilt), 15.0)
    h = h_tx_clamped(h_tx)
    if h < 15.0:
        t = 6.0
    elif h < 30.0:
        t = 4.0
    elif h <= 50.0:
        t = 3.0
    else:
        t = 2.0
    return t + 2.0 if f_mhz >= 24250.0 else t


def elevation_deg(h_tx, d_m):
    """Depression angle from the antenna to the UE (positive downwards)."""
    return math.degrees(math.atan2(h_tx_clamped(h_tx) - H_RX, d_m))


def antenna_attenuation(bearing, d_m, az, hbw, fb, gain, tilt, h_tx, f_mhz, hrp_att=None, hrp_peak=0):
    """Rules 3-4: combined attenuation A (dB, >= 0) at `bearing` and horizontal distance `d_m`."""
    a_h = horizontal_attenuation(bearing, az, hbw, fb, hrp_att, hrp_peak)
    theta3 = vertical_beamwidth(az, hbw, gain)
    t = effective_tilt(tilt, h_tx, f_mhz)
    a_v = min(12.0 * ((elevation_deg(h_tx, d_m) - t) / theta3) ** 2, 30.0)
    fbv = DEFAULT_FB if fb is None else fb
    return min(a_h + a_v, max(fbv, 30.0))


# ---------------------------------------------------------------- rule 5: area class

def area_class(sites_within_3km):
    if sites_within_3km >= 40:
        return "DENSE_URBAN"
    if sites_within_3km >= 12:
        return "URBAN"
    if sites_within_3km >= 3:
        return "SUBURBAN"
    return "RURAL"


# ---------------------------------------------------------------- rule 6: path loss

def _hata_ahm_and_corr(f_mhz, area):
    logf = math.log10(f_mhz)
    if area == "DENSE_URBAN":
        ahm = 3.2 * math.log10(11.75 * H_RX) ** 2 - 4.97
    else:
        ahm = (1.1 * logf - 0.7) * H_RX - (1.56 * logf - 0.8)
    if area == "SUBURBAN":
        corr = -2.0 * math.log10(f_mhz / 28.0) ** 2 - 5.4
    elif area == "RURAL":
        corr = -4.78 * logf ** 2 + 18.33 * logf - 35.94
    else:
        corr = 0.0
    return ahm, corr


def hata(f_mhz, d_km, h_tx, area):
    """Okumura-Hata (f < 1500 MHz)."""
    hb = clamp(h_tx, 3.0, 200.0)
    ahm, corr = _hata_ahm_and_corr(f_mhz, area)
    logd = math.log10(d_km)
    return (69.55 + 26.16 * math.log10(f_mhz) - 13.82 * math.log10(hb) - ahm
            + (44.9 - 6.55 * math.log10(hb)) * logd + corr)


def cost231(f_mhz, d_km, h_tx, area):
    """COST-231 Hata (1500..2200 MHz)."""
    hb = clamp(h_tx, 3.0, 200.0)
    ahm, corr = _hata_ahm_and_corr(f_mhz, area)
    cm = 3.0 if area == "DENSE_URBAN" else 0.0
    logd = math.log10(d_km)
    return (46.3 + 33.9 * math.log10(f_mhz) - 13.82 * math.log10(hb) - ahm
            + (44.9 - 6.55 * math.log10(hb)) * logd + cm + corr)


def _blend(p_los, pl_los, pl_nlos, s_los, s_nlos):
    """Rule 6 (Ruling DD): LOS/NLOS blended in the dB domain, as is sigma."""
    return p_los * pl_los + (1.0 - p_los) * pl_nlos, p_los * s_los + (1.0 - p_los) * s_nlos


def uma(fc, d2, d3, h_bs):
    """TR 38.901 Table 7.4.1-1 UMa; LOS probability Table 7.4.2-1 (h_UT <= 13 m form)."""
    dbp = 4.0 * (h_bs - H_E) * (H_RX - H_E) * fc * 1e9 / C_LIGHT        # d'BP, note 1 (h' = h - hE, hE = 1)
    if d2 <= dbp:
        pl_los = 28.0 + 22.0 * math.log10(d3) + 20.0 * math.log10(fc)    # PL1
    else:                                                                # PL2
        pl_los = (28.0 + 40.0 * math.log10(d3) + 20.0 * math.log10(fc)
                  - 9.0 * math.log10(dbp ** 2 + (h_bs - H_RX) ** 2))
    pl_nlos_p = 13.54 + 39.08 * math.log10(d3) + 20.0 * math.log10(fc) - 0.6 * (H_RX - 1.5)
    pl_nlos = max(pl_los, pl_nlos_p)
    p = 1.0 if d2 <= 18.0 else 18.0 / d2 + math.exp(-d2 / 63.0) * (1.0 - 18.0 / d2)
    return _blend(p, pl_los, pl_nlos, 4.0, 6.0)


def _rma_pl1(fc, d3, h):
    return (20.0 * math.log10(40.0 * math.pi * d3 * fc / 3.0) + min(0.03 * h ** 1.72, 10.0) * math.log10(d3)
            - min(0.044 * h ** 1.72, 14.77) + 0.002 * math.log10(h) * d3)


def rma(fc, d2, d3, h_bs, h=5.0, w=20.0):
    """TR 38.901 Table 7.4.1-1 RMa (h = 5 m, W = 20 m); LOS probability Table 7.4.2-1."""
    dbp = 2.0 * math.pi * h_bs * H_RX * fc * 1e9 / C_LIGHT              # d_BP, note 5 (actual heights)
    if d2 <= dbp:
        pl_los = _rma_pl1(fc, d3, h)                                      # PL1
    else:
        pl_los = _rma_pl1(fc, dbp, h) + 40.0 * math.log10(d3 / dbp)       # PL2
    pl_nlos_p = (161.04 - 7.1 * math.log10(w) + 7.5 * math.log10(h)
                 - (24.37 - 3.7 * (h / h_bs) ** 2) * math.log10(h_bs)
                 + (43.42 - 3.1 * math.log10(h_bs)) * (math.log10(d3) - 3.0)
                 + 20.0 * math.log10(fc) - (3.2 * math.log10(11.75 * H_RX) ** 2 - 4.97))
    pl_nlos = max(pl_los, pl_nlos_p)
    p = 1.0 if d2 <= 10.0 else math.exp(-(d2 - 10.0) / 1000.0)
    return _blend(p, pl_los, pl_nlos, 4.0, 8.0)


def umi(fc, d2, d3, h_bs):
    """TR 38.901 Table 7.4.1-1 UMi-Street Canyon; LOS probability Table 7.4.2-1."""
    dbp = 4.0 * (h_bs - H_E) * (H_RX - H_E) * fc * 1e9 / C_LIGHT        # d'BP, note 1
    if d2 <= dbp:
        pl_los = 32.4 + 21.0 * math.log10(d3) + 20.0 * math.log10(fc)    # PL1
    else:                                                                # PL2
        pl_los = (32.4 + 40.0 * math.log10(d3) + 20.0 * math.log10(fc)
                  - 9.5 * math.log10(dbp ** 2 + (h_bs - H_RX) ** 2))
    pl_nlos_p = 35.3 * math.log10(d3) + 22.4 + 21.3 * math.log10(fc) - 0.3 * (H_RX - 1.5)
    pl_nlos = max(pl_los, pl_nlos_p)
    p = 1.0 if d2 <= 18.0 else 18.0 / d2 + math.exp(-d2 / 36.0) * (1.0 - 18.0 / d2)
    return _blend(p, pl_los, pl_nlos, 4.0, 7.82)


def path_loss(f_mhz, d_km, h_tx, area):
    """Rule 6. `d_km` is the 3D distance. Returns (PL dB, blended sigma dB, model name)."""
    d_km = max(d_km, 0.02)
    h = h_tx_clamped(h_tx)
    if f_mhz < 1500.0:
        return hata(f_mhz, d_km, h, area), 8.0, "HATA"
    if f_mhz <= 2200.0:
        return cost231(f_mhz, d_km, h, area), 8.0, "COST231"
    fc = f_mhz / 1000.0
    dh = h - H_RX
    d2 = math.sqrt(max((d_km * 1000.0) ** 2 - dh * dh, 0.0))
    d2 = max(d2, 10.0)                    # lower validity bound (10 m) by clamping
    d3 = math.sqrt(d2 * d2 + dh * dh)
    if f_mhz >= 24250.0:
        pl, sigma = umi(fc, d2, d3, h)
        return pl, sigma, "UMI"
    if area in ("DENSE_URBAN", "URBAN"):
        pl, sigma = uma(fc, d2, d3, h)
        return pl, sigma, "UMA"
    pl, sigma = rma(fc, d2, d3, h)
    return pl, sigma, "RMA"


# ---------------------------------------------------------------- rules 7-8

def o2i_low_loss_db(f_mhz):
    """Rule 7: 3GPP O2I low-loss model (f in GHz), including the 2.5 dB term."""
    f = f_mhz / 1000.0
    return 5.0 - 10.0 * math.log10(0.3 * 10.0 ** (-(2.0 + 0.2 * f) / 10.0)
                                   + 0.7 * 10.0 ** (-(5.0 + 4.0 * f) / 10.0)) + 2.5


O2I_SIGMA_DB = 4.4


def indoor_sigma(sigma_db):
    """Rule 7 (Ruling EE): outdoor and O2I shadowing combined in quadrature."""
    return math.sqrt(sigma_db * sigma_db + O2I_SIGMA_DB * O2I_SIGMA_DB)


def noise_dbm(scs_hz):
    return -174.0 + 10.0 * math.log10(scs_hz) + 7.0


def coverage_probability(rsrp_dbm, thr_dbm, sigma_db):
    return 0.5 * math.erfc((thr_dbm - rsrp_dbm) / (sigma_db * math.sqrt(2.0)))


def max_range_km(eirp_re_dbm, f_mhz, h_tx, area):
    """Rule 9 culling radius: the 3D distance where eirpRe - PL (A = 0, outdoor) falls to -140 dBm,
    by 60 bisection steps on [0.02, 80] km; 80 when still above at 80 km, 0 when below at 0.02 km.
    The culling radius itself is `culling_range_km` (the maximum over the area classes)."""
    def margin(d):
        return eirp_re_dbm - path_loss(f_mhz, d, h_tx, area)[0] - RSRP_FLOOR_DBM
    lo, hi = 0.02, R_MAX_CAP_KM
    if margin(hi) >= 0.0:
        return R_MAX_CAP_KM
    if margin(lo) < 0.0:
        return 0.0
    for _ in range(60):
        mid = 0.5 * (lo + hi)
        if margin(mid) >= 0.0:
            lo = mid
        else:
            hi = mid
    return lo


def culling_range_km(eirp_re_dbm, f_mhz, h_tx):
    """Rule 9 R_max, once per carrier: the maximum of `max_range_km` over all four area classes, so
    culling never drops a carrier that the tile's class would reach."""
    return max(max_range_km(eirp_re_dbm, f_mhz, h_tx, a) for a in AREAS)


# ---------------------------------------------------------------- rule 9: codes

def rsrp_code(rsrp):
    if rsrp < RSRP_FLOOR_DBM:
        return 0
    return clamp(round_half_up((rsrp + 140.0) * 2.0), 1, 254)


def sinr_code(sinr):
    return clamp(round_half_up((sinr + 20.0) * 4.0), 0, 255)


def prob_code(p):
    return clamp(round_half_up(p * 255.0), 0, 255)


# ---------------------------------------------------------------- geometry

def tile_lon(x_frac, z):
    return x_frac / (1 << z) * 360.0 - 180.0


def tile_lat(y_frac, z):
    return math.degrees(math.atan(math.sinh(math.pi * (1.0 - 2.0 * y_frac / (1 << z)))))


def tile_for(lat, lon, z):
    n = 1 << z
    x = int(math.floor((lon + 180.0) / 360.0 * n))
    y = int(math.floor((1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * n))
    return x, y


def haversine_m(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2.0) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2.0) ** 2
    return 2.0 * EARTH_R * math.asin(min(1.0, math.sqrt(a)))


def bearing_deg(lat1, lon1, lat2, lon2):
    """Initial great-circle bearing from point 1 to point 2, in [0, 360)."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return math.degrees(math.atan2(y, x)) % 360.0


def tile_bounds(z, x, y):
    """(lat_south, lat_north, lon_west, lon_east)."""
    return tile_lat(y + 1, z), tile_lat(y, z), tile_lon(x, z), tile_lon(x + 1, z)


def tile_centre(z, x, y):
    return tile_lat(y + 0.5, z), tile_lon(x + 0.5, z)


def tile_area(sites, z, x, y):
    """Rule 5 for a tile: `sites` is an iterable of (lat, lon) of every kept site (any network)."""
    clat, clon = tile_centre(z, x, y)
    n = sum(1 for lat, lon in sites if haversine_m(clat, clon, lat, lon) <= 3000.0)
    return area_class(n), n


def reaches_tile(lat, lon, r_max_km, bounds):
    """Rule 9 culling: the carrier's R_max reaches the nearest point of the tile's bounding box."""
    s, n, w, e = bounds
    return haversine_m(lat, lon, clamp(lat, s, n), clamp(lon, w, e)) <= r_max_km * 1000.0


# ---------------------------------------------------------------- calibration (M4 Calibration rules 4-6)

CAL_RATIO_MIN = 0.1        # the slope term's d / d_median is clamped to [0.1, 10] (no extrapolation past a decade)
CAL_RATIO_MAX = 10.0
CAL_SIGMA_FLOOR_DB = 4.0   # rule 6


def _finite(v):
    return v is not None and math.isfinite(v)


def calibration_db(offset_db, slope, d_med_km, d2_m):
    """Rules 4: mu + b * log10(clamp(d / d_median, 0.1, 10)) at horizontal distance d2_m (m). None / NaN offset is
    0; a None / NaN slope or a d_median that is not > 0 drops the slope term."""
    o = offset_db if _finite(offset_db) else 0.0
    if not (_finite(slope) and _finite(d_med_km) and d_med_km > 0.0) or slope == 0.0:
        return o
    ratio = clamp(d2_m / (d_med_km * 1000.0), CAL_RATIO_MIN, CAL_RATIO_MAX)
    return o + slope * math.log10(ratio)


def calibrated_sigma(sigma_db, scale):
    """Rule 6 as applied per carrier: max(4, sigma * scale) when scale < 1 (negative counts as 0), else sigma."""
    if not (_finite(scale) and scale < 1.0):
        return sigma_db
    return max(CAL_SIGMA_FLOOR_DB, sigma_db * max(scale, 0.0))


def _cal(carriers, key, i):
    col = carriers.get(key)
    return None if col is None else col[i]


def carrier_usable(carriers, i):
    lat, lon, e = carriers["lat"][i], carriers["lon"][i], carriers["eirpRe"][i]
    return all(_finite(v) for v in (lat, lon, e)) and abs(lat) <= 90.0 and abs(lon) <= 540.0


def carrier_rsrp(carriers, i, patterns, lat, lon, area, indoor, terrain=None):
    """One carrier at one point: (RSRP dBm, sigma dB, terrain complete). Rules 3-8, Terrain rule 5, Calibration rules
    4 and 6 (the optional `calOffsetDb`, `calSlope`, `calDmedKm`, `calSigmaScale` columns; no residual raster)."""
    h = h_tx_clamped(carriers["hTx"][i])
    d2 = haversine_m(carriers["lat"][i], carriers["lon"][i], lat, lon)
    d3 = math.sqrt(d2 * d2 + (h - H_RX) ** 2)
    brg = bearing_deg(carriers["lat"][i], carriers["lon"][i], lat, lon)
    pid = carriers["hrpId"][i]
    att = patterns[pid - 1] if pid > 0 else None
    a = antenna_attenuation(brg, d2, carriers["az"][i], carriers["hBw"][i], carriers["fb"][i],
                            carriers["gain"][i], carriers["tilt"][i], carriers["hTx"][i],
                            carriers["freqMhz"][i], att, carriers["hrpPeak"][i])
    pl, sg, _ = path_loss(carriers["freqMhz"][i], d3 / 1000.0, carriers["hTx"][i], area)
    if indoor:
        pl += o2i_low_loss_db(carriers["freqMhz"][i])
        sg = indoor_sigma(sg)
    l_terr, full = terrain(i, brg, d2) if terrain is not None else (0.0, False)
    cal = calibration_db(_cal(carriers, "calOffsetDb", i), _cal(carriers, "calSlope", i),
                         _cal(carriers, "calDmedKm", i), d2)
    rsrp = carriers["eirpRe"][i] - a - pl - l_terr + cal    # Terrain rule 5, Calibration rule 4
    return rsrp, calibrated_sigma(sg, _cal(carriers, "calSigmaScale", i)), full


def predict_point(carriers, patterns, lat, lon, area, indoor=False, terrain=None):
    """M4 point query: every carrier's RSRP at (lat, lon), no culling; None for an unusable carrier."""
    return [carrier_rsrp(carriers, i, patterns, lat, lon, area, indoor, terrain)[0]
            if carrier_usable(carriers, i) else None for i in range(carriers["n"])]


def residual_pixel(index, grid):
    """Calibration rule 5: the raster pixel (0..255) that grid index `index` of a `grid` x `grid` render samples."""
    return int(math.floor((index + 0.5) * 256.0 / grid))


# ---------------------------------------------------------------- tile renderer

def _sample(carriers, idx, patterns, lat, lon, area, indoor, thr, terrain=None, residual=None):
    """Best-server RSRP, SINR, probability, carrier index and whether the best server's terrain loss
    came from a complete profile, at one point. `terrain(i, bearing, d2D)` -> (L_terr dB, complete);
    `residual(i)` -> the residual rasters' sum (dB) for carrier i at this point."""
    rsrp = []
    sigma = []
    complete = []
    for i in idx:
        r, sg, full = carrier_rsrp(carriers, i, patterns, lat, lon, area, indoor, terrain)
        rsrp.append(r + (residual(i) if residual is not None else 0.0))    # Calibration rule 5
        sigma.append(sg)
        complete.append(full)
    if not idx:
        return NO_COVERAGE_DBM, -20.0, 0.0, NO_BEST, False
    b = 0
    for k in range(1, len(idx)):
        if rsrp[k] > rsrp[b]:        # ties keep the lower carrier index
            b = k
    best = idx[b]
    interference = 0.0
    for k, i in enumerate(idx):
        if carriers["freqKey"][i] == carriers["freqKey"][best] and \
                carriers["sectorKey"][i] != carriers["sectorKey"][best]:
            interference += 0.5 * 10.0 ** (rsrp[k] / 10.0)
    scs = scs_khz("NR" if carriers["tech"][best] == 1 else "LTE", carriers["freqMhz"][best]) * 1000
    sinr = rsrp[b] - 10.0 * math.log10(interference + 10.0 ** (noise_dbm(scs) / 10.0))
    prob = coverage_probability(rsrp[b], thr, sigma[b])
    return rsrp[b], sinr, prob, best, complete[b]


def render_tile(carriers, patterns, z, x, y, grid, area, indoor=False, thr_dbm=-118.0, terrain=None, residuals=None):
    """Rules 8-9: render one 256x256 tile from structure-of-arrays `carriers` (the tile JSON fields).

    `patterns[id - 1]` is the 360-entry quantised attenuation of pattern `id`. Returns a dict with
    rsrpCodes / sinrCodes / probCodes / best (row-major, 65536 each) and `evaluated` (carriers that
    passed culling). With `terrain(i, bearing, d2D)` -> (L_terr dB, complete) (Terrain rules 5-6) each
    carrier's RSRP loses L_terr, and `terrainComplete` (65536 bools) says whether the pixel's best server
    (nearest grid sample, as for `best`) had a complete profile. `residuals` (Calibration rule 5) is a list of
    (codes, network_mask): 65536 int8 codes (0.25 dB, row-major) added to every carrier whose `network` column bit
    is set in the mask, sampled at `residual_pixel` of each grid index."""
    bounds = tile_bounds(z, x, y)
    idx = [i for i in range(carriers["n"])
           if carrier_usable(carriers, i)
           and reaches_tile(carriers["lat"][i], carriers["lon"][i], carriers["rMaxKm"][i], bounds)]
    residuals = residuals or []
    nets = carriers.get("network")
    vals = []
    for j in range(grid):
        lat = tile_lat(y + (j + 0.5) / grid, z)
        row = []
        for i in range(grid):
            lon = tile_lon(x + (i + 0.5) / grid, z)
            pixel = residual_pixel(j, grid) * 256 + residual_pixel(i, grid)
            here = [(codes[pixel] * 0.25, mask) for codes, mask in residuals]

            def residual(k, here=here):
                if nets is None or not 0 <= nets[k] < 32:
                    return 0.0
                return sum(v for v, mask in here if (mask >> nets[k]) & 1)
            row.append(_sample(carriers, idx, patterns, lat, lon, area, indoor, thr_dbm, terrain,
                               residual if residuals else None))
        vals.append(row)

    out = {"rsrpCodes": [], "sinrCodes": [], "probCodes": [], "best": [], "evaluated": len(idx),
           "terrainComplete": []}
    if grid == 256:
        for j in range(256):
            for i in range(256):
                r, s, p, b, full = vals[j][i]
                c = rsrp_code(r)
                out["rsrpCodes"].append(c)
                out["sinrCodes"].append(sinr_code(s))
                out["probCodes"].append(prob_code(p))
                out["best"].append(NO_BEST if c == 0 else b)
                out["terrainComplete"].append(full)
        return out

    scale = grid / 256.0
    axis = []
    for p in range(256):
        g = clamp((p + 0.5) * scale - 0.5, 0.0, grid - 1.0)
        i0 = int(math.floor(g))
        i1 = min(i0 + 1, grid - 1)
        nearest = min(int(math.floor(g + 0.5)), grid - 1)
        axis.append((i0, i1, g - i0, nearest))
    for py in range(256):
        j0, j1, ty, jn = axis[py]
        for px in range(256):
            i0, i1, tx, inn = axis[px]
            v = []
            for k in range(3):
                top = vals[j0][i0][k] * (1.0 - tx) + vals[j0][i1][k] * tx
                bot = vals[j1][i0][k] * (1.0 - tx) + vals[j1][i1][k] * tx
                v.append(top * (1.0 - ty) + bot * ty)
            c = rsrp_code(v[0])
            out["rsrpCodes"].append(c)
            out["sinrCodes"].append(sinr_code(v[1]))
            out["probCodes"].append(prob_code(v[2]))
            out["best"].append(NO_BEST if c == 0 else vals[jn][inn][3])
            out["terrainComplete"].append(vals[jn][inn][4])
    return out
