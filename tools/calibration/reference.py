"""Reference implementation of the Signal Atlas M4 calibration rules 1-6 (matching score, estimator, residual grid).

Pure functions, stdlib only; run with `python3 -I`. The Kotlin module :core:calibration must reproduce these numbers;
tools/calibration/make_vectors.py writes them as test vectors. Change both together (shared-context Calibration rules).

Units: RSRP in dBm, distances in metres unless named _km, angles in degrees.
"""
import math

# Rule 1
FREQ_TOLERANCE_MHZ = 0.15
CANDIDATE_RADIUS_KM = 40.0
MAX_FIX_ACCURACY_M = 50.0
# Rule 2
TA_STEP_M = 150.0
TA_DB_PER_STEP = 3.0
OUT_OF_RANGE_PENALTY = 100.0
MISMATCH_CAP_DB = 20.0
DB_PER_DOUBLING = 6.0
NEAR_FLOOR_M = 300.0
LIKELY_MARGIN = 3.0
CONFIRM_AFTER = 5
# Rule 3
THROTTLE_M = 50.0
THROTTLE_MS = 5000
# Rule 4
PRIOR_SIGMA_DB = 8.0
MEAS_SIGMA_DB = 6.0
OFFSET_CLAMP_DB = 20.0
SLOPE_MIN_N = 30
SLOPE_MIN_SPREAD = 3.0
SLOPE_PRIOR_SIGMA = 10.0
# Rules 5-6
MIN_APPLIED = 3
RESIDUAL_K = 5.0
SIGMA_N0 = 10.0
SIGMA_FLOOR_DB = 4.0

EARTH_R = 6371008.8


def haversine_m(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2.0) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2.0) ** 2
    return 2.0 * EARTH_R * math.asin(min(1.0, math.sqrt(a)))


# ---------------------------------------------------------------- rules 1-2: candidates and score

def is_candidate(carrier_freq_mhz, channel_freq_mhz, distance_m):
    return abs(carrier_freq_mhz - channel_freq_mhz) <= FREQ_TOLERANCE_MHZ + 1e-9 and \
        distance_m <= CANDIDATE_RADIUS_KM * 1000.0


def match_score(predicted_dbm, measured_dbm, distance_m, ta_m, r_max_km, nearest_m=None):
    """Rule 2 (lower is better). `ta_m` None = no timing advance; `nearest_m` = nearest candidate's distance."""
    if nearest_m is None:
        nearest_m = distance_m
    s = min(abs(predicted_dbm - measured_dbm), MISMATCH_CAP_DB)
    s += DB_PER_DOUBLING * max(0.0, math.log2(distance_m / max(nearest_m, NEAR_FLOOR_M)))
    if ta_m is not None:
        s += abs(distance_m - ta_m) / TA_STEP_M * TA_DB_PER_STEP
    if distance_m > r_max_km * 1000.0:
        s += OUT_OF_RANGE_PENALTY
    return s


def confidence(scores):
    """'LIKELY' when the best beats the second by >= 3 points (or is alone), else 'UNCERTAIN'; None when empty."""
    if not scores:
        return None
    s = sorted(scores)
    if len(s) == 1 or s[1] - s[0] >= LIKELY_MARGIN:
        return "LIKELY"
    return "UNCERTAIN"


def throttle_ok(last, now_ms, lat, lon):
    """Rule 3: a new sample needs >= 50 m from the last stored one AND >= 5 s since it. `last` = (ms, lat, lon)."""
    if last is None:
        return True
    return now_ms - last[0] >= THROTTLE_MS and haversine_m(last[1], last[2], lat, lon) >= THROTTLE_M


# ---------------------------------------------------------------- rule 4: sector offset and slope

def offset_only(n, sum_r):
    """Bayesian normal mean with prior N(0, 8^2) and sigma_m 6 dB, clamped to +-20 dB."""
    if n == 0:
        return 0.0
    mu = (sum_r / MEAS_SIGMA_DB ** 2) / (1.0 / PRIOR_SIGMA_DB ** 2 + n / MEAS_SIGMA_DB ** 2)
    return max(-OFFSET_CLAMP_DB, min(OFFSET_CLAMP_DB, mu))


def median(xs):
    s = sorted(xs)
    n = len(s)
    if n == 0:
        return None
    return s[n // 2] if n % 2 == 1 else 0.5 * (s[n // 2 - 1] + s[n // 2])


def slope_regressor(d_km, d_med_km):
    """x = log10(d / d_median). Unclamped here, so the residual grid can be kept as per-sector sums (Kotlin); the
    engine clamps d / d_median to [0.1, 10] when applying the slope, which only matters beyond a decade from the
    median, where no fit is trusted anyway."""
    return math.log10(max(d_km, 1e-6) / d_med_km)


def fit_sector(residuals, distances_km):
    """Rule 4 for one sector's samples (r = M - P_hat, d km). Returns dict n, offset, slope (0 when not fitted),
    dMedianKm, slopeFitted. The slope is fitted jointly with the offset (MAP of r = mu + b x + e, x =
    log10(d / d_med), priors mu ~ N(0, 8^2), b ~ N(0, 10^2), e ~ N(0, 6^2)) when n >= 30 and
    max(d) / min(d) >= 3; then mu is clamped to +-20 dB."""
    n = len(residuals)
    d_med = median(distances_km) if n else 1.0
    if d_med is None or d_med <= 0.0:
        d_med = 1.0
    sum_r = sum(residuals)
    d_min = min(distances_km) if n else 0.0
    d_max = max(distances_km) if n else 0.0
    spread_ok = n > 0 and d_max >= SLOPE_MIN_SPREAD * max(d_min, 1e-3)
    if n < SLOPE_MIN_N or not spread_ok:
        return {"n": n, "offset": offset_only(n, sum_r), "slope": 0.0, "dMedianKm": d_med, "slopeFitted": False}
    xs = [slope_regressor(d, d_med) for d in distances_km]
    w = 1.0 / MEAS_SIGMA_DB ** 2
    a11 = n * w + 1.0 / PRIOR_SIGMA_DB ** 2
    a12 = sum(xs) * w
    a22 = sum(x * x for x in xs) * w + 1.0 / SLOPE_PRIOR_SIGMA ** 2
    b1 = sum_r * w
    b2 = sum(x * r for x, r in zip(xs, residuals)) * w
    det = a11 * a22 - a12 * a12
    mu = (b1 * a22 - a12 * b2) / det
    b = (a11 * b2 - a12 * b1) / det
    mu = max(-OFFSET_CLAMP_DB, min(OFFSET_CLAMP_DB, mu))
    return {"n": n, "offset": mu, "slope": b, "dMedianKm": d_med, "slopeFitted": True}


def correction_db(fit, d_km):
    """The applied sector term mu + b * x (0 below MIN_APPLIED samples, rule 4 / Review Focus 2)."""
    if fit["n"] < MIN_APPLIED:
        return 0.0
    return fit["offset"] + fit["slope"] * slope_regressor(d_km, fit["dMedianKm"])


def sigma_scale(n):
    return math.sqrt(SIGMA_N0 / (SIGMA_N0 + n))


def sigma_eff(sigma_db, n):
    """Rule 6."""
    return max(SIGMA_FLOOR_DB, sigma_db * math.sqrt(SIGMA_N0 / (SIGMA_N0 + n)))


# ---------------------------------------------------------------- rule 5: residual grid

def cell_index(deg):
    return int(math.floor(deg * 1000.0))


def residual_cells(samples, fits):
    """Rule 5. `samples`: list of dicts (network, lat, lon, sector, r, dKm); `fits`: sector -> fit_sector result.
    Each cell (network, latIdx, lonIdx) holds n and the mean of r - correction_db(sector fit, d) (the correction
    actually applied), weighted n / (n + 5) and used only where n >= 3."""
    cells = {}
    for s in samples:
        key = (s["network"], cell_index(s["lat"]), cell_index(s["lon"]))
        fit = fits.get(s["sector"])
        res = s["r"] - (correction_db(fit, s["dKm"]) if fit is not None else 0.0)
        acc = cells.setdefault(key, [0, 0.0])
        acc[0] += 1
        acc[1] += res
    out = []
    for key in sorted(cells):
        n, total = cells[key]
        mean = total / n
        applied = (n / (n + RESIDUAL_K)) * mean if n >= MIN_APPLIED else 0.0
        out.append({"network": key[0], "latIdx": key[1], "lonIdx": key[2], "n": n, "meanDb": mean, "appliedDb": applied})
    return out
