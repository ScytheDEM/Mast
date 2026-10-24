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
    // Calibration rules 4 and 6 (render/calibration.h), per carrier; each array may be null (= the default for every
    // carrier) and each value NaN: offset mu (dB, default 0), slope b (dB per decade of d / d_median, default 0),
    // d_median (km; not > 0 = no slope term) and the sigma scale (default 1; < 1 gives max(4, sigma * scale)).
    // RSRP gains mu + b * log10(clamp(d / d_median, 0.1, 10)) at horizontal distance d.
    const float* calOffsetDb = nullptr;
    const float* calSlope = nullptr;
    const float* calDmedKm = nullptr;
    const float* calSigmaScale = nullptr;
    // Network ordinal per carrier (< 32), selecting the residual rasters that apply (RenderParams::residuals). Null =
    // no raster applies to any carrier.
    const uint8_t* network = nullptr;
    // Rule 5 (amended 2026-10-24): the area class of each carrier (an sa::Area value), derived once per carrier from
    // the site density around its site, so every tile uses the same path-loss model for a given carrier. Null, or a
    // value > 3, = RenderParams::area (the fallback).
    const uint8_t* area = nullptr;
};

// Calibration rule 5: a per-tile residual raster, 256 x 256 int8 in 0.25 dB steps (row-major, row 0 = north edge),
// added to the RSRP of every carrier whose network bit (1 << network) is set in `networkMask`. A grid sample at
// (i, j) of an N x N grid reads pixel (floor((i + 0.5) * 256 / N), floor((j + 0.5) * 256 / N)).
struct ResidualRaster {
    const int8_t* q;
    uint32_t networkMask;
};

constexpr int32_t kMaxResidualRasters = 32;

struct PatternTable {
    int32_t count;
    const uint8_t* att;  // count * 360 bytes; id k occupies [(k - 1) * 360, k * 360)
};

struct RenderParams {
    int32_t z, x, y;
    int32_t grid;  // 128 (bilinear upsample to 256) or 256
    Area area;     // fallback for carriers without their own class (CarrierArrays::area, rule 5)
    bool indoor;
    float thrDbm;
    bool debugNoBounds = false;  // tests only: evaluate every carrier everywhere (no pruning)
    // Residual rasters (Calibration rule 5), `residualCount` <= kMaxResidualRasters, each `q` non-null; borrowed for
    // the call. Rasters whose masks overlap add up.
    const ResidualRaster* residuals = nullptr;
    int32_t residualCount = 0;
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
// (bad grid, z, n, residual rasters or a missing array; n must be <= 65535). With no evaluated carrier the output is all zero.
int32_t renderTile(const CarrierArrays& c, const PatternTable& patterns, const RenderParams& p, TileOut& out,
                   const volatile int32_t* cancelFlag);

}  // namespace sa

#endif  // SIGNAL_ATLAS_RENDER_TILE_H
