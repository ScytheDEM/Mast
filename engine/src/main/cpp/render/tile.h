// Task 6: best-server tile renderer (rules 8-9). Pure computation over caller-supplied flat arrays.
#ifndef SIGNAL_ATLAS_RENDER_TILE_H
#define SIGNAL_ATLAS_RENDER_TILE_H

#include <cstdint>

#include "model/pathloss.h"

namespace sa {

// Structure of arrays, `n` entries each (every pointer must be non-null when n > 0, except hrpId/hrpPeak, which
// may be null = no HRP). NaN in az/hBw/fb/gain/tilt means "not supplied" (see model/antenna.h). `eirpRe` is
// precomputed by the caller (eirpPerRe) and `rMaxKm` is cullingRangeKm. `tech`: 0 = LTE, 1 = NR.
struct CarrierArrays {
    int32_t n;
    const double *lat, *lon;
    const float *hTx, *az, *tilt, *eirpRe, *freqMhz, *hBw, *fb, *gain, *rMaxKm;
    const uint8_t* tech;
    const int32_t* sectorKey;
    const int32_t* freqKey;  // network + frequency (0.1 MHz): carriers sharing it and not the sectorKey interfere
    const int32_t* hrpId;    // 1-based into PatternTable, 0 = none; out-of-range = none
    const int16_t* hrpPeak;
    // Terrain (Terrain rules 5-6): per carrier, the polar table (kPolarTableCells bytes) and its coverage mask
    // (kPolarMaskBytes) built by buildPolarTable; carriers of one site/frequency/height key share a pointer. Either
    // array may be null (= no terrain for any carrier) and so may each entry (= quick model only for that carrier;
    // its pixels count as terrain-incomplete). A non-null table with a null mask is treated as no terrain data.
    // The tables must stay valid for the duration of the call. L_terr is subtracted from that carrier's RSRP before
    // the best server, SINR and probability are derived, exactly like the reference.
    const uint8_t* const* terrainTable = nullptr;
    const uint8_t* const* terrainMask = nullptr;
};

struct PatternTable {
    int32_t count;
    const uint8_t* att;  // count * 360 bytes; id k occupies [(k - 1) * 360, k * 360)
};

struct RenderParams {
    int32_t z, x, y;
    int32_t grid;  // 128 (bilinear upsample to 256) or 256
    Area area;     // fixed per tile (derived by the caller, rule 5)
    bool indoor;
    float thrDbm;
    bool debugNoBounds = false;  // tests only: evaluate every carrier everywhere (no pruning)
};

// Each buffer is 256 * 256, row-major, row 0 = north edge. `best` is the index into CarrierArrays of the serving
// carrier, or kNoBest where RSRP code = 0 (may be null to skip). On a nullptr buffer, that output is skipped.
struct TileOut {
    uint8_t* rsrp;
    uint8_t* sinr;
    uint8_t* prob;
    uint16_t* best;
    // Terrain statistics (either may be null): `terrainCoveredPx` = pixels with RSRP code != 0; `terrainIncompletePx`
    // = those among them whose best server's L_terr was not a complete table lookup (no table, masked cell, or
    // range beyond the last bin). The caller labels the tile "Terrain" when covered > 0 and
    // covered - incomplete >= 95% of covered. Both are set to 0 when the outputs are zeroed (cancel, no carrier).
    int32_t* terrainCoveredPx = nullptr;
    int32_t* terrainIncompletePx = nullptr;
};

constexpr uint16_t kNoBest = 0xFFFF;
constexpr int32_t kTilePixels = 256 * 256;
constexpr int32_t kRenderCancelled = -1;
constexpr int32_t kRenderInvalid = -2;  // bad arguments (outputs untouched)

// Returns the number of carriers evaluated (those that pass culling and have finite inputs), kRenderCancelled when
// `cancelFlag` (polled once per grid row) became non-zero (outputs are then zeroed / kNoBest), or kRenderInvalid
// (bad grid, z, n or a missing array; n must be <= 65535). With no evaluated carrier the output is all zero.
int32_t renderTile(const CarrierArrays& c, const PatternTable& patterns, const RenderParams& p, TileOut& out,
                   const volatile int32_t* cancelFlag);

}  // namespace sa

#endif  // SIGNAL_ATLAS_RENDER_TILE_H
