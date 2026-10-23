// Point prediction (M4): every carrier's RSRP at one location, with the same model as renderTile.
#ifndef SIGNAL_ATLAS_RENDER_POINT_H
#define SIGNAL_ATLAS_RENDER_POINT_H

#include <cstdint>

#include "model/pathloss.h"
#include "render/tile.h"

namespace sa {

// Writes `outRsrp[i]` (c.n entries) = the RSRP (dBm) of carrier i at (lat, lon) for area class `area`: antenna, path
// loss (indoor adds O2I), the terrain lookup when the carrier has a table, and the calibration terms, exactly as
// renderTile evaluates a grid sample (no residual raster, no culling: carriers beyond R_max are still predicted).
// Carriers with a non-finite position or EIRP get NaN. Returns c.n, or kRenderInvalid for a bad n, a missing array,
// a null `outRsrp` (when n > 0) or a non-finite point.
int32_t predictPoint(const CarrierArrays& c, const PatternTable& patterns, double lat, double lon, Area area,
                     bool indoor, float* outRsrp);

}  // namespace sa

#endif  // SIGNAL_ATLAS_RENDER_POINT_H
