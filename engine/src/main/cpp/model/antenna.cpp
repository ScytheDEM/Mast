#include "model/antenna.h"

#include <cmath>

#include "model/limits.h"

namespace sa {
namespace {

constexpr double kDefaultHBw = 65.0;
constexpr double kDefaultFb = 25.0;
constexpr double kDefaultGain = 15.0;

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

PreparedAntenna prepareAntenna(const AntennaParams& p) {
    const double hTx = sanitizeHeight(p.hTxM);
    const double f = sanitizeFreq(p.freqMhz);
    PreparedAntenna a;
    a.hrp = p.hrp;
    a.hrpPeak = p.hrpPeakBearing;
    a.omni = !std::isfinite(p.azimuthDeg);
    a.azimuth = a.omni ? 0.0 : static_cast<double>(p.azimuthDeg);
    a.hbw = sane(p.hBwDeg, 1.0, 360.0, kDefaultHBw);
    a.fb = sane(p.fbDb, 0.0, 100.0, kDefaultFb);
    const double gain = sane(p.gainDbi, -10.0, 60.0, kDefaultGain);
    const double tilt = sane(p.tiltDeg, -1000.0, 1000.0, 0.0);
    const double phi3 = a.omni ? 360.0 : a.hbw;
    const double t = 32400.0 / (std::pow(10.0, gain / 10.0) * phi3);
    a.theta3 = t < 4.0 ? 4.0 : (t > 30.0 ? 30.0 : t);
    a.tilt = effectiveTilt(tilt, hTx, f);
    a.heightDiff = hTx - kHRx;
    a.maxA = std::fmax(a.fb, 30.0);
    return a;
}

float antennaAttenuation(const AntennaParams& p, float bearingDeg, float distM) {
    return prepareAntenna(p).evaluate(bearingDeg, distM);
}

}  // namespace sa
