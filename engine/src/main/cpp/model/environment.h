// M2 rule 5a (M3b): the receiver environment from the building grid, per point, as a blend of the four classes.
#ifndef SIGNAL_ATLAS_MODEL_ENVIRONMENT_H
#define SIGNAL_ATLAS_MODEL_ENVIRONMENT_H

#include <cmath>
#include <cstdint>

namespace sa {

constexpr uint8_t kBuildingNoData = 255;  // cover code of a cell without data
constexpr double kCoverScale = 250.0;     // cover code = round(fraction * 250)
constexpr double kHeightStepM = 0.5;      // height code step

// A mosaic of building cells (0.01 degree): cell (row, col) covers latitudes south + row * stepDeg .. + stepDeg and
// longitudes west + col * stepDeg .. + stepDeg, row 0 = south. `cover` / `height` are row-major codes (rows * cols),
// owned by the caller for the duration of a call.
struct BuildingGrid {
    double south, west;
    double stepDeg;
    int32_t rows, cols;
    const uint8_t* cover;
    const uint8_t* height;
};

// Blend weights of the four receiver classes (index = sa::Area value), summing to 1; `valid` false = no building data.
struct EnvWeights {
    bool valid;
    double w[4];
};

inline double envRamp(double x, double lo, double hi) {
    const double t = (x - lo) / (hi - lo);
    return t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
}

// Rule 5a (continuous, 2026-10-25): piecewise-linear weights on the coverage fraction with ramps of +-0.05 around the
// thresholds 0.05 (SUBURBAN), 0.2 (URBAN) and 0.35 (DENSE_URBAN), the last also ramped on mean height over 12-18 m.
inline EnvWeights environmentWeights(double fraction, double heightM) {
    const double s1 = envRamp(fraction, 0.0, 0.1);
    const double s2 = envRamp(fraction, 0.15, 0.25);
    double s3 = envRamp(fraction, 0.30, 0.40) * envRamp(heightM, 12.0, 18.0);
    if (s3 > s2) s3 = s2;
    return {true, {s3, s2 - s3, s1 - s2, 1.0 - s1}};
}

// The blend weights at (lat, lon): coverage fraction and mean height interpolated bilinearly between the centres of the
// four surrounding cells. Not valid when the grid is null, the point lies outside the cell centres, or any of the four
// cells has no data (the carrier's own class applies there).
inline EnvWeights environmentAt(const BuildingGrid* g, double lat, double lon) {
    const EnvWeights none{false, {0.0, 0.0, 0.0, 0.0}};
    if (g == nullptr || g->cover == nullptr || g->height == nullptr || g->rows < 2 || g->cols < 2 || !(g->stepDeg > 0.0)) {
        return none;
    }
    const double fr = (lat - g->south) / g->stepDeg - 0.5;
    const double fc = (lon - g->west) / g->stepDeg - 0.5;
    if (!(fr >= 0.0 && fc >= 0.0 && fr <= g->rows - 1 && fc <= g->cols - 1)) return none;
    int32_t r0 = static_cast<int32_t>(std::floor(fr));
    int32_t c0 = static_cast<int32_t>(std::floor(fc));
    if (r0 > g->rows - 2) r0 = g->rows - 2;
    if (c0 > g->cols - 2) c0 = g->cols - 2;
    const double tr = fr - r0, tc = fc - c0;
    const int64_t i00 = static_cast<int64_t>(r0) * g->cols + c0, i01 = i00 + 1, i10 = i00 + g->cols, i11 = i10 + 1;
    const uint8_t* cv = g->cover;
    if (cv[i00] == kBuildingNoData || cv[i01] == kBuildingNoData || cv[i10] == kBuildingNoData || cv[i11] == kBuildingNoData) {
        return none;
    }
    auto bil = [&](const uint8_t* a) {
        return (a[i00] * (1.0 - tc) + a[i01] * tc) * (1.0 - tr) + (a[i10] * (1.0 - tc) + a[i11] * tc) * tr;
    };
    return environmentWeights(bil(cv) / kCoverScale, bil(g->height) * kHeightStepM);
}

}  // namespace sa

#endif  // SIGNAL_ATLAS_MODEL_ENVIRONMENT_H
