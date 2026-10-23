// Per-carrier state shared by the tile renderer and the point query: everything that does not depend on the sample
// point, and the exact RSRP of one carrier at a (bearing, distance) from its site.
#ifndef SIGNAL_ATLAS_RENDER_CARRIER_H
#define SIGNAL_ATLAS_RENDER_CARRIER_H

#include <cmath>
#include <cstdint>

#include "model/antenna.h"
#include "model/pathloss.h"
#include "render/calibration.h"
#include "render/tile.h"
#include "terrain/polar_table.h"

namespace sa {

struct Carrier {
    PreparedAntenna ant;
    PreparedPathLoss pl;
    PreparedCal cal;
    double eirpRe;
    double hd2;      // (h_tx - h_rx)^2, m^2
    double noiseMw;  // thermal noise for this carrier's sub-carrier spacing, mW
    double hrpMinDb; // smallest attenuation of the HRP (dB), 0 without one
    const uint8_t* terrainTable;  // polar table / mask (terrain/polar_table.h), null = quick model only
    const uint8_t* terrainMask;
    uint32_t rasterBits;  // bit r set: residual raster r of the render applies to this carrier (0 = none)
    int32_t freqKey, sectorKey, orig, group;
};

// True when carrier `i` has finite position and EIRP (else it is never evaluated).
inline bool carrierUsable(const CarrierArrays& c, int32_t i) {
    const double lat = c.lat[i], lon = c.lon[i];
    return std::isfinite(lat) && std::isfinite(lon) && std::fabs(lat) <= 90.0 && std::fabs(lon) <= 540.0 &&
           std::isfinite(c.eirpRe[i]);
}

// Prepares carrier `i` for area class `area` (rasterBits, group are left 0; the renderer sets them).
Carrier prepareCarrier(const CarrierArrays& c, const PatternTable& patterns, int32_t i, Area area);

struct CarrierEval {
    double rsrp;      // dBm, including L_terr and the calibration terms (not the residual raster)
    float sigmaDb;    // shadowing sigma after rule 6
    bool incomplete;  // L_terr was not a complete table lookup
};

// Exact RSRP of `cr` at bearing `bearingDeg` (from the site) and horizontal distance `d2M` (rules 3-8, Terrain rule 5,
// Calibration rules 4 and 6): eirpRe - A - PL - L_terr + mu + b * log10(d / d_med).
inline CarrierEval evaluateCarrier(const Carrier& cr, double bearingDeg, double d2M, bool indoor) {
    const float aDb = cr.ant.evaluate(static_cast<float>(bearingDeg), static_cast<float>(d2M));
    const float d3Km = static_cast<float>(std::sqrt(d2M * d2M + cr.hd2) / 1000.0);
    const PathLoss pl = indoor ? cr.pl.evaluateIndoor(d3Km) : cr.pl.evaluate(d3Km);
    bool inc = true;
    float lTerr = 0.0f;
    if (cr.terrainTable != nullptr) {
        lTerr = terrainLookup(cr.terrainTable, cr.terrainMask, static_cast<float>(bearingDeg), static_cast<float>(d2M), &inc);
    }
    return {cr.eirpRe - aDb - pl.plDb - lTerr + cr.cal.db(d2M), cr.cal.sigma(pl.sigmaDb), inc};
}

}  // namespace sa

#endif  // SIGNAL_ATLAS_RENDER_CARRIER_H
