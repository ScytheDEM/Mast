// Constants and input sanitisers shared by the model files.
#ifndef SIGNAL_ATLAS_MODEL_LIMITS_H
#define SIGNAL_ATLAS_MODEL_LIMITS_H

#include <cstddef>

#include "model/stats.h"

namespace sa {

constexpr double kHRx = 1.5;  // UE height (m)

// Frequency: NaN -> 1800 MHz, otherwise clamped to [30, 100000].
inline double sanitizeFreq(double f) { return sane(f, 30.0, 100000.0, 1800.0); }
// Transmitter height: NaN -> 3 m, clamped to [3, 1000] (rule 1).
inline double sanitizeHeight(double h) { return sane(h, 3.0, 1000.0, 3.0); }

}  // namespace sa

#endif  // SIGNAL_ATLAS_MODEL_LIMITS_H
