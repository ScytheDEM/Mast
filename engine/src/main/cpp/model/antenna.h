// Rules 3-4: horizontal (parametric or HRP) and vertical antenna attenuation.
#ifndef SIGNAL_ATLAS_MODEL_ANTENNA_H
#define SIGNAL_ATLAS_MODEL_ANTENNA_H

#include <cstdint>

namespace sa {

struct AntennaParams {
    float azimuthDeg;  // NaN = omni
    float hBwDeg;      // NaN = default 65
    float fbDb;        // NaN = default 25
    float gainDbi;     // NaN = default 15
    float tiltDeg;     // ACMA tilt; 0 or NaN = by height
    float hTxM;        // clamped to >= 3 m
    float freqMhz;
    const uint8_t* hrp;  // null, or 360 relative attenuations in 0.25 dB steps
    int16_t hrpPeakBearing;
};

// Combined attenuation A (dB, >= 0) at `bearingDeg` (from the site) and horizontal distance `distM`.
float antennaAttenuation(const AntennaParams& p, float bearingDeg, float distM);

}  // namespace sa

#endif  // SIGNAL_ATLAS_MODEL_ANTENNA_H
