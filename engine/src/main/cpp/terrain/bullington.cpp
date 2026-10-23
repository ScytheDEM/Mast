#include "terrain/bullington.h"

#include <cmath>
#include <vector>

namespace sa {

namespace {

constexpr double kInf = 1e300;

double wavelengthM(double freqMhz) { return kLightSpeed / (freqMhz * 1e6); }

// Eq. 44: the highest diffraction parameter over the interior points (line-of-sight case).
double nuMaxLos(const double* d, const double* hb, int32_t n, double hTs, double hRs, double lam) {
    const double dd = d[n - 1];
    double best = -kInf;
    for (int32_t i = 1; i < n - 1; ++i) {
        const double di = d[i];
        const double nu = (hb[i] - (hTs * (dd - di) + hRs * di) / dd) * std::sqrt(0.002 * dd / (lam * di * (dd - di)));
        if (nu > best) best = nu;
    }
    return best;
}

}  // namespace

double knifeEdgeJ(double nu) {
    if (nu <= -0.78) return 0.0;
    return 6.9 + 20.0 * std::log10(std::sqrt((nu - 0.1) * (nu - 0.1) + 1.0) + nu - 0.1);
}

double bullingtonLuc(const double* d, const double* h, int32_t n, double hTs, double hRs, double freqMhz) {
    if (n < 3) return 0.0;  // no interior point: nothing to diffract over
    const double dd = d[n - 1];
    const double lam = wavelengthM(freqMhz);
    // Earth-curvature term of eqs. 41-44: h_i + 500 C_e d_i (d - d_i).
    std::vector<double> hb(static_cast<size_t>(n));
    for (int32_t i = 0; i < n; ++i) hb[static_cast<size_t>(i)] = h[i] + kBulgePerKm2 * d[i] * (dd - d[i]);
    // Eq. 41: highest slope from the transmitter to an interior point (m/km).
    double sTim = -kInf;
    for (int32_t i = 1; i < n - 1; ++i) {
        const double s = (hb[static_cast<size_t>(i)] - hTs) / d[i];
        if (s > sTim) sTim = s;
    }
    // Eq. 42: slope of the transmitter-receiver line.
    const double sTr = (hRs - hTs) / dd;
    double sRim = 0.0;
    if (sTim >= sTr) {
        // Eq. 45: highest slope from the receiver to an interior point.
        sRim = -kInf;
        for (int32_t i = 1; i < n - 1; ++i) {
            const double s = (hb[static_cast<size_t>(i)] - hRs) / (dd - d[i]);
            if (s > sRim) sRim = s;
        }
    }
    if (sTim < sTr || sTim + sRim <= 0.0) {
        // Eqs. 43-44: line of sight (the guard covers exact grazing, where eq. 46 is 0/0 and nu = 0).
        return knifeEdgeJ(nuMaxLos(d, hb.data(), n, hTs, hRs, lam));
    }
    // Eq. 46: distance of the Bullington point from the transmitter (km).
    const double dB = (hRs - hTs + sRim * dd) / (sTim + sRim);
    // Eq. 47: diffraction parameter at the Bullington point; eq. 48: L_uc = J(nu_b).
    const double nuB = (hTs + sTim * dB - (hTs * (dd - dB) + hRs * dB) / dd) *
                       std::sqrt(0.002 * dd / (lam * dB * (dd - dB)));
    return knifeEdgeJ(nuB);
}

TerrainExcess bullingtonExcess(const float* heights, int32_t n, float stepM, float totalDistM, float gTx, float gRx,
                               float hTxAgl, float hRxAgl, float freqMhz) {
    const TerrainExcess none{0.0, 0.0, 0.0};
    if (heights == nullptr || n < 3 || !(stepM > 0.0f) || !(totalDistM > 0.0f) || !(freqMhz > 0.0f) ||
        !std::isfinite(gTx) || !std::isfinite(gRx) || !std::isfinite(hTxAgl) || !std::isfinite(hRxAgl) ||
        !std::isfinite(totalDistM) || !std::isfinite(stepM) || !std::isfinite(freqMhz)) {
        return none;
    }
    const size_t count = static_cast<size_t>(n);
    std::vector<double> d(count), actual(count), ground(count);
    const double gt = gTx, gr = gRx;
    const double total = totalDistM;
    for (int32_t i = 0; i < n; ++i) {
        const double x = i == n - 1 ? total : i * static_cast<double>(stepM);
        d[static_cast<size_t>(i)] = x / 1000.0;
        // Rule 4: the straight ground line g_t + (g_r - g_t) x / d.
        ground[static_cast<size_t>(i)] = gt + (gr - gt) * x / total;
        actual[static_cast<size_t>(i)] = heights[i];
    }
    actual[0] = gt;
    actual[count - 1] = gr;
    const double hTs = gt + hTxAgl, hRs = gr + hRxAgl;
    TerrainExcess out;
    out.lUcActual = bullingtonLuc(d.data(), actual.data(), n, hTs, hRs, freqMhz);
    out.lUcRef = bullingtonLuc(d.data(), ground.data(), n, hTs, hRs, freqMhz);
    out.lTerr = out.lUcActual - out.lUcRef > 0.0 ? out.lUcActual - out.lUcRef : 0.0;
    return out;
}

float bullingtonExcessDb(const float* heights, int32_t n, float stepM, float totalDistM, float gTx, float gRx,
                         float hTxAgl, float hRxAgl, float freqMhz) {
    return static_cast<float>(bullingtonExcess(heights, n, stepM, totalDistM, gTx, gRx, hTxAgl, hRxAgl, freqMhz).lTerr);
}

}  // namespace sa
