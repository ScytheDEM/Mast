// Rules 6, 7 and 9: path loss, O2I loss and culling range.
#ifndef SIGNAL_ATLAS_MODEL_PATHLOSS_H
#define SIGNAL_ATLAS_MODEL_PATHLOSS_H

#include <cstdint>

namespace sa {

enum class Area : uint8_t { DenseUrban = 0, Urban = 1, Suburban = 2, Rural = 3 };

struct PathLoss {
    float plDb;
    float sigmaDb;  // blended shadowing sigma (outdoor)
};

// `dKm3d` is the 3D distance (floored at 0.02 km); `hTxM` is clamped to >= 3 m. Values of Area above Rural are Rural.
PathLoss pathLoss(float freqMhz, float dKm3d, float hTxM, Area area);

// Rule 7: O2I low-loss model including the 2.5 dB term.
float o2iLowLossDb(float freqMhz);

// Rule 9: 3D distance (km, <= 80) at which eirpRe - PL (A = 0, outdoor, one area class) falls to -140 dBm.
float maxRangeKm(float eirpReDbm, float freqMhz, float hTxM, Area area);

// Rule 9 R_max: the maximum of maxRangeKm over the four area classes.
float cullingRangeKm(float eirpReDbm, float freqMhz, float hTxM);

}  // namespace sa

#endif  // SIGNAL_ATLAS_MODEL_PATHLOSS_H
