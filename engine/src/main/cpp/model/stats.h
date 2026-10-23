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
