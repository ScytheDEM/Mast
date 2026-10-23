// Terrain rule 6: the per-(site, frequency, height) polar table of terrain excess loss and its renderer lookup.
#ifndef SIGNAL_ATLAS_TERRAIN_POLAR_TABLE_H
#define SIGNAL_ATLAS_TERRAIN_POLAR_TABLE_H

#include <cstdint>

#include "terrain/dem_grid.h"
#include "terrain/profile.h"

namespace sa {

constexpr int32_t kPolarTableCells = kTerrainRadials * kTerrainBins;  // 92160
constexpr int32_t kPolarMaskBytes = kPolarTableCells / 8;             // 11520

constexpr int32_t kTerrainCancelled = -1;
constexpr int32_t kTerrainInvalid = -2;  // bad arguments (outputs untouched)

// Builds the table for one site: `out[b * 256 + j]` is L_terr quantised to uint8 at 0.25 dB (cap 63.75 dB) on the
// radial at bearing b degrees true, at range r_j = 50 m * 1600^(j / 255). `coverageMask` (kPolarMaskBytes) has one
// bit per cell (byte k >> 3, bit k & 7, LSB first), set when every profile sample to that cell, endpoint included,
// had DEM; incomplete cells hold 0. A site without DEM gives an all-incomplete table.
//
// `freqMhz` is the table-key frequency (lambda uses it) and `hTxAgl` the key height (clamped to >= 3 m, as the
// reference does). Each radial is sampled once at 90 m steps (to 80 km or the grid edge) and each bin evaluates a
// prefix of it. `cancel` (may be null) is polled once per radial: when non-zero both outputs are zeroed and
// kTerrainCancelled is returned. Returns 0 on success, kTerrainInvalid for a null output or an unusable grid.
// Reentrant: all scratch is per call.
int32_t buildPolarTable(const DemGrid& dem, double siteLat, double siteLon, float hTxAgl, float freqMhz,
                        uint8_t* out, uint8_t* coverageMask, const volatile int32_t* cancel);

// Renderer lookup: L_terr (dB), bilinear in bearing (wrapped) and ln r over the four surrounding cells.
// r < 50 m gives 0 (complete). r beyond 80 km, or any of the four cells masked, gives 0 and sets *incomplete
// (nullable) to true; otherwise *incomplete is set to false. NaN inputs count as incomplete.
float terrainLookup(const uint8_t* table, const uint8_t* mask, float bearingDeg, float distM, bool* incomplete);

}  // namespace sa

#endif  // SIGNAL_ATLAS_TERRAIN_POLAR_TABLE_H
