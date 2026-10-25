// Rules 6, 7 and 9: path loss, O2I loss and culling range.
#ifndef SIGNAL_ATLAS_MODEL_PATHLOSS_H
#define SIGNAL_ATLAS_MODEL_PATHLOSS_H

#include <cmath>
#include <cstdint>

#include "model/environment.h"
#include "model/limits.h"

namespace sa {

enum class Area : uint8_t { DenseUrban = 0, Urban = 1, Suburban = 2, Rural = 3 };

struct PathLoss {
    float plDb;
    float sigmaDb;  // blended shadowing sigma
};

// Per-carrier path-loss state: everything that depends only on (frequency, h_tx, area class) is computed once
// (log10 terms, a(h_m), corrections, break point, model choice). Math is double, as in the reference.
struct PreparedPathLoss {
    enum class Kind : uint8_t { Hata, Cost231, UMa, RMa, UMi };

    struct Pl {
        double pl;
        double sigma;
    };

    Kind kind;
    double o2iDb;
    // Hata / COST-231: pl = base + slope * log10(d). envBase[e] is `base` for receiver environment class e (rule 5a:
    // the class changes only a(h_m), the SUBURBAN / RURAL corrections and C_m); envApplies is false for TR 38.901.
    double base, slope;
    double envBase[4];
    bool envApplies;
    // TR 38.901.
    double dh;                  // h_BS - h_UT
    double dbp;                 // break point (m)
    double fc, lfc;             // carrier in GHz and its log10
    double c1, k1, c2;          // LOS: PL1 = c1 + k1 log d3 + 20 lfc; PL2 = c1 + 40 log d3 + 20 lfc - c2
    double n0, n1, nf;          // UMa/UMi NLOS': n0 + n1 log d3 + nf lfc;  RMa: n0 + n1 (log d3 - 3)
    double pD, pL;              // LOS probability: 1 below pD, else pD/d2 + exp(-d2/pL)(1 - pD/d2)
    double sLos, sNlos;
    double rK, rC, rH, rPl1Dbp; // RMa PL1 terms and PL1(d_BP)

    // Raw double evaluation. `d3dKm` is the 3D distance, finite. `env` (rule 5a, nullable) blends the four classes'
    // Hata / COST-231 receiver terms by its weights; it changes nothing for TR 38.901 or when not valid.
    Pl evalD(double d3dKm, const EnvWeights* env = nullptr) const {
        const double d = d3dKm < 0.02 ? 0.02 : d3dKm;
        if (kind == Kind::Hata || kind == Kind::Cost231) {
            const double b = env != nullptr && env->valid
                                 ? env->w[0] * envBase[0] + env->w[1] * envBase[1] + env->w[2] * envBase[2] + env->w[3] * envBase[3]
                                 : base;
            return {b + slope * std::log10(d), 8.0};
        }
        const double dd = d * 1000.0;
        double d2 = std::sqrt(std::fmax(dd * dd - dh * dh, 0.0));
        d2 = std::fmax(d2, 10.0);
        const double d3 = std::sqrt(d2 * d2 + dh * dh);
        const double ld3 = std::log10(d3);
        double plLos, plNlosP, p;
        if (kind == Kind::RMa) {
            if (d2 <= dbp) {
                plLos = 20.0 * std::log10(40.0 * 3.14159265358979323846 * d3 * fc / 3.0) +
                        rK * ld3 - rC + rH * d3;
            } else {
                plLos = rPl1Dbp + 40.0 * std::log10(d3 / dbp);
            }
            plNlosP = n0 + n1 * (ld3 - 3.0);
            p = d2 <= pD ? 1.0 : std::exp(-(d2 - pD) / pL);
        } else {
            if (d2 <= dbp) {
                plLos = c1 + k1 * ld3 + 20.0 * lfc;
            } else {
                plLos = c1 + 40.0 * ld3 + 20.0 * lfc - c2;
            }
            plNlosP = n0 + n1 * ld3 + nf * lfc;
            p = d2 <= pD ? 1.0 : pD / d2 + std::exp(-d2 / pL) * (1.0 - pD / d2);
        }
        const double plNlos = std::fmax(plLos, plNlosP);
        return {p * plLos + (1.0 - p) * plNlos, p * sLos + (1.0 - p) * sNlos};
    }

    // `d3dKm` is the 3D distance, floored at 0.02 km; NaN is treated as 80 km.
    PathLoss evaluate(float d3dKm, const EnvWeights* env = nullptr) const {
        const Pl r = evalD(sane(d3dKm, 0.02, 1.0e4, 80.0), env);
        return {static_cast<float>(r.pl), static_cast<float>(r.sigma)};
    }

    // Rule 7: PL + O2I low loss, and sigma combined with the O2I sigma in quadrature.
    PathLoss evaluateIndoor(float d3dKm, const EnvWeights* env = nullptr) const {
        const Pl r = evalD(sane(d3dKm, 0.02, 1.0e4, 80.0), env);
        return {static_cast<float>(r.pl + o2iDb),
                static_cast<float>(std::sqrt(r.sigma * r.sigma + kO2iSigmaDb * kO2iSigmaDb))};
    }
};

// Values of Area above Rural are Rural; frequency and height are sanitised (see limits.h).
PreparedPathLoss preparePathLoss(float freqMhz, float hTxM, Area area);

// Thin wrappers: preparePathLoss(...).evaluate / evaluateIndoor.
PathLoss pathLoss(float freqMhz, float dKm3d, float hTxM, Area area);
PathLoss pathLossIndoor(float freqMhz, float dKm3d, float hTxM, Area area);

// Rule 7: O2I low-loss model including the 2.5 dB term.
float o2iLowLossDb(float freqMhz);

// Rule 9: 3D distance (km, <= 80) at which eirpRe - PL (A = 0, outdoor, one area class) falls to -140 dBm.
float maxRangeKm(float eirpReDbm, float freqMhz, float hTxM, Area area);

// Rule 9 R_max: the maximum of maxRangeKm over the four area classes.
float cullingRangeKm(float eirpReDbm, float freqMhz, float hTxM);

}  // namespace sa

#endif  // SIGNAL_ATLAS_MODEL_PATHLOSS_H
