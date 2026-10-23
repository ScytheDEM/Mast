// Terrain rules 1 and 6: the DEM mosaic the terrain stage reads. Pure data plus bilinear sampling.
#ifndef SIGNAL_ATLAS_TERRAIN_DEM_GRID_H
#define SIGNAL_ATLAS_TERRAIN_DEM_GRID_H

#include <cmath>
#include <cstdint>

namespace sa {

// A mosaic of elevation chunks on the 3-arcsecond node grid. Node (row, col) lies at latitude south + row * stepDeg
// and longitude west + col * stepDeg; row 0 is the south edge; `h` is row-major int16 metres (rows * cols entries).
// The caller owns `h`, which must outlive every call that uses the grid.
struct DemGrid {
    double south, west;
    double stepDeg;  // 1 / 1200
    int32_t rows, cols;
    const int16_t* h;
};

// Nodes per degree, exact when stepDeg is 1/N for an integer N (1/1200 is not representable).
inline double demNodesPerDegree(const DemGrid& g) {
    const double inv = 1.0 / g.stepDeg;
    const double rounded = std::floor(inv + 0.5);
    return std::fabs(inv - rounded) < 1e-9 ? rounded : inv;
}

inline bool demIsUsable(const DemGrid& g) {
    return g.h != nullptr && g.rows >= 2 && g.cols >= 2 && g.stepDeg > 0.0 && std::isfinite(g.stepDeg);
}

// Bilinear height (m) in lat/lon from the four surrounding nodes; false (and *out untouched) outside
// [0, rows - 1] x [0, cols - 1] in fractional node index, or for a NaN position. The last row / column use the
// cell below / left of them. `nodesPerDegree` is demNodesPerDegree(g), hoisted by loops.
inline bool demHeight(const DemGrid& g, double nodesPerDegree, double lat, double lon, double* out) {
    const double fr = (lat - g.south) * nodesPerDegree;
    const double fc = (lon - g.west) * nodesPerDegree;
    if (!(fr >= 0.0 && fr <= g.rows - 1 && fc >= 0.0 && fc <= g.cols - 1)) return false;
    int32_t r0 = static_cast<int32_t>(std::floor(fr));
    int32_t c0 = static_cast<int32_t>(std::floor(fc));
    if (r0 > g.rows - 2) r0 = g.rows - 2;
    if (c0 > g.cols - 2) c0 = g.cols - 2;
    const double tr = fr - r0, tc = fc - c0;
    const int16_t* p = g.h + static_cast<int64_t>(r0) * g.cols + c0;
    const double south = p[0] * (1.0 - tc) + p[1] * tc;
    const double north = p[g.cols] * (1.0 - tc) + p[g.cols + 1] * tc;
    *out = south * (1.0 - tr) + north * tr;
    return true;
}

inline bool demHeight(const DemGrid& g, double lat, double lon, double* out) {
    return demIsUsable(g) && demHeight(g, demNodesPerDegree(g), lat, lon, out);
}

}  // namespace sa

#endif  // SIGNAL_ATLAS_TERRAIN_DEM_GRID_H
