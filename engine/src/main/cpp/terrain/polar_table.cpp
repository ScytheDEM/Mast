#include "terrain/polar_table.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <vector>

#include "model/limits.h"
#include "terrain/bullington.h"

namespace sa {

namespace {

// Samples at i * 90 m for every i with i * 90 < 80 km: i = 0 .. 888.
constexpr int32_t kMaxSamples = 889;
constexpr double kNuFloor = -0.78;  // J(nu) = 0 at and below

// ---------------------------------------------------------------- per-call setup shared by every radial

struct Bin {
    double r;          // range (m)
    int32_t nBelow;    // interior samples: i = 1..nBelow with i * 90 < r
    double dKm;        // r / 1000
    double fMin;       // smallest sqrt(0.002 d / (lambda d_i (d - d_i))) over d_i (at d_i = d / 2)
    double sinDelta, cosDelta;  // of r / R
    double lUcRef;     // L_uc of the straight ground line (independent of the radial, see below)
};

struct Setup {
    double hTx;                    // clamped antenna height (m)
    double lambda;
    double lon1Rad;
    double sinLat, cosLat;
    double nodesPerDegree;
    double dKm[kMaxSamples];       // d_i = i * 90 m in km
    double sinDelta[kMaxSamples], cosDelta[kMaxSamples];
    Bin bins[kTerrainBins];
};

// L_uc of the reference profile for bin `bin`: the ground line between the terminals. Adding a linear function of
// distance to every height of a profile (terminals and ground alike) leaves the Bullington L_uc unchanged (it shifts
// S_tim, S_tr and S_rim by constants that cancel in eqs. 43-47), and the reference profile is a straight line plus
// the curvature bulge, so L_uc_ref depends on the range only. It is evaluated once per bin on flat zero ground.
double referenceLuc(const Setup& s, const Bin& bin, std::vector<double>& d, std::vector<double>& h, double freqMhz) {
    const int32_t n = bin.nBelow + 2;
    if (n < 3) return 0.0;
    for (int32_t k = 0; k <= bin.nBelow; ++k) d[static_cast<size_t>(k)] = (k * kProfileStepM) / 1000.0;
    d[static_cast<size_t>(bin.nBelow + 1)] = bin.r / 1000.0;
    std::fill(h.begin(), h.begin() + n, 0.0);
    return bullingtonLuc(d.data(), h.data(), n, s.hTx, kTerrainRxHeightM, freqMhz);
}

// ---------------------------------------------------------------- per-radial state

// Prefix structures over the interior samples i = 1..count of one radial, grown as the bins' prefixes grow.
// With c = 500 C_e (per km), g_i = h_i - c d_i^2 and a bin's range d:
//   S_tim (eq. 41) = c d + max_i [ (h_i - h_ts) / d_i - c d_i ]               (a running maximum)
//   S_rim (eq. 45) = max_i (g_i - (h_rs - c d^2)) / (d - d_i) - c d            (a tangent from a point to the
//                    right of the samples onto the upper convex hull of (d_i, g_i), found by binary search)
//   line-of-sight clearance a_i = h_i - h_ts - c d_i^2 + d_i (c d - (h_rs - h_ts) / d)   (u_i + K x_i)
struct Radial {
    int32_t count = 0;     // interior samples folded in
    double ptx = -1e300;   // running maximum of (h_i - h_ts) / d_i - c d_i
    double h[kMaxSamples];
    double u[kMaxSamples];  // h_i - h_ts - c d_i^2
    double g[kMaxSamples];  // h_i - c d_i^2
    int32_t hull[kMaxSamples];
    int32_t hullSize = 0;
};

inline double slopeFrom(const Radial& rad, const double* dKm, int32_t i, double d, double rg) {
    return (rad.g[i] - rg) / (d - dKm[i]);
}

// Folds samples up to `upTo` into the prefix structures.
inline void growPrefix(Radial& rad, const double* dKm, int32_t upTo, double hTs) {
    while (rad.count < upTo) {
        const int32_t i = ++rad.count;
        const double di = dKm[i];
        const double cdd = kBulgePerKm2 * di * di;
        rad.g[i] = rad.h[i] - cdd;
        rad.u[i] = rad.h[i] - hTs - cdd;
        const double term = (rad.h[i] - hTs) / di - kBulgePerKm2 * di;
        if (term > rad.ptx) rad.ptx = term;
        // Upper hull: pop while the last two points and the new one do not turn clockwise.
        while (rad.hullSize >= 2) {
            const int32_t a = rad.hull[rad.hullSize - 2], b = rad.hull[rad.hullSize - 1];
            const double cross = (dKm[b] - dKm[a]) * (rad.g[i] - rad.g[a]) - (rad.g[b] - rad.g[a]) * (di - dKm[a]);
            if (cross >= 0.0) {
                --rad.hullSize;
            } else {
                break;
            }
        }
        rad.hull[rad.hullSize++] = i;
    }
}

// Eq. 45: S_rim for a receiver at range d (km) with height hRs, over interior samples 1..count.
inline double receiverSlope(const Radial& rad, const double* dKm, double d, double hRs) {
    const double rg = hRs - kBulgePerKm2 * d * d;
    int32_t lo = 0, hi = rad.hullSize - 1;
    while (lo < hi) {
        const int32_t mid = (lo + hi) / 2;
        if (slopeFrom(rad, dKm, rad.hull[mid + 1], d, rg) > slopeFrom(rad, dKm, rad.hull[mid], d, rg)) {
            lo = mid + 1;
        } else {
            hi = mid;
        }
    }
    return slopeFrom(rad, dKm, rad.hull[lo], d, rg) - kBulgePerKm2 * d;
}

// Eqs. 43-44 over interior samples 1..count: J(nu_max). With `prune` every a_i must be below 0 (strict line of
// sight): then nu_i = a_i F_i <= a_i F_min, so the cheap clearance pass decides most bins, and the exact pass skips
// samples that cannot exceed -0.78.
inline double losLoss(const Radial& rad, const double* dKm, const Bin& bin, double lambda, double hTs, double hRs,
                      bool prune) {
    const double d = bin.dKm;
    const double k = kBulgePerKm2 * d - (hRs - hTs) / d;
    const int32_t n = bin.nBelow;
    if (prune) {
        double m0 = -1e300, m1 = -1e300, m2 = -1e300, m3 = -1e300;
        int32_t i = 1;
        for (; i + 3 <= n; i += 4) {
            const double a0 = rad.u[i] + k * dKm[i], a1 = rad.u[i + 1] + k * dKm[i + 1];
            const double a2 = rad.u[i + 2] + k * dKm[i + 2], a3 = rad.u[i + 3] + k * dKm[i + 3];
            m0 = a0 > m0 ? a0 : m0;
            m1 = a1 > m1 ? a1 : m1;
            m2 = a2 > m2 ? a2 : m2;
            m3 = a3 > m3 ? a3 : m3;
        }
        for (; i <= n; ++i) {
            const double a = rad.u[i] + k * dKm[i];
            m0 = a > m0 ? a : m0;
        }
        const double amax = std::max(std::max(m0, m1), std::max(m2, m3));
        if (amax < 0.0 && amax * bin.fMin <= kNuFloor) return 0.0;
    }
    double best = -1e300;
    for (int32_t i = 1; i <= n; ++i) {
        const double a = rad.u[i] + k * dKm[i];
        if (prune && a < 0.0 && a * bin.fMin <= kNuFloor) continue;
        const double di = dKm[i];
        const double nu = a * std::sqrt(0.002 * d / (lambda * di * (d - di)));
        if (nu > best) best = nu;
    }
    return knifeEdgeJ(best);
}

inline uint8_t quantise(double lDb) {
    const double c = std::floor(lDb / kTerrainCodeStepDb + 0.5);
    return static_cast<uint8_t>(c >= kTerrainCodeMax ? kTerrainCodeMax : (c > 0.0 ? c : 0.0));
}

}  // namespace

int32_t buildPolarTable(const DemGrid& dem, double siteLat, double siteLon, float hTxAgl, float freqMhz,
                        uint8_t* out, uint8_t* coverageMask, const volatile int32_t* cancel) {
    if (out == nullptr || coverageMask == nullptr || !demIsUsable(dem)) return kTerrainInvalid;
    std::memset(out, 0, kPolarTableCells);
    std::memset(coverageMask, 0, kPolarMaskBytes);
    if (!std::isfinite(siteLat) || !std::isfinite(siteLon)) return 0;
    const double fMhz = sanitizeFreq(freqMhz);
    const double nodesPerDegree = demNodesPerDegree(dem);
    double ground = 0.0;
    if (!demHeight(dem, nodesPerDegree, siteLat, siteLon, &ground)) return 0;

    const std::unique_ptr<Setup> setup = std::make_unique<Setup>();  // ~50 KB: heap, not stack
    Setup& s = *setup;
    s.hTx = sanitizeHeight(hTxAgl);
    s.lambda = kLightSpeed / (fMhz * 1e6);
    const double lat1Rad = siteLat * (M_PI / 180.0);
    s.lon1Rad = siteLon * (M_PI / 180.0);
    s.sinLat = std::sin(lat1Rad);
    s.cosLat = std::cos(lat1Rad);
    s.nodesPerDegree = nodesPerDegree;
    for (int32_t i = 0; i < kMaxSamples; ++i) {
        const double x = i * kProfileStepM;
        s.dKm[i] = x / 1000.0;
        const double delta = x / kEarthRadiusM;
        s.sinDelta[i] = std::sin(delta);
        s.cosDelta[i] = std::cos(delta);
    }
    std::vector<double> scratchD(kMaxSamples + 2), scratchH(kMaxSamples + 2);
    for (int32_t j = 0; j < kTerrainBins; ++j) {
        Bin& bin = s.bins[j];
        bin.r = kTerrainMinRangeM * std::pow(kTerrainMaxRangeM / kTerrainMinRangeM, j / (kTerrainBins - 1.0));
        bin.nBelow = static_cast<int32_t>(std::ceil(bin.r / kProfileStepM)) - 1;
        bin.dKm = bin.r / 1000.0;
        bin.fMin = std::sqrt(0.002 * bin.dKm / (s.lambda * (bin.dKm / 2.0) * (bin.dKm - bin.dKm / 2.0)));
        const double delta = bin.r / kEarthRadiusM;
        bin.sinDelta = std::sin(delta);
        bin.cosDelta = std::cos(delta);
        bin.lUcRef = referenceLuc(s, bin, scratchD, scratchH, fMhz);
    }

    const double hTs = ground + s.hTx;
    const std::unique_ptr<Radial> radialState = std::make_unique<Radial>();
    Radial& rad = *radialState;
    for (int32_t b = 0; b < kTerrainRadials; ++b) {
        if (cancel != nullptr && *cancel != 0) {
            std::memset(out, 0, kPolarTableCells);
            std::memset(coverageMask, 0, kPolarMaskBytes);
            return kTerrainCancelled;
        }
        const double th = b * (M_PI / 180.0);
        const double cosTh = std::cos(th), sinTh = std::sin(th);
        // The destination point at (sin delta, cos delta): the reference's `destination` with the per-site and
        // per-radial trigonometry hoisted.
        auto sample = [&](double sinD, double cosD, double* height) {
            const double sp = s.sinLat * cosD + s.cosLat * sinD * cosTh;
            const double p2 = std::asin(sp > 1.0 ? 1.0 : (sp < -1.0 ? -1.0 : sp));
            const double l2 = s.lon1Rad + std::atan2(sinTh * sinD * s.cosLat, cosD - s.sinLat * sp);
            const double lat = p2 * (180.0 / M_PI);
            const double lon = std::fmod(l2 * (180.0 / M_PI) + 540.0, 360.0) - 180.0;
            return demHeight(dem, s.nodesPerDegree, lat, lon, height);
        };

        // The radial's shared samples: i * 90 m for i = 0.. until the DEM runs out.
        rad.h[0] = ground;
        int32_t avail = 1;
        while (avail < kMaxSamples && sample(s.sinDelta[avail], s.cosDelta[avail], &rad.h[avail])) ++avail;
        rad.count = 0;
        rad.ptx = -1e300;
        rad.hullSize = 0;

        uint8_t* row = out + static_cast<size_t>(b) * kTerrainBins;
        for (int32_t j = 0; j < kTerrainBins; ++j) {
            const Bin& bin = s.bins[j];
            if (bin.nBelow + 1 > avail) break;  // this and every longer bin leave the DEM
            double end = 0.0;
            if (!sample(bin.sinDelta, bin.cosDelta, &end)) continue;
            const int32_t k = b * kTerrainBins + j;
            coverageMask[k >> 3] = static_cast<uint8_t>(coverageMask[k >> 3] | (1u << (k & 7)));
            if (bin.nBelow < 1) continue;  // no interior sample: L = 0
            growPrefix(rad, s.dKm, bin.nBelow, hTs);
            const double d = bin.dKm;
            const double hRs = end + kTerrainRxHeightM;
            const double sTim = kBulgePerKm2 * d + rad.ptx;  // eq. 41
            const double sTr = (hRs - hTs) / d;              // eq. 42
            double lUc;
            if (sTim < sTr) {
                lUc = losLoss(rad, s.dKm, bin, s.lambda, hTs, hRs, true);  // eqs. 43-44
            } else {
                const double sRim = receiverSlope(rad, s.dKm, d, hRs);   // eq. 45
                if (sTim + sRim <= 0.0) {
                    lUc = losLoss(rad, s.dKm, bin, s.lambda, hTs, hRs, false);  // grazing guard
                } else {
                    const double dB = (hRs - hTs + sRim * d) / (sTim + sRim);  // eq. 46
                    const double nuB = (hTs + sTim * dB - (hTs * (d - dB) + hRs * dB) / d) *
                                       std::sqrt(0.002 * d / (s.lambda * dB * (d - dB)));  // eq. 47
                    lUc = knifeEdgeJ(nuB);                                                 // eq. 48
                }
            }
            const double excess = lUc - bin.lUcRef;
            row[j] = excess > 0.0 ? quantise(excess) : 0;
        }
    }
    return 0;
}

float terrainLookup(const uint8_t* table, const uint8_t* mask, float bearingDeg, float distM, bool* incomplete) {
    bool inc = false;
    double value = 0.0;
    const double r = distM;
    if (table == nullptr || mask == nullptr || std::isnan(r) || !std::isfinite(static_cast<double>(bearingDeg))) {
        inc = true;
    } else if (r < kTerrainMinRangeM) {
        // below the first bin: no terrain loss
    } else if (r > kTerrainMaxRangeM) {
        inc = true;
    } else {
        const double u = std::log(r / kTerrainMinRangeM) / std::log(kTerrainMaxRangeM / kTerrainMinRangeM) *
                         (kTerrainBins - 1);
        const int32_t j0 = std::min(static_cast<int32_t>(std::floor(u)), kTerrainBins - 1);
        const int32_t j1 = std::min(j0 + 1, kTerrainBins - 1);
        const double tj = u - j0;
        double bearing = std::fmod(static_cast<double>(bearingDeg), 360.0);
        if (bearing < 0.0) bearing += 360.0;
        const double bf = std::floor(bearing);
        const int32_t b0 = static_cast<int32_t>(bf) % kTerrainRadials;
        const int32_t b1 = (b0 + 1) % kTerrainRadials;
        const double tb = bearing - bf;
        const int32_t cells[4] = {b0 * kTerrainBins + j0, b0 * kTerrainBins + j1, b1 * kTerrainBins + j0,
                                  b1 * kTerrainBins + j1};
        bool complete = true;
        for (int32_t cell : cells) complete = complete && ((mask[cell >> 3] >> (cell & 7)) & 1) != 0;
        if (complete) {
            const double v00 = table[cells[0]] * kTerrainCodeStepDb, v01 = table[cells[1]] * kTerrainCodeStepDb;
            const double v10 = table[cells[2]] * kTerrainCodeStepDb, v11 = table[cells[3]] * kTerrainCodeStepDb;
            value = (v00 * (1.0 - tj) + v01 * tj) * (1.0 - tb) + (v10 * (1.0 - tj) + v11 * tj) * tb;
        } else {
            inc = true;
        }
    }
    if (incomplete != nullptr) *incomplete = inc;
    return static_cast<float>(value);
}

}  // namespace sa
