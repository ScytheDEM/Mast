#include "model/pathloss.h"

#include <cmath>
#include <initializer_list>

#include "model/stats.h"

// Double throughout: path loss is ~100-200 dB, where float's 24-bit mantissa (about 1.5e-5 dB ulp at 160 dB) is fine
// for one evaluation, but the TR 38.901 break-point terms and the 60-step range bisection compound rounding, so the
// computation stays in double and only the results are narrowed to float.
namespace sa {
namespace {

constexpr double kHRx = 1.5;
constexpr double kCLight = 3.0e8;
constexpr double kHE = 1.0;
constexpr double kPi = 3.14159265358979323846;
constexpr double kMinKm = 0.02;
constexpr double kCapKm = 80.0;
constexpr double kFloorDbm = -140.0;

struct Pl {
    double pl;
    double sigma;
};

double clampd(double x, double lo, double hi) { return x < lo ? lo : (x > hi ? hi : x); }

void hataTerms(double f, Area area, double* ahm, double* corr) {
    const double logf = std::log10(f);
    if (area == Area::DenseUrban) {
        const double l = std::log10(11.75 * kHRx);
        *ahm = 3.2 * l * l - 4.97;
    } else {
        *ahm = (1.1 * logf - 0.7) * kHRx - (1.56 * logf - 0.8);
    }
    if (area == Area::Suburban) {
        const double l = std::log10(f / 28.0);
        *corr = -2.0 * l * l - 5.4;
    } else if (area == Area::Rural) {
        *corr = -4.78 * logf * logf + 18.33 * logf - 35.94;
    } else {
        *corr = 0.0;
    }
}

double hata(double f, double d, double h, Area area) {
    const double hb = clampd(h, 3.0, 200.0);
    double ahm, corr;
    hataTerms(f, area, &ahm, &corr);
    return 69.55 + 26.16 * std::log10(f) - 13.82 * std::log10(hb) - ahm +
           (44.9 - 6.55 * std::log10(hb)) * std::log10(d) + corr;
}

double cost231(double f, double d, double h, Area area) {
    const double hb = clampd(h, 3.0, 200.0);
    double ahm, corr;
    hataTerms(f, area, &ahm, &corr);
    const double cm = area == Area::DenseUrban ? 3.0 : 0.0;
    return 46.3 + 33.9 * std::log10(f) - 13.82 * std::log10(hb) - ahm +
           (44.9 - 6.55 * std::log10(hb)) * std::log10(d) + cm + corr;
}

Pl blend(double pLos, double plLos, double plNlos, double sLos, double sNlos) {
    return {pLos * plLos + (1.0 - pLos) * plNlos, pLos * sLos + (1.0 - pLos) * sNlos};
}

Pl uma(double fc, double d2, double d3, double hBs) {
    const double dbp = 4.0 * (hBs - kHE) * (kHRx - kHE) * fc * 1e9 / kCLight;
    double plLos;
    if (d2 <= dbp) {
        plLos = 28.0 + 22.0 * std::log10(d3) + 20.0 * std::log10(fc);
    } else {
        plLos = 28.0 + 40.0 * std::log10(d3) + 20.0 * std::log10(fc) -
                9.0 * std::log10(dbp * dbp + (hBs - kHRx) * (hBs - kHRx));
    }
    const double plNlosP = 13.54 + 39.08 * std::log10(d3) + 20.0 * std::log10(fc) - 0.6 * (kHRx - 1.5);
    const double plNlos = std::fmax(plLos, plNlosP);
    const double p = d2 <= 18.0 ? 1.0 : 18.0 / d2 + std::exp(-d2 / 63.0) * (1.0 - 18.0 / d2);
    return blend(p, plLos, plNlos, 4.0, 6.0);
}

double rmaPl1(double fc, double d3, double h) {
    return 20.0 * std::log10(40.0 * kPi * d3 * fc / 3.0) + std::fmin(0.03 * std::pow(h, 1.72), 10.0) * std::log10(d3) -
           std::fmin(0.044 * std::pow(h, 1.72), 14.77) + 0.002 * std::log10(h) * d3;
}

Pl rma(double fc, double d2, double d3, double hBs) {
    constexpr double h = 5.0;
    constexpr double w = 20.0;
    const double dbp = 2.0 * kPi * hBs * kHRx * fc * 1e9 / kCLight;
    const double plLos = d2 <= dbp ? rmaPl1(fc, d3, h) : rmaPl1(fc, dbp, h) + 40.0 * std::log10(d3 / dbp);
    const double l = std::log10(11.75 * kHRx);
    const double plNlosP = 161.04 - 7.1 * std::log10(w) + 7.5 * std::log10(h) -
                           (24.37 - 3.7 * (h / hBs) * (h / hBs)) * std::log10(hBs) +
                           (43.42 - 3.1 * std::log10(hBs)) * (std::log10(d3) - 3.0) + 20.0 * std::log10(fc) -
                           (3.2 * l * l - 4.97);
    const double plNlos = std::fmax(plLos, plNlosP);
    const double p = d2 <= 10.0 ? 1.0 : std::exp(-(d2 - 10.0) / 1000.0);
    return blend(p, plLos, plNlos, 4.0, 8.0);
}

Pl umi(double fc, double d2, double d3, double hBs) {
    const double dbp = 4.0 * (hBs - kHE) * (kHRx - kHE) * fc * 1e9 / kCLight;
    double plLos;
    if (d2 <= dbp) {
        plLos = 32.4 + 21.0 * std::log10(d3) + 20.0 * std::log10(fc);
    } else {
        plLos = 32.4 + 40.0 * std::log10(d3) + 20.0 * std::log10(fc) -
                9.5 * std::log10(dbp * dbp + (hBs - kHRx) * (hBs - kHRx));
    }
    const double plNlosP = 35.3 * std::log10(d3) + 22.4 + 21.3 * std::log10(fc) - 0.3 * (kHRx - 1.5);
    const double plNlos = std::fmax(plLos, plNlosP);
    const double p = d2 <= 18.0 ? 1.0 : 18.0 / d2 + std::exp(-d2 / 36.0) * (1.0 - 18.0 / d2);
    return blend(p, plLos, plNlos, 4.0, 7.82);
}

Area sanitizeArea(Area a) { return static_cast<uint8_t>(a) > 3 ? Area::Rural : a; }

// Inputs already sane: f in MHz, d in km (3D), h in m.
Pl pathLossD(double f, double dKm, double hTx, Area area) {
    const double d = dKm < kMinKm ? kMinKm : dKm;
    const double h = hTx < 3.0 ? 3.0 : hTx;
    if (f < 1500.0) return {hata(f, d, h, area), 8.0};
    if (f <= 2200.0) return {cost231(f, d, h, area), 8.0};
    const double fc = f / 1000.0;
    const double dh = h - kHRx;
    double d2 = std::sqrt(std::fmax((d * 1000.0) * (d * 1000.0) - dh * dh, 0.0));
    d2 = std::fmax(d2, 10.0);
    const double d3 = std::sqrt(d2 * d2 + dh * dh);
    if (f >= 24250.0) return umi(fc, d2, d3, h);
    if (area == Area::DenseUrban || area == Area::Urban) return uma(fc, d2, d3, h);
    return rma(fc, d2, d3, h);
}

double sanitizeFreq(double f) { return sane(f, 30.0, 100000.0, 1800.0); }
double sanitizeHeight(double h) { return sane(h, 3.0, 1000.0, 3.0); }

double maxRangeD(double eirpRe, double f, double h, Area area) {
    auto margin = [&](double d) { return eirpRe - pathLossD(f, d, h, area).pl - kFloorDbm; };
    double lo = kMinKm;
    double hi = kCapKm;
    if (margin(hi) >= 0.0) return kCapKm;
    if (margin(lo) < 0.0) return 0.0;
    for (int i = 0; i < 60; ++i) {
        const double mid = 0.5 * (lo + hi);
        if (margin(mid) >= 0.0) {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    return lo;
}

}  // namespace

PathLoss pathLoss(float freqMhz, float dKm3d, float hTxM, Area area) {
    const Pl r = pathLossD(sanitizeFreq(freqMhz), sane(dKm3d, kMinKm, 1.0e4, kCapKm), sanitizeHeight(hTxM),
                           sanitizeArea(area));
    return {static_cast<float>(r.pl), static_cast<float>(r.sigma)};
}

float o2iLowLossDb(float freqMhz) {
    const double f = sanitizeFreq(freqMhz) / 1000.0;
    return static_cast<float>(5.0 - 10.0 * std::log10(0.3 * std::pow(10.0, -(2.0 + 0.2 * f) / 10.0) +
                                                      0.7 * std::pow(10.0, -(5.0 + 4.0 * f) / 10.0)) +
                              2.5);
}

float maxRangeKm(float eirpReDbm, float freqMhz, float hTxM, Area area) {
    return static_cast<float>(maxRangeD(sane(eirpReDbm, -300.0, 300.0, -300.0), sanitizeFreq(freqMhz),
                                        sanitizeHeight(hTxM), sanitizeArea(area)));
}

float cullingRangeKm(float eirpReDbm, float freqMhz, float hTxM) {
    const double e = sane(eirpReDbm, -300.0, 300.0, -300.0);
    const double f = sanitizeFreq(freqMhz);
    const double h = sanitizeHeight(hTxM);
    double best = 0.0;
    for (Area a : {Area::DenseUrban, Area::Urban, Area::Suburban, Area::Rural}) {
        best = std::fmax(best, maxRangeD(e, f, h, a));
    }
    return static_cast<float>(best);
}

}  // namespace sa
