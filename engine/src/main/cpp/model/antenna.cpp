#include "model/antenna.h"

#include <cmath>

#include "model/stats.h"

// Double throughout: the vertical term is quadratic in an angle difference of a few degrees, and the reference is
// double; float would risk drifting from the 0.01 dB tolerance at the clamp knees.
namespace sa {
namespace {

constexpr double kHRx = 1.5;
constexpr double kDefaultHBw = 65.0;
constexpr double kDefaultFb = 25.0;
constexpr double kDefaultGain = 15.0;
constexpr double kRadToDeg = 180.0 / 3.14159265358979323846;

double wrap180(double deg) {
    double m = std::fmod(deg + 180.0, 360.0);
    if (m < 0.0) m += 360.0;
    return m - 180.0;
}

double effectiveTilt(double tilt, double hTx, double fMhz) {
    if (tilt != 0.0) return std::fmin(std::fabs(tilt), 15.0);
    double t;
    if (hTx < 15.0) {
        t = 6.0;
    } else if (hTx < 30.0) {
        t = 4.0;
    } else if (hTx <= 50.0) {
        t = 3.0;
    } else {
        t = 2.0;
    }
    return fMhz >= 24250.0 ? t + 2.0 : t;
}

}  // namespace

float antennaAttenuation(const AntennaParams& p, float bearingDeg, float distM) {
    const double bearing = sane(bearingDeg, -1.0e9, 1.0e9, 0.0);
    const double d = sane(distM, 0.0, 1.0e9, 0.0);
    const double hTx = sane(p.hTxM, 3.0, 1000.0, 3.0);
    const double f = sane(p.freqMhz, 30.0, 100000.0, 1800.0);
    const bool omni = !std::isfinite(p.azimuthDeg);
    const double hbw = sane(p.hBwDeg, 1.0, 360.0, kDefaultHBw);
    const double fb = sane(p.fbDb, 0.0, 100.0, kDefaultFb);
    const double gain = sane(p.gainDbi, -10.0, 60.0, kDefaultGain);
    const double tilt = sane(p.tiltDeg, -1000.0, 1000.0, 0.0);

    // Rule 3.
    double aH;
    if (p.hrp != nullptr) {
        const int64_t rounded = static_cast<int64_t>(std::floor(bearing + 0.5));
        int64_t idx = (rounded - static_cast<int64_t>(p.hrpPeakBearing)) % 360;
        if (idx < 0) idx += 360;
        aH = p.hrp[idx] * 0.25;
    } else if (omni) {
        aH = 0.0;
    } else {
        const double dphi = wrap180(bearing - static_cast<double>(p.azimuthDeg));
        aH = std::fmin(12.0 * (dphi / hbw) * (dphi / hbw), fb);
    }

    // Rule 4.
    const double phi3 = omni ? 360.0 : hbw;
    double theta3 = 32400.0 / (std::pow(10.0, gain / 10.0) * phi3);
    theta3 = theta3 < 4.0 ? 4.0 : (theta3 > 30.0 ? 30.0 : theta3);
    const double elev = std::atan2(hTx - kHRx, d) * kRadToDeg;
    const double x = (elev - effectiveTilt(tilt, hTx, f)) / theta3;
    const double aV = std::fmin(12.0 * x * x, 30.0);
    return static_cast<float>(std::fmin(aH + aV, std::fmax(fb, 30.0)));
}

}  // namespace sa
