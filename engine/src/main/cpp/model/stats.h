// Numeric helpers shared by the engine models, and the coverage probability (rule 8).
// Engine never throws (Ruling AA): non-finite or out-of-range inputs are clamped to valid ranges, so results are
// always finite. Math runs in double internally (see each .cpp for why); public interfaces stay float.
#ifndef SIGNAL_ATLAS_MODEL_STATS_H
#define SIGNAL_ATLAS_MODEL_STATS_H

#include <cmath>

namespace sa {

// NaN -> `fallback`; otherwise clamp to [lo, hi] (so +-inf become the bounds).
inline double sane(double x, double lo, double hi, double fallback) {
    if (std::isnan(x)) return fallback;
    return x < lo ? lo : (x > hi ? hi : x);
}

// Rule 7 (Ruling EE): outdoor and O2I shadowing combine in quadrature.
constexpr double kO2iSigmaDb = 4.4;

inline float indoorSigmaDb(float sigmaDb) {
    const double s = sane(sigmaDb, 0.0, 100.0, 8.0);
    return static_cast<float>(std::sqrt(s * s + kO2iSigmaDb * kO2iSigmaDb));
}

// Rule 8 (amended 2026-11-02, reliability): the prediction's own error adds to the shadowing in quadrature, 6 dB
// where the best server's terrain loss came from a complete table and 9 dB where it did not (quick model), growing
// by 0.2 dB per km past 10 km up to 4 dB more. Calibration (rule 6) then scales the total.
constexpr double kModelSigmaTerrainDb = 6.0;
constexpr double kModelSigmaQuickDb = 9.0;
constexpr double kModelSigmaGrowStartKm = 10.0;
constexpr double kModelSigmaGrowDbPerKm = 0.2;
constexpr double kModelSigmaGrowMaxDb = 4.0;

inline double totalSigmaDb(double shadowSigmaDb, bool terrainComplete, double d2Km) {
    const double s = sane(shadowSigmaDb, 0.0, 100.0, 8.0);
    const double grow = std::fmin(kModelSigmaGrowMaxDb, kModelSigmaGrowDbPerKm * std::fmax(0.0, sane(d2Km, 0.0, 1.0e4, 80.0) - kModelSigmaGrowStartKm));
    const double m = (terrainComplete ? kModelSigmaTerrainDb : kModelSigmaQuickDb) + grow;
    return std::sqrt(s * s + m * m);
}

// Rule 8: usable service also needs SINR >= -3 dB; its prediction error is taken as 3 dB.
constexpr double kSinrMinDb = -3.0;
constexpr double kSinrSigmaDb = 3.0;

// Rule 8: P(RSRP + shadowing >= thr) for shadowing ~ N(0, sigma). Result in [0, 1].
inline float coverageProbability(float rsrpDbm, float thrDbm, float sigmaDb) {
    const double rsrp = sane(rsrpDbm, -1000.0, 1000.0, -200.0);
    const double thr = sane(thrDbm, -1000.0, 1000.0, -140.0);
    const double sigma = sane(sigmaDb, 0.01, 100.0, 8.0);
    const double p = 0.5 * std::erfc((thr - rsrp) / (sigma * std::sqrt(2.0)));
    return static_cast<float>(sane(p, 0.0, 1.0, 0.0));
}

}  // namespace sa

#endif  // SIGNAL_ATLAS_MODEL_STATS_H
