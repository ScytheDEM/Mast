// Terrain rule 1 and 6 constants and great-circle geometry shared by the terrain stage.
#ifndef SIGNAL_ATLAS_TERRAIN_PROFILE_H
#define SIGNAL_ATLAS_TERRAIN_PROFILE_H

#include <cmath>
#include <cstdint>

namespace sa {

constexpr double kEarthRadiusM = 6371008.8;  // sphere for great-circle geometry (same as the Python reference)
constexpr double kProfileStepM = 90.0;       // rule 1: profile sample spacing
constexpr double kTerrainRxHeightM = 1.5;    // rule 1: receiver height above ground
constexpr double kTerrainMinTxHeightM = 3.0; // M2 rule 1 clamp, applied by buildPolarTable
constexpr int32_t kTerrainRadials = 360;     // rule 6
constexpr int32_t kTerrainBins = 256;
constexpr double kTerrainMinRangeM = 50.0;
constexpr double kTerrainMaxRangeM = 80000.0;
constexpr double kTerrainCodeStepDb = 0.25;
constexpr int32_t kTerrainCodeMax = 255;     // 63.75 dB

struct LatLon {
    double lat, lon;  // degrees
};

// Point at `distM` along the great circle leaving (lat, lon) at `bearingDeg` (true, clockwise from north).
// Same operations as the Python reference's `destination` so the sample points agree to the last bit.
inline LatLon destinationPoint(double lat, double lon, double bearingDeg, double distM) {
    const double p1 = lat * (M_PI / 180.0), l1 = lon * (M_PI / 180.0), th = bearingDeg * (M_PI / 180.0);
    const double delta = distM / kEarthRadiusM;
    const double sp = std::sin(p1) * std::cos(delta) + std::cos(p1) * std::sin(delta) * std::cos(th);
    const double p2 = std::asin(sp > 1.0 ? 1.0 : (sp < -1.0 ? -1.0 : sp));
    const double l2 = l1 + std::atan2(std::sin(th) * std::sin(delta) * std::cos(p1),
                                      std::cos(delta) - std::sin(p1) * sp);
    return {p2 * (180.0 / M_PI), std::fmod(l2 * (180.0 / M_PI) + 540.0, 360.0) - 180.0};
}

}  // namespace sa

#endif  // SIGNAL_ATLAS_TERRAIN_PROFILE_H
