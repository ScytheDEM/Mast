// Terrain rules 2-5: ITU-R P.526-16 section 4.5.3 Bullington construction and the terrain excess loss
// (Ruling B3: excess over the straight terminal-ground line, no eq. 49 correction). Math is double, with the
// operation order of tools/engine/terrain_reference.py.
#ifndef SIGNAL_ATLAS_TERRAIN_BULLINGTON_H
#define SIGNAL_ATLAS_TERRAIN_BULLINGTON_H

#include <cstdint>

namespace sa {

constexpr double kLightSpeed = 3.0e8;                             // lambda = c / f
constexpr double kEffectiveEarthRadiusKm = (4.0 / 3.0) * 6371.0;  // rule 2: a_e = k * 6371 km, k = 4/3
constexpr double kCe = 1.0 / kEffectiveEarthRadiusKm;             // P.526 C_e (1/km)
constexpr double kBulgePerKm2 = 500.0 * kCe;                      // bulge (m) = this * d_i (d - d_i), d in km

// P.526-16 eq. 31: knife-edge loss J(nu) (dB) for nu > -0.78, else 0.
double knifeEdgeJ(double nu);

// Eqs. 41-48 over one profile: L_uc (dB). `dKm` (km) and `h` (m amsl) hold n points including both terminals
// (dKm[0] = 0, dKm[n - 1] = path length); `hTs` / `hRs` are the antenna heights amsl. Only interior points enter
// the maxima; n < 3 gives 0. Brute force, O(n).
double bullingtonLuc(const double* dKm, const double* h, int32_t n, double hTs, double hRs, double freqMhz);

struct TerrainExcess {
    double lUcActual;  // L_uc on the real profile (dB)
    double lUcRef;     // L_uc on the straight ground line between the terminals
    double lTerr;      // max(0, lUcActual - lUcRef)
};

// Rules 3-5 for one profile. `heights` are the n ground heights (m amsl) at i * stepM for i < n - 1 and at
// totalDistM for the last one; only the interior ones are used. `gTx` / `gRx` are the terminals' ground heights and
// `hTxAgl` / `hRxAgl` the antennas above them. Invalid input (n < 3, non-positive distance or step, null heights,
// non-finite values) gives zeros.
TerrainExcess bullingtonExcess(const float* heights, int32_t n, float stepM, float totalDistM, float gTx, float gRx,
                               float hTxAgl, float hRxAgl, float freqMhz);

// L_terr (dB) of bullingtonExcess.
float bullingtonExcessDb(const float* heights, int32_t n, float stepM, float totalDistM, float gTx, float gRx,
                         float hTxAgl, float hRxAgl, float freqMhz);

}  // namespace sa

#endif  // SIGNAL_ATLAS_TERRAIN_BULLINGTON_H
