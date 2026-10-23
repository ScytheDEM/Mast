// Rule 2: EIRP per resource element.
#ifndef SIGNAL_ATLAS_MODEL_EIRP_H
#define SIGNAL_ATLAS_MODEL_EIRP_H

#include <cstdint>

namespace sa {

enum class Tech : uint8_t { LTE = 0, NR = 1 };

// eirpRe = eirp - 10 log10(12 N_RB). Any Tech value other than NR is treated as LTE.
float eirpPerRe(float eirpDbm, Tech tech, float freqMhz, int32_t bandwidthHz);

// Sub-carrier spacing (kHz) and resource-block count used by eirpPerRe (shared with the tile renderer).
int32_t scsKhz(Tech tech, float freqMhz);
int32_t numResourceBlocks(Tech tech, float freqMhz, int32_t bandwidthHz);

}  // namespace sa

#endif  // SIGNAL_ATLAS_MODEL_EIRP_H
