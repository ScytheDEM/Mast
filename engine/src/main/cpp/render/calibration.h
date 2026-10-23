// Calibration rules 4 and 6 as the renderer applies them (match tools/engine/reference.py `calibration_db`,
// `calibrated_sigma`): a per-carrier offset and distance slope, and a shadowing-sigma scale.
#ifndef SIGNAL_ATLAS_RENDER_CALIBRATION_H
#define SIGNAL_ATLAS_RENDER_CALIBRATION_H

#include <cmath>

namespace sa {

// The slope term uses log10(d / d_median) with the ratio clamped to [0.1, 10], so it never extrapolates more than
// one decade from the sector's median sample distance.
constexpr double kCalRatioMin = 0.1;
constexpr double kCalRatioMax = 10.0;
// Rule 6: a calibrated sector's sigma never drops below 4 dB.
constexpr double kCalSigmaFloorDb = 4.0;

struct PreparedCal {
    double offsetDb = 0.0;  // mu (0 when the input is not finite)
    double slope = 0.0;     // b, dB per decade (0 without a usable median distance)
    double dMedM = 1.0;     // d_median in metres (> 0)
    double sigmaScale = 1.0;  // < 1 shrinks sigma (rule 6); 1 = unchanged

    // offset + b * log10(clamp(d / d_med, 0.1, 10)) for horizontal distance `d2M` (metres).
    double db(double d2M) const {
        if (slope == 0.0) return offsetDb;
        double ratio = d2M / dMedM;
        if (std::isnan(ratio)) ratio = 1.0;  // NaN distance: no slope term
        ratio = ratio < kCalRatioMin ? kCalRatioMin : (ratio > kCalRatioMax ? kCalRatioMax : ratio);
        return offsetDb + slope * std::log10(ratio);
    }

    // An upper bound of db(d) over d in [dLoM, dHiM]: the term is monotonic in d, so its endpoints suffice.
    double upperBound(double dLoM, double dHiM) const {
        if (slope == 0.0) return offsetDb;
        return std::fmax(db(dLoM), db(dHiM));
    }

    // Rule 6: max(4, sigma * scale) for a calibrated carrier (scale < 1), else sigma unchanged.
    float sigma(float sigmaDb) const {
        if (!(sigmaScale < 1.0)) return sigmaDb;
        return static_cast<float>(std::fmax(kCalSigmaFloorDb, static_cast<double>(sigmaDb) * sigmaScale));
    }
};

// Sanitises the four per-carrier inputs (any may be NaN): offset NaN -> 0; slope NaN, or d_median not > 0 / not
// finite -> no slope term; scale NaN or >= 1 -> 1, negative -> 0 (then the 4 dB floor applies).
inline PreparedCal prepareCal(float offsetDb, float slope, float dMedKm, float sigmaScale) {
    PreparedCal c;
    if (std::isfinite(offsetDb)) c.offsetDb = offsetDb;
    if (std::isfinite(slope) && std::isfinite(dMedKm) && dMedKm > 0.0f) {
        c.slope = slope;
        c.dMedM = static_cast<double>(dMedKm) * 1000.0;
    }
    if (std::isfinite(sigmaScale) && sigmaScale < 1.0f) c.sigmaScale = sigmaScale < 0.0f ? 0.0 : sigmaScale;
    return c;
}

}  // namespace sa

#endif  // SIGNAL_ATLAS_RENDER_CALIBRATION_H
