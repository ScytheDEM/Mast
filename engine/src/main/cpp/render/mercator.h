// Web-Mercator tile geometry and great-circle helpers (match tools/engine/reference.py). Degrees in, metres out.
#ifndef SIGNAL_ATLAS_RENDER_MERCATOR_H
#define SIGNAL_ATLAS_RENDER_MERCATOR_H

#include <cmath>
#include <cstdint>

namespace sa::merc {

constexpr double kPi = 3.14159265358979323846;
constexpr double kDegToRad = kPi / 180.0;
constexpr double kRadToDeg = 180.0 / kPi;
constexpr double kEarthRadiusM = 6371008.8;

inline double tileLonDeg(double xFrac, int32_t z) {
    return xFrac / static_cast<double>(int64_t{1} << z) * 360.0 - 180.0;
}

inline double tileLatDeg(double yFrac, int32_t z) {
    const double n = static_cast<double>(int64_t{1} << z);
    return std::atan(std::sinh(kPi * (1.0 - 2.0 * yFrac / n))) * kRadToDeg;
}

// Haversine distance between two points (degrees).
inline double haversineM(double lat1, double lon1, double lat2, double lon2) {
    const double p1 = lat1 * kDegToRad, p2 = lat2 * kDegToRad;
    const double dp = p2 - p1, dl = (lon2 - lon1) * kDegToRad;
    const double s1 = std::sin(dp / 2.0), s2 = std::sin(dl / 2.0);
    const double a = s1 * s1 + std::cos(p1) * std::cos(p2) * s2 * s2;
    return 2.0 * kEarthRadiusM * std::asin(std::fmin(1.0, std::sqrt(a)));
}

}  // namespace sa::merc

#endif  // SIGNAL_ATLAS_RENDER_MERCATOR_H
