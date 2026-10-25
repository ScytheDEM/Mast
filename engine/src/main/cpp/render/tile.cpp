#include "render/tile.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstring>
#include <limits>
#include <vector>

#include "model/antenna.h"
#include "model/pathloss.h"
#include "model/stats.h"
#include "render/carrier.h"
#include "render/mercator.h"

namespace sa {
namespace {

using merc::kDegToRad;
using merc::kRadToDeg;

constexpr double kNoCoverageSinrDb = -20.0;
constexpr double kNoCoverageRsrp = -200.0;  // grid value of a sample with no carrier
constexpr double kLoad = 0.5;              // rule 8
constexpr int32_t kBlocks = 16;            // the grid is split into kBlocks x kBlocks blocks for the RSRP bounds
constexpr double kBoundSlackDb = 0.01;     // keeps the RSRP upper bound safe against rounding
constexpr double kInterferenceTol = 1e-3;  // relative error allowed when truncating the interference sum
constexpr int32_t kMaxZ = 30;

// One distinct (lat, lon): the carriers of a site are contiguous in the carrier array.
struct Site {
    double sinP, cosP, shP, chP;  // latitude (rad): sin, cos and the half-angle sin, cos
    double shL, chL;              // sin / cos of half the longitude
    int32_t first, count;
};

void zeroOutputs(const TileOut& out) {
    if (out.rsrp) std::memset(out.rsrp, 0, kTilePixels);
    if (out.sinr) std::memset(out.sinr, 0, kTilePixels);
    if (out.prob) std::memset(out.prob, 0, kTilePixels);
    if (out.best) std::fill(out.best, out.best + kTilePixels, kNoBest);
    if (out.terrainCoveredPx) *out.terrainCoveredPx = 0;
    if (out.terrainIncompletePx) *out.terrainIncompletePx = 0;
}

inline double clampd(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

// Rule 9 codes (round half up, like the reference).
inline uint8_t rsrpCode(double rsrp) {
    if (rsrp < -140.0) return 0;
    return static_cast<uint8_t>(clampd(std::floor((rsrp + 140.0) * 2.0 + 0.5), 1.0, 254.0));
}
inline uint8_t sinrCode(double sinr) {
    return static_cast<uint8_t>(clampd(std::floor((sinr + 20.0) * 4.0 + 0.5), 0.0, 255.0));
}
inline uint8_t probCode(double p) { return static_cast<uint8_t>(clampd(std::floor(p * 255.0 + 0.5), 0.0, 255.0)); }

struct Axis {
    int32_t i0, i1, nearest;
    double t;
};

}  // namespace

int32_t renderTile(const CarrierArrays& c, const PatternTable& patterns, const RenderParams& p, TileOut& out,
                   const volatile int32_t* cancelFlag) {
    if ((p.grid != 128 && p.grid != 256) || p.z < 0 || p.z > kMaxZ || c.n < 0 || c.n > 65535) return kRenderInvalid;
    if (c.n > 0 && (!c.lat || !c.lon || !c.hTx || !c.az || !c.tilt || !c.eirpRe || !c.freqMhz || !c.hBw || !c.fb ||
                    !c.gain || !c.rMaxKm || !c.tech || !c.sectorKey || !c.freqKey)) {
        return kRenderInvalid;
    }
    if (p.residualCount < 0 || p.residualCount > kMaxResidualRasters || (p.residualCount > 0 && !p.residuals)) {
        return kRenderInvalid;
    }
    for (int32_t r = 0; r < p.residualCount; ++r) {
        if (p.residuals[r].q == nullptr) return kRenderInvalid;
    }
    const int64_t tiles = int64_t{1} << p.z;
    if (p.x < 0 || p.y < 0 || p.x >= tiles || p.y >= tiles) return kRenderInvalid;

    // ---- Culling (rule 9): R_max against the nearest point of the tile's lat/lon box.
    const double south = merc::tileLatDeg(p.y + 1.0, p.z), north = merc::tileLatDeg(p.y, p.z);
    const double west = merc::tileLonDeg(p.x, p.z), east = merc::tileLonDeg(p.x + 1.0, p.z);
    std::vector<int32_t> alive;
    alive.reserve(static_cast<size_t>(c.n));
    for (int32_t i = 0; i < c.n; ++i) {
        if (!carrierUsable(c, i)) continue;
        const double lat = c.lat[i], lon = c.lon[i];
        const double rM = static_cast<double>(c.rMaxKm[i]) * 1000.0;
        if (!(merc::haversineM(lat, lon, clampd(lat, south, north), clampd(lon, west, east)) <= rM)) continue;
        alive.push_back(i);
    }
    if (cancelFlag != nullptr && *cancelFlag != 0) {
        zeroOutputs(out);
        return kRenderCancelled;
    }
    if (alive.empty()) {
        zeroOutputs(out);
        return 0;
    }

    // ---- Prepare each surviving carrier once, ordered by site so a site's geometry is computed once per point.
    std::sort(alive.begin(), alive.end(), [&](int32_t a, int32_t b) {
        if (c.lat[a] != c.lat[b]) return c.lat[a] < c.lat[b];
        if (c.lon[a] != c.lon[b]) return c.lon[a] < c.lon[b];
        return a < b;
    });
    const int32_t K = static_cast<int32_t>(alive.size());
    const size_t KK = static_cast<size_t>(K);
    std::vector<Carrier> car(KK);
    std::vector<Site> sites;
    std::vector<double> siteLat, siteLon;  // degrees
    std::vector<int32_t> siteOf(KK);
    for (int32_t k = 0; k < K; ++k) {
        const int32_t i = alive[static_cast<size_t>(k)];
        Carrier& cr = car[static_cast<size_t>(k)];
        cr = prepareCarrier(c, patterns, i, p.area);
        if (c.network != nullptr && c.network[i] < 32) {
            for (int32_t r = 0; r < p.residualCount; ++r) {
                if ((p.residuals[r].networkMask >> c.network[i]) & 1u) cr.rasterBits |= 1u << r;
            }
        }
        if (sites.empty() || siteLat.back() != c.lat[i] || siteLon.back() != c.lon[i]) {
            const double pr = c.lat[i] * kDegToRad, lr = c.lon[i] * kDegToRad;
            sites.push_back({std::sin(pr), std::cos(pr), std::sin(pr / 2.0), std::cos(pr / 2.0), std::sin(lr / 2.0),
                             std::cos(lr / 2.0), k, 0});
            siteLat.push_back(c.lat[i]);
            siteLon.push_back(c.lon[i]);
        }
        ++sites.back().count;
        siteOf[static_cast<size_t>(k)] = static_cast<int32_t>(sites.size()) - 1;
    }
    const int32_t S = static_cast<int32_t>(sites.size());

    // Interference groups: carriers with the same freqKey (CSR over `byKey`).
    std::vector<int32_t> byKey(KK);
    for (int32_t k = 0; k < K; ++k) byKey[static_cast<size_t>(k)] = k;
    std::sort(byKey.begin(), byKey.end(), [&](int32_t a, int32_t b) {
        const Carrier &ca = car[static_cast<size_t>(a)], &cb = car[static_cast<size_t>(b)];
        return ca.freqKey != cb.freqKey ? ca.freqKey < cb.freqKey : a < b;
    });
    std::vector<int32_t> groupFirst;
    for (int32_t m = 0; m < K; ++m) {
        if (m == 0 || car[static_cast<size_t>(byKey[static_cast<size_t>(m)])].freqKey !=
                          car[static_cast<size_t>(byKey[static_cast<size_t>(m - 1)])].freqKey) {
            groupFirst.push_back(m);
        }
        car[static_cast<size_t>(byKey[static_cast<size_t>(m)])].group = static_cast<int32_t>(groupFirst.size()) - 1;
    }
    groupFirst.push_back(K);
    const int32_t G = static_cast<int32_t>(groupFirst.size()) - 1;

    // ---- Grid evaluation (rules 8-9), branch and bound per block of grid points.
    //
    // For a block of B x B sample points every carrier gets an upper bound on its RSRP anywhere in the block:
    // eirpRe - PL(shortest possible distance) - (a lower bound of the antenna attenuation over the block; PL grows
    // with distance). Carriers are visited in decreasing bound; once a bound falls below the best RSRP found at the point,
    // no remaining carrier can be the best server, so the exact (expensive) antenna + path loss runs only for the
    // few contenders. The bound ignores the terrain loss (L_terr >= 0 only lowers a carrier's RSRP, so the bound
    // stays valid, merely looser where terrain matters). Calibration terms enter the bound exactly: the offset plus the
    // slope term's maximum over the block's distance range, plus each applicable residual raster's block maximum. The best server and its RSRP are therefore exactly the reference's. The interference sum over
    // the best server's frequency group is visited in decreasing bound and stops once the rest (bounded by the sum of
    // the remaining members' bounds) is under 1e-3 of noise + interference, i.e. SINR is at most 0.0043 dB high.
    // (2026-10-24: the old stop rule, group size times the next bound at 1e-5, evaluated ~1500 carriers per pixel in
    // Sydney, where a frequency group spans every site within 80 km: 90% of a 10 s z15 render. Now 1.8 s; RSRP and
    // probability are unchanged and 1.6% of that tile's SINR codes move by one 0.25 dB step.)
    const int32_t N = p.grid;
    const int32_t B = N / kBlocks;
    const size_t NN = static_cast<size_t>(N) * static_cast<size_t>(N);
    std::vector<double> gR(NN), gS(NN), gP(NN);
    std::vector<uint16_t> gB(NN);
    std::vector<uint8_t> gInc(NN, 0);  // 1 = the best server's L_terr was not a complete table lookup

    std::vector<double> colSh(static_cast<size_t>(N)), colCh(static_cast<size_t>(N));
    std::vector<double> colLonDeg(static_cast<size_t>(N)), rowLatDeg(static_cast<size_t>(N));
    for (int32_t i = 0; i < N; ++i) {
        colLonDeg[static_cast<size_t>(i)] = merc::tileLonDeg(p.x + (i + 0.5) / N, p.z);
        const double lr = colLonDeg[static_cast<size_t>(i)] * kDegToRad;
        colSh[static_cast<size_t>(i)] = std::sin(lr / 2.0);
        colCh[static_cast<size_t>(i)] = std::cos(lr / 2.0);
    }
    for (int32_t j = 0; j < N; ++j) rowLatDeg[static_cast<size_t>(j)] = merc::tileLatDeg(p.y + (j + 0.5) / N, p.z);
    // Rule 5a: the receiver environment blend of every grid sample (not valid = the carrier's own class), and per block
    // the classes with weight anywhere in it (bit e = class e, bit 4 = no data). The blended loss is a convex
    // combination of the classes' losses, so the smallest of those present bounds it from below.
    std::vector<EnvWeights> gEnv;
    if (p.buildings != nullptr) {
        gEnv.resize(NN);
        for (int32_t j = 0; j < N; ++j) {
            for (int32_t i = 0; i < N; ++i) {
                gEnv[static_cast<size_t>(j) * N + i] =
                    environmentAt(p.buildings, rowLatDeg[static_cast<size_t>(j)], colLonDeg[static_cast<size_t>(i)]);
            }
        }
    }
    // Residual rasters: the pixel a grid index samples, and per raster the value (dB) at the current point.
    const int32_t R = p.residualCount;
    std::vector<int32_t> rasterPx(static_cast<size_t>(N));
    for (int32_t i = 0; i < N; ++i) rasterPx[static_cast<size_t>(i)] = static_cast<int32_t>(std::floor((i + 0.5) * 256.0 / N));
    std::vector<double> resAt(static_cast<size_t>(R)), resMax(static_cast<size_t>(R));
    // Sum of the rasters selected by `bits`, from per-raster values `v`.
    auto rasterSum = [R](uint32_t bits, const double* v) {
        double sum = 0.0;
        for (int32_t r = 0; r < R && bits != 0; ++r, bits >>= 1) {
            if (bits & 1u) sum += v[r];
        }
        return sum;
    };

    // Per block of the current block row: bound (dBm), bound as 0.5 * mW, and the visiting order.
    std::vector<double> ub(static_cast<size_t>(kBlocks) * KK), ubLin(static_cast<size_t>(kBlocks) * KK);
    std::vector<int32_t> ord(static_cast<size_t>(kBlocks) * KK);
    // Per block: the carriers of each interference group in decreasing bound (CSR over groupFirst) and the sum of the
    // group's linear bounds, which bounds the interference of the members not evaluated yet.
    std::vector<int32_t> gord(static_cast<size_t>(kBlocks) * KK), gcur(static_cast<size_t>(G));
    std::vector<double> gsum(static_cast<size_t>(kBlocks) * static_cast<size_t>(G));

    std::vector<double> rowSy2(static_cast<size_t>(S)), rowCC(static_cast<size_t>(S)), rowXa(static_cast<size_t>(S)),
        rowXb(static_cast<size_t>(S));
    std::vector<double> rs(KK), sd2(static_cast<size_t>(S)), sbrg(static_cast<size_t>(S));
    std::vector<int32_t> evalStamp(KK, 0), siteStamp(static_cast<size_t>(S), 0), evk(KK);
    int32_t stamp = 0;
    const bool indoor = p.indoor;

    for (int32_t bj = 0; bj < kBlocks; ++bj) {
        if (cancelFlag != nullptr && *cancelFlag != 0) {
            zeroOutputs(out);
            return kRenderCancelled;
        }
        // Bounds and visiting order for the blocks of this block row.
        for (int32_t bi = 0; bi < kBlocks; ++bi) {
            const int32_t jc = bj * B + B / 2, ic = bi * B + B / 2;
            const double cLat = rowLatDeg[static_cast<size_t>(jc)], cLon = colLonDeg[static_cast<size_t>(ic)];
            double radius = 0.0;
            for (const int32_t cj : {bj * B, bj * B + B - 1}) {
                for (const int32_t ci : {bi * B, bi * B + B - 1}) {
                    radius = std::max(radius, merc::haversineM(cLat, cLon, rowLatDeg[static_cast<size_t>(cj)],
                                                               colLonDeg[static_cast<size_t>(ci)]));
                }
            }
            radius = radius * 1.05 + 2.0;  // every point of the block lies within `radius` of the centre sample
            uint32_t envMask = 1u << 4;  // without a building grid every sample uses the carrier's class
            if (!gEnv.empty()) {
                envMask = 0;
                for (int32_t j = bj * B; j < (bj + 1) * B; ++j) {
                    for (int32_t i = bi * B; i < (bi + 1) * B; ++i) {
                        const EnvWeights& e = gEnv[static_cast<size_t>(j) * N + i];
                        if (!e.valid) {
                            envMask |= 1u << 4;
                            continue;
                        }
                        for (uint32_t c = 0; c < 4; ++c) {
                            if (e.w[c] > 0.0) envMask |= 1u << c;
                        }
                    }
                }
            }
            // The largest value each residual raster takes at the block's samples.
            for (int32_t r = 0; r < R; ++r) {
                int32_t hi = -128;
                const int8_t* q = p.residuals[r].q;
                for (int32_t j = bj * B; j < (bj + 1) * B; ++j) {
                    const int8_t* row = q + static_cast<size_t>(rasterPx[static_cast<size_t>(j)]) * 256;
                    for (int32_t i = bi * B; i < (bi + 1) * B; ++i) hi = std::max<int32_t>(hi, row[rasterPx[static_cast<size_t>(i)]]);
                }
                resMax[static_cast<size_t>(r)] = hi * 0.25;
            }
            double* bub = &ub[static_cast<size_t>(bi) * KK];
            double* blin = &ubLin[static_cast<size_t>(bi) * KK];
            int32_t* bord = &ord[static_cast<size_t>(bi) * KK];
            for (int32_t s = 0; s < S; ++s) {
                const double sLat = siteLat[static_cast<size_t>(s)], sLon = siteLon[static_cast<size_t>(s)];
                const double dc = merc::haversineM(sLat, sLon, cLat, cLon);
                const double dlo = std::max(0.0, dc - radius), dhi = dc + radius;
                // Bearing window of the block seen from the site: centre bearing +- half-width (spherical).
                double bearingC = 0.0, halfWidth = 180.0;
                if (dc > radius) {
                    const double p1 = sLat * kDegToRad, p2 = cLat * kDegToRad, dl = (cLon - sLon) * kDegToRad;
                    bearingC = std::atan2(std::sin(dl) * std::cos(p2),
                                          std::cos(p1) * std::sin(p2) - std::sin(p1) * std::cos(p2) * std::cos(dl)) * kRadToDeg;
                    const double ratio = std::sin(radius / merc::kEarthRadiusM) / std::sin(dc / merc::kEarthRadiusM);
                    halfWidth = std::asin(std::fmin(1.0, ratio)) * kRadToDeg * 1.01 + 0.01;
                }
                const Site& st = sites[static_cast<size_t>(s)];
                for (int32_t k = st.first; k < st.first + st.count; ++k) {
                    const Carrier& cr = car[static_cast<size_t>(k)];
                    const float d3Km = static_cast<float>(std::sqrt(dlo * dlo + cr.hd2) / 1000.0);
                    // The smallest path loss over the receiver classes present in the block (rule 5a; only the
                    // Hata family depends on them), so the bound stays an upper bound.
                    PathLoss pl = indoor ? cr.pl.evaluateIndoor(d3Km) : cr.pl.evaluate(d3Km);
                    if (cr.pl.envApplies && envMask != (1u << 4)) {
                        if (!((envMask >> 4) & 1u)) pl.plDb = std::numeric_limits<float>::infinity();
                        for (uint8_t e = 0; e < 4; ++e) {
                            if (!((envMask >> e) & 1u)) continue;
                            const EnvWeights only{true, {e == 0 ? 1.0 : 0.0, e == 1 ? 1.0 : 0.0, e == 2 ? 1.0 : 0.0, e == 3 ? 1.0 : 0.0}};
                            const PathLoss q = indoor ? cr.pl.evaluateIndoor(d3Km, &only) : cr.pl.evaluate(d3Km, &only);
                            if (q.plDb < pl.plDb) pl = q;
                        }
                    }
                    // Lower bounds on the antenna attenuation over the block (A = min(A_H + A_V, maxA)).
                    const PreparedAntenna& an = cr.ant;
                    double aH = 0.0;
                    if (an.hrp != nullptr) {
                        // Smallest HRP attenuation over the integer-degree bins the window can touch (rounded
                        // outwards by one bin each side, with wrap); the whole pattern when the window is wide.
                        aH = cr.hrpMinDb;
                        if (halfWidth < 180.0) {
                            const int64_t from = static_cast<int64_t>(std::floor(bearingC - halfWidth + 0.5)) - 1;
                            const int64_t to = static_cast<int64_t>(std::floor(bearingC + halfWidth + 0.5)) + 1;
                            if (to - from < 359) {
                                uint8_t lo = 255;
                                for (int64_t bin = from; bin <= to; ++bin) {
                                    int64_t idx = (bin - an.hrpPeak) % 360;
                                    if (idx < 0) idx += 360;
                                    lo = std::min(lo, an.hrp[idx]);
                                }
                                aH = lo * 0.25;
                            }
                        }
                    } else if (!an.omni && halfWidth < 180.0) {
                        double dphi = std::fmod(bearingC - an.azimuth + 180.0, 360.0);
                        if (dphi < 0.0) dphi += 360.0;
                        const double gap = std::fmax(0.0, std::fabs(dphi - 180.0) - halfWidth);
                        aH = std::fmin(12.0 * (gap / an.hbw) * (gap / an.hbw), an.fb);
                    }
                    const double thHi = std::atan2(an.heightDiff, dlo) * kRadToDeg;
                    const double thLo = std::atan2(an.heightDiff, dhi) * kRadToDeg;
                    const double vGap = std::fmax(0.0, std::fmax(thLo - an.tilt, an.tilt - thHi));
                    const double aV = std::fmin(12.0 * (vGap / an.theta3) * (vGap / an.theta3), 30.0);
                    const double aBound = std::fmin(aH + aV, an.maxA);
                    // Calibration: the offset + slope term at its largest over [dlo, dhi], and each applicable
                    // raster's block maximum (L_terr >= 0 is still left out).
                    bub[k] = cr.eirpRe - aBound - pl.plDb + cr.cal.upperBound(dlo, dhi) +
                             rasterSum(cr.rasterBits, resMax.data()) + kBoundSlackDb;
                    if (p.debugNoBounds) bub[k] = std::numeric_limits<double>::infinity();
                    blin[k] = kLoad * std::pow(10.0, bub[k] / 10.0);
                    bord[k] = k;
                }
            }
            std::sort(bord, bord + K, [bub](int32_t a, int32_t b) { return bub[a] != bub[b] ? bub[a] > bub[b] : a < b; });
            int32_t* bgord = &gord[static_cast<size_t>(bi) * KK];
            double* bgsum = &gsum[static_cast<size_t>(bi) * static_cast<size_t>(G)];
            for (int32_t g = 0; g < G; ++g) {
                gcur[static_cast<size_t>(g)] = groupFirst[static_cast<size_t>(g)];
                bgsum[g] = 0.0;
            }
            for (int32_t m = 0; m < K; ++m) {
                const int32_t k = bord[m];
                const int32_t g = car[static_cast<size_t>(k)].group;
                bgord[gcur[static_cast<size_t>(g)]++] = k;
                bgsum[g] += blin[k];
            }
        }

        for (int32_t j = bj * B; j < (bj + 1) * B; ++j) {
            if (cancelFlag != nullptr && *cancelFlag != 0) {
                zeroOutputs(out);
                return kRenderCancelled;
            }
            const double pr = rowLatDeg[static_cast<size_t>(j)] * kDegToRad;
            const double sinP2 = std::sin(pr), cosP2 = std::cos(pr);
            const double shP2 = std::sin(pr / 2.0), chP2 = std::cos(pr / 2.0);
            for (int32_t s = 0; s < S; ++s) {
                const Site& st = sites[static_cast<size_t>(s)];
                const double sy = shP2 * st.chP - chP2 * st.shP;
                rowSy2[static_cast<size_t>(s)] = sy * sy;
                rowCC[static_cast<size_t>(s)] = st.cosP * cosP2;
                rowXa[static_cast<size_t>(s)] = st.cosP * sinP2;
                rowXb[static_cast<size_t>(s)] = st.sinP * cosP2;
            }

            const int8_t* rasterRow[kMaxResidualRasters];
            for (int32_t r = 0; r < R; ++r) {
                rasterRow[r] = p.residuals[r].q + static_cast<size_t>(rasterPx[static_cast<size_t>(j)]) * 256;
            }
            for (int32_t i = 0; i < N; ++i) {
                for (int32_t r = 0; r < R; ++r) resAt[static_cast<size_t>(r)] = rasterRow[r][rasterPx[static_cast<size_t>(i)]] * 0.25;
                const size_t bi = static_cast<size_t>(i / B);
                const double* bub = &ub[bi * KK];
                const double* blin = &ubLin[bi * KK];
                const int32_t* bord = &ord[bi * KK];
                const int32_t* bgord = &gord[bi * KK];
                const double* bgsum = &gsum[bi * static_cast<size_t>(G)];
                const double cs = colSh[static_cast<size_t>(i)], cc = colCh[static_cast<size_t>(i)];
                const EnvWeights* envHere = gEnv.empty() ? nullptr : &gEnv[static_cast<size_t>(j) * N + i];
                ++stamp;
                double bestR = -std::numeric_limits<double>::infinity();
                float bestSigma = 8.0f;
                int32_t bestK = -1, nEval = 0;
                bool bestInc = true;

                // Exact RSRP of carrier k at this point (the geometry of its site is computed once per point).
                auto evaluate = [&](int32_t k) {
                    const Carrier& cr = car[static_cast<size_t>(k)];
                    const size_t s = static_cast<size_t>(siteOf[static_cast<size_t>(k)]);
                    if (siteStamp[s] != stamp) {
                        siteStamp[s] = stamp;
                        const Site& st = sites[s];
                        const double sh = cs * st.chL - cc * st.shL;
                        const double ch = cc * st.chL + cs * st.shL;
                        const double a = std::fmin(1.0, rowSy2[s] + rowCC[s] * sh * sh);
                        sd2[s] = 2.0 * merc::kEarthRadiusM * std::asin(std::sqrt(a));
                        const double by = 2.0 * sh * ch * cosP2;
                        const double bx = rowXa[s] - rowXb[s] * (1.0 - 2.0 * sh * sh);
                        double brg = std::atan2(by, bx) * kRadToDeg;
                        if (brg < 0.0) brg += 360.0;
                        sbrg[s] = brg;
                    }
                    // Terrain rule 5 and Calibration rules 4-6: RSRP = eirpRe - A - PL_quick - L_terr + calibration
                    // + residual. A carrier without a table keeps the quick model and counts as incomplete.
                    const CarrierEval ev = evaluateCarrier(cr, sbrg[s], sd2[s], indoor, envHere);
                    const double r = cr.rasterBits == 0 ? ev.rsrp : ev.rsrp + rasterSum(cr.rasterBits, resAt.data());
                    evalStamp[static_cast<size_t>(k)] = stamp;
                    rs[static_cast<size_t>(k)] = r;
                    evk[static_cast<size_t>(nEval++)] = k;
                    if (bestK < 0 || r > bestR || (r == bestR && cr.orig < car[static_cast<size_t>(bestK)].orig)) {
                        bestR = r;
                        bestK = k;
                        bestSigma = ev.sigmaDb;
                        bestInc = ev.incomplete;
                    }
                };

                for (int32_t m = 0; m < K; ++m) {
                    const int32_t k = bord[m];
                    if (bestK >= 0 && bub[k] < bestR) break;  // nothing after this one can beat the best
                    evaluate(k);
                }

                double sinr = kNoCoverageSinrDb, prob = 0.0, rsrp = kNoCoverageRsrp;
                uint16_t bestIdx = kNoBest;
                if (bestK >= 0) {
                    const Carrier& b = car[static_cast<size_t>(bestK)];
                    const int32_t g = b.group;
                    // `rem` bounds the interference of the group members not evaluated yet: the group's bound sum less
                    // the bounds of those already evaluated or never interfering (same sector).
                    double interf = 0.0, rem = bgsum[g];
                    for (int32_t e = 0; e < nEval; ++e) {
                        const int32_t k = evk[static_cast<size_t>(e)];
                        const Carrier& o = car[static_cast<size_t>(k)];
                        if (o.group != g) continue;
                        rem -= blin[k];
                        if (o.sectorKey != b.sectorKey) interf += kLoad * std::pow(10.0, rs[static_cast<size_t>(k)] / 10.0);
                    }
                    for (int32_t m = groupFirst[static_cast<size_t>(g)]; m < groupFirst[static_cast<size_t>(g) + 1]; ++m) {
                        const int32_t k = bgord[m];
                        if (evalStamp[static_cast<size_t>(k)] == stamp) continue;
                        if (car[static_cast<size_t>(k)].sectorKey == b.sectorKey) {
                            rem -= blin[k];
                            continue;
                        }
                        if (rem < kInterferenceTol * (b.noiseMw + interf)) break;
                        evaluate(k);
                        interf += kLoad * std::pow(10.0, rs[static_cast<size_t>(k)] / 10.0);
                        rem -= blin[k];
                    }
                    rsrp = bestR;
                    sinr = bestR - 10.0 * std::log10(interf + b.noiseMw);
                    prob = coverageProbability(static_cast<float>(bestR), p.thrDbm, bestSigma);
                    bestIdx = static_cast<uint16_t>(b.orig);
                }
                const size_t at = static_cast<size_t>(j) * static_cast<size_t>(N) + static_cast<size_t>(i);
                gR[at] = rsrp;
                gS[at] = sinr;
                gP[at] = prob;
                gB[at] = bestIdx;
                gInc[at] = bestIdx == kNoBest ? 0 : (bestInc ? 1 : 0);
            }
        }
    }

    // ---- Quantise (and bilinearly upsample 128 -> 256, floats before codes; `best` takes the nearest sample).
    int32_t covered = 0, incomplete = 0;
    if (N == 256) {
        for (size_t q = 0; q < NN; ++q) {
            const uint8_t code = rsrpCode(gR[q]);
            if (code != 0) {
                ++covered;
                incomplete += gInc[q];
            }
            if (out.rsrp) out.rsrp[q] = code;
            if (out.sinr) out.sinr[q] = sinrCode(gS[q]);
            if (out.prob) out.prob[q] = probCode(gP[q]);
            if (out.best) out.best[q] = code == 0 ? kNoBest : gB[q];
        }
        if (out.terrainCoveredPx) *out.terrainCoveredPx = covered;
        if (out.terrainIncompletePx) *out.terrainIncompletePx = incomplete;
        return K;
    }
    // Rule 9 (amended 2026-10-24): the outermost pixels lie a quarter sample outside the outermost samples and are
    // extrapolated linearly from the last two (t in [-0.25, 0) or (1, 1.25]) instead of clamped, so both sides of a
    // tile border estimate the field at the border (clamping left a step of ~1.5 samples' gradient there). Where any
    // of the four samples has no coverage, t is clamped as before (no extrapolation from the -200 sentinel).
    Axis axis[256];
    const double scale = N / 256.0;
    for (int32_t q = 0; q < 256; ++q) {
        const double g = (q + 0.5) * scale - 0.5;
        const int32_t i0 = std::clamp(static_cast<int32_t>(std::floor(g)), 0, N - 2);
        axis[q] = {i0, i0 + 1, std::clamp(static_cast<int32_t>(std::floor(g + 0.5)), 0, N - 1), g - i0};
    }
    for (int32_t py = 0; py < 256; ++py) {
        const Axis& ay = axis[py];
        const double* r0 = &gR[static_cast<size_t>(ay.i0) * N];
        const double* r1 = &gR[static_cast<size_t>(ay.i1) * N];
        const double* s0 = &gS[static_cast<size_t>(ay.i0) * N];
        const double* s1 = &gS[static_cast<size_t>(ay.i1) * N];
        const double* p0 = &gP[static_cast<size_t>(ay.i0) * N];
        const double* p1 = &gP[static_cast<size_t>(ay.i1) * N];
        const uint16_t* bn = &gB[static_cast<size_t>(ay.nearest) * N];
        const uint8_t* inc = &gInc[static_cast<size_t>(ay.nearest) * N];
        for (int32_t px = 0; px < 256; ++px) {
            const Axis& ax = axis[px];
            double tx = ax.t, ty = ay.t;
            if ((tx < 0.0 || tx > 1.0 || ty < 0.0 || ty > 1.0) &&
                (r0[ax.i0] <= kNoCoverageRsrp || r0[ax.i1] <= kNoCoverageRsrp || r1[ax.i0] <= kNoCoverageRsrp ||
                 r1[ax.i1] <= kNoCoverageRsrp)) {
                tx = clampd(tx, 0.0, 1.0);
                ty = clampd(ty, 0.0, 1.0);
            }
            const double vr = (r0[ax.i0] * (1.0 - tx) + r0[ax.i1] * tx) * (1.0 - ty) +
                              (r1[ax.i0] * (1.0 - tx) + r1[ax.i1] * tx) * ty;
            const double vs = (s0[ax.i0] * (1.0 - tx) + s0[ax.i1] * tx) * (1.0 - ty) +
                              (s1[ax.i0] * (1.0 - tx) + s1[ax.i1] * tx) * ty;
            const double vp = (p0[ax.i0] * (1.0 - tx) + p0[ax.i1] * tx) * (1.0 - ty) +
                              (p1[ax.i0] * (1.0 - tx) + p1[ax.i1] * tx) * ty;
            const size_t q = static_cast<size_t>(py) * 256 + static_cast<size_t>(px);
            const uint8_t code = rsrpCode(vr);
            if (code != 0) {
                ++covered;
                incomplete += inc[ax.nearest];  // as `best`: the nearest grid sample's server
            }
            if (out.rsrp) out.rsrp[q] = code;
            if (out.sinr) out.sinr[q] = sinrCode(vs);
            if (out.prob) out.prob[q] = probCode(vp);
            if (out.best) out.best[q] = code == 0 ? kNoBest : bn[ax.nearest];
        }
    }
    if (out.terrainCoveredPx) *out.terrainCoveredPx = covered;
    if (out.terrainIncompletePx) *out.terrainIncompletePx = incomplete;
    return K;
}

}  // namespace sa
