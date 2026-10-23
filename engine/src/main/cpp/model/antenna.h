// Rules 3-4: horizontal (parametric or HRP) and vertical antenna attenuation.
#ifndef SIGNAL_ATLAS_MODEL_ANTENNA_H
#define SIGNAL_ATLAS_MODEL_ANTENNA_H

#include <cmath>
#include <cstdint>

#include "model/limits.h"

namespace sa {

struct AntennaParams {
    float azimuthDeg;  // NaN = omni
    float hBwDeg;      // NaN = default 65
    float fbDb;        // NaN = default 25
    float gainDbi;     // NaN = default 15
    float tiltDeg;     // ACMA tilt; 0 or NaN = by height
    float hTxM;        // clamped to >= 3 m
    float freqMhz;
    // Null, or a pointer to EXACTLY 360 bytes (relative attenuation, 0.25 dB steps). The engine cannot check the
    // length: loaders must validate the size before building params.
    const uint8_t* hrp;
    int16_t hrpPeakBearing;
};

// Per-carrier antenna state: everything that does not depend on the sample point is computed once. The HRP pointer
// is borrowed and must outlive this object. Math is double, as in the reference.
struct PreparedAntenna {
    const uint8_t* hrp;
    int64_t hrpPeak;
    bool omni;
    double azimuth;
    double hbw;
    double fb;
    double theta3;     // vertical 3 dB beamwidth
    double tilt;       // effective downtilt
    double heightDiff; // h_tx - 1.5
    double maxA;       // max(FB, 30)

    // Combined attenuation A (dB, >= 0) at `bearingDeg` (from the site) and horizontal distance `d2dM`.
    float evaluate(float bearingDeg, float d2dM) const {
        const double bearing = sane(bearingDeg, -1.0e9, 1.0e9, 0.0);
        const double d = sane(d2dM, 0.0, 1.0e9, 0.0);
        double aH;
        if (hrp != nullptr) {
            const int64_t rounded = static_cast<int64_t>(std::floor(bearing + 0.5));
            int64_t idx = (rounded - hrpPeak) % 360;
            if (idx < 0) idx += 360;
            aH = hrp[idx] * 0.25;
        } else if (omni) {
            aH = 0.0;
        } else {
            double dphi = std::fmod(bearing - azimuth + 180.0, 360.0);
            if (dphi < 0.0) dphi += 360.0;
            dphi -= 180.0;
            const double q = dphi / hbw;
            aH = std::fmin(12.0 * q * q, fb);
        }
        const double elev = std::atan2(heightDiff, d) * (180.0 / 3.14159265358979323846);
        const double x = (elev - tilt) / theta3;
        const double aV = std::fmin(12.0 * x * x, 30.0);
        return static_cast<float>(std::fmin(aH + aV, maxA));
    }
};

PreparedAntenna prepareAntenna(const AntennaParams& p);

// Thin wrapper: prepareAntenna(p).evaluate(bearingDeg, distM).
float antennaAttenuation(const AntennaParams& p, float bearingDeg, float distM);

}  // namespace sa

#endif  // SIGNAL_ATLAS_MODEL_ANTENNA_H
