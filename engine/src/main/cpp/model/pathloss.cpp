#include "model/pathloss.h"

#include <cmath>
#include <initializer_list>

#include "model/limits.h"

// Double throughout: the reference is double, and the TR 38.901 break-point terms and the 60-step range bisection
// compound rounding; the tolerance is 0.01 dB / 0.01 km. Only results are narrowed to float.
namespace sa {
namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kCLight = 3.0e8;
constexpr double kHE = 1.0;
constexpr double kCapKm = 80.0;
constexpr double kFloorDbm = -140.0;

double clampd(double x, double lo, double hi) { return x < lo ? lo : (x > hi ? hi : x); }

Area sanitizeArea(Area a) { return static_cast<uint8_t>(a) > 3 ? Area::Rural : a; }

double o2iD(double fMhz) {
    const double f = fMhz / 1000.0;
    return 5.0 - 10.0 * std::log10(0.3 * std::pow(10.0, -(2.0 + 0.2 * f) / 10.0) +
                                   0.7 * std::pow(10.0, -(5.0 + 4.0 * f) / 10.0)) +
           2.5;
}

double rmaPl1(double fc, double d3, double h) {
    return 20.0 * std::log10(40.0 * kPi * d3 * fc / 3.0) + std::fmin(0.03 * std::pow(h, 1.72), 10.0) * std::log10(d3) -
           std::fmin(0.044 * std::pow(h, 1.72), 14.77) + 0.002 * std::log10(h) * d3;
}

double maxRangeD(const PreparedPathLoss& pp, double eirpRe) {
    auto margin = [&](double d) { return eirpRe - pp.evalD(d).pl - kFloorDbm; };
    double lo = 0.02;
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

PreparedPathLoss preparePathLoss(float freqMhz, float hTxM, Area areaIn) {
    const double f = sanitizeFreq(freqMhz);
    const double h = sanitizeHeight(hTxM);
    const Area area = sanitizeArea(areaIn);
    PreparedPathLoss p{};
    p.o2iDb = o2iD(f);
    p.sLos = p.sNlos = 8.0;
    if (f <= 2200.0) {
        // Okumura-Hata (< 1500 MHz) / COST-231 (1500..2200 MHz).
        const bool hata = f < 1500.0;
        p.kind = hata ? PreparedPathLoss::Kind::Hata : PreparedPathLoss::Kind::Cost231;
        const double hb = clampd(h, 3.0, 200.0);
        const double logf = std::log10(f);
        const double lead = hata ? 69.55 + 26.16 * logf : 46.3 + 33.9 * logf;
        // The receiver-surroundings terms of class a: -a(h_m) + C_m + correction.
        auto receiver = [&](Area a) {
            double ahm;
            if (a == Area::DenseUrban) {
                const double l = std::log10(11.75 * kHRx);
                ahm = 3.2 * l * l - 4.97;
            } else {
                ahm = (1.1 * logf - 0.7) * kHRx - (1.56 * logf - 0.8);
            }
            double corr = 0.0;
            if (a == Area::Suburban) {
                const double l = std::log10(f / 28.0);
                corr = -2.0 * l * l - 5.4;
            } else if (a == Area::Rural) {
                corr = -4.78 * logf * logf + 18.33 * logf - 35.94;
            }
            const double cm = (!hata && a == Area::DenseUrban) ? 3.0 : 0.0;
            return -ahm + cm + corr;
        };
        const double fixed = lead - 13.82 * std::log10(hb);
        p.base = fixed + receiver(area);
        for (int e = 0; e < 4; ++e) p.envBase[e] = fixed + receiver(static_cast<Area>(e));
        p.envApplies = true;
        p.slope = 44.9 - 6.55 * std::log10(hb);
        return p;
    }
    const double fc = f / 1000.0;
    p.fc = fc;
    p.lfc = std::log10(fc);
    p.dh = h - kHRx;
    if (f >= 24250.0) {  // UMi street canyon
        p.kind = PreparedPathLoss::Kind::UMi;
        p.dbp = 4.0 * (h - kHE) * (kHRx - kHE) * fc * 1e9 / kCLight;
        p.c1 = 32.4;
        p.k1 = 21.0;
        p.c2 = 9.5 * std::log10(p.dbp * p.dbp + p.dh * p.dh);
        p.n0 = 22.4;
        p.n1 = 35.3;
        p.nf = 21.3;
        p.pD = 18.0;
        p.pL = 36.0;
        p.sLos = 4.0;
        p.sNlos = 7.82;
    } else if (area == Area::DenseUrban || area == Area::Urban) {
        p.kind = PreparedPathLoss::Kind::UMa;
        p.dbp = 4.0 * (h - kHE) * (kHRx - kHE) * fc * 1e9 / kCLight;
        p.c1 = 28.0;
        p.k1 = 22.0;
        p.c2 = 9.0 * std::log10(p.dbp * p.dbp + p.dh * p.dh);
        p.n0 = 13.54;
        p.n1 = 39.08;
        p.nf = 20.0;
        p.pD = 18.0;
        p.pL = 63.0;
        p.sLos = 4.0;
        p.sNlos = 6.0;
    } else {  // RMa, h = 5 m, W = 20 m
        constexpr double hr = 5.0;
        constexpr double w = 20.0;
        p.kind = PreparedPathLoss::Kind::RMa;
        p.dbp = 2.0 * kPi * h * kHRx * fc * 1e9 / kCLight;
        p.rK = std::fmin(0.03 * std::pow(hr, 1.72), 10.0);
        p.rC = std::fmin(0.044 * std::pow(hr, 1.72), 14.77);
        p.rH = 0.002 * std::log10(hr);
        p.rPl1Dbp = rmaPl1(fc, p.dbp, hr);
        const double l = std::log10(11.75 * kHRx);
        p.n1 = 43.42 - 3.1 * std::log10(h);
        p.n0 = 161.04 - 7.1 * std::log10(w) + 7.5 * std::log10(hr) -
               (24.37 - 3.7 * (hr / h) * (hr / h)) * std::log10(h) + 20.0 * p.lfc - (3.2 * l * l - 4.97);
        p.pD = 10.0;
        p.pL = 1000.0;
        p.sLos = 4.0;
        p.sNlos = 8.0;
    }
    return p;
}

PathLoss pathLoss(float freqMhz, float dKm3d, float hTxM, Area area) {
    return preparePathLoss(freqMhz, hTxM, area).evaluate(dKm3d);
}

PathLoss pathLossIndoor(float freqMhz, float dKm3d, float hTxM, Area area) {
    return preparePathLoss(freqMhz, hTxM, area).evaluateIndoor(dKm3d);
}

float o2iLowLossDb(float freqMhz) { return static_cast<float>(o2iD(sanitizeFreq(freqMhz))); }

float maxRangeKm(float eirpReDbm, float freqMhz, float hTxM, Area area) {
    const PreparedPathLoss pp = preparePathLoss(freqMhz, hTxM, area);
    return static_cast<float>(maxRangeD(pp, sane(eirpReDbm, -300.0, 300.0, -300.0)));
}

float cullingRangeKm(float eirpReDbm, float freqMhz, float hTxM) {
    const double e = sane(eirpReDbm, -300.0, 300.0, -300.0);
    double best = 0.0;
    for (Area a : {Area::DenseUrban, Area::Urban, Area::Suburban, Area::Rural}) {
        best = std::fmax(best, maxRangeD(preparePathLoss(freqMhz, hTxM, a), e));
    }
    return static_cast<float>(best);
}

}  // namespace sa
