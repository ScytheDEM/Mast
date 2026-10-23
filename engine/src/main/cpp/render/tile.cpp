#include "render/tile.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstring>
#include <limits>
#include <vector>

#include "model/antenna.h"
#include "model/eirp.h"
#include "model/limits.h"
#include "model/pathloss.h"
#include "model/stats.h"
#include "render/mercator.h"

namespace sa {
namespace {

using merc::kDegToRad;
using merc::kEarthRadiusM;
using merc::kRadToDeg;

constexpr double kNoCoverageSinrDb = -20.0;
constexpr double kLoad = 0.5;              // rule 8
constexpr int32_t kBlocks = 16;            // the grid is split into kBlocks x kBlocks blocks for the RSRP bounds
constexpr double kBoundSlackDb = 0.01;     // keeps the RSRP upper bound safe against rounding
constexpr double kInterferenceTol = 1e-5;  // relative error allowed when truncating the interference sum
constexpr int32_t kMaxZ = 30;

struct Carrier {
    PreparedAntenna ant;
    PreparedPathLoss pl;
    double eirpRe;
    double hd2;      // (h_tx - h_rx)^2, m^2
    double noiseMw;  // thermal noise for this carrier's sub-carrier spacing, mW
    double hrpMinDb; // smallest attenuation of the HRP (dB), 0 without one
    int32_t freqKey, sectorKey, orig, group;
};

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
    const int64_t tiles = int64_t{1} << p.z;
    if (p.x < 0 || p.y < 0 || p.x >= tiles || p.y >= tiles) return kRenderInvalid;

    // ---- Culling (rule 9): R_max against the nearest point of the tile's lat/lon box.
    const double south = merc::tileLatDeg(p.y + 1.0, p.z), north = merc::tileLatDeg(p.y, p.z);
    const double west = merc::tileLonDeg(p.x, p.z), east = merc::tileLonDeg(p.x + 1.0, p.z);
    std::vector<int32_t> alive;
    alive.reserve(static_cast<size_t>(c.n));
    for (int32_t i = 0; i < c.n; ++i) {
        const double lat = c.lat[i], lon = c.lon[i];
        if (!std::isfinite(lat) || !std::isfinite(lon) || std::fabs(lat) > 90.0 || std::fabs(lon) > 540.0) continue;
        if (!std::isfinite(c.eirpRe[i])) continue;
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
        const uint8_t* hrp = nullptr;
        int16_t peak = 0;
        if (c.hrpId && patterns.att && c.hrpId[i] >= 1 && c.hrpId[i] <= patterns.count) {
            hrp = patterns.att + static_cast<size_t>(c.hrpId[i] - 1) * 360;
            peak = c.hrpPeak ? c.hrpPeak[i] : int16_t{0};
        }
        const AntennaParams ap{c.az[i], c.hBw[i], c.fb[i], c.gain[i], c.tilt[i], c.hTx[i], c.freqMhz[i], hrp, peak};
        cr.ant = prepareAntenna(ap);
        cr.pl = preparePathLoss(c.freqMhz[i], c.hTx[i], p.area);
        cr.eirpRe = c.eirpRe[i];
        const double dh = sanitizeHeight(c.hTx[i]) - kHRx;
        cr.hd2 = dh * dh;
        cr.hrpMinDb = 0.0;
        if (hrp != nullptr) {
            uint8_t lo = 255;
            for (int32_t q = 0; q < 360; ++q) lo = std::min(lo, hrp[q]);
            cr.hrpMinDb = lo * 0.25;
        }
        const Tech tech = c.tech[i] == 1 ? Tech::NR : Tech::LTE;
        const double scsHz = scsKhz(tech, c.freqMhz[i]) * 1000.0;
        cr.noiseMw = std::pow(10.0, (-174.0 + 10.0 * std::log10(scsHz) + 7.0) / 10.0);
        cr.freqKey = c.freqKey[i];
        cr.sectorKey = c.sectorKey[i];
        cr.orig = i;
        cr.group = 0;
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

    // ---- Grid evaluation (rules 8-9), branch and bound per block of grid points.
    //
    // For a block of B x B sample points every carrier gets an upper bound on its RSRP anywhere in the block:
    // eirpRe - PL(shortest possible distance) - (a lower bound of the antenna attenuation over the block; PL grows
    // with distance). Carriers are visited in decreasing bound; once a bound falls below the best RSRP found at the point,
    // no remaining carrier can be the best server, so the exact (expensive) antenna + path loss runs only for the
    // few contenders. The best server and its RSRP are therefore exactly the reference's. The interference sum over
    // the best server's frequency group is visited in the same order and stops once the rest (bounded by the group
    // size times the next bound) is under 1e-5 of noise + interference, i.e. under 5e-5 dB of SINR.
    const int32_t N = p.grid;
    const int32_t B = N / kBlocks;
    const size_t NN = static_cast<size_t>(N) * static_cast<size_t>(N);
    std::vector<double> gR(NN), gS(NN), gP(NN);
    std::vector<uint16_t> gB(NN);

    std::vector<double> colSh(static_cast<size_t>(N)), colCh(static_cast<size_t>(N));
    std::vector<double> colLonDeg(static_cast<size_t>(N)), rowLatDeg(static_cast<size_t>(N));
    for (int32_t i = 0; i < N; ++i) {
        colLonDeg[static_cast<size_t>(i)] = merc::tileLonDeg(p.x + (i + 0.5) / N, p.z);
        const double lr = colLonDeg[static_cast<size_t>(i)] * kDegToRad;
        colSh[static_cast<size_t>(i)] = std::sin(lr / 2.0);
        colCh[static_cast<size_t>(i)] = std::cos(lr / 2.0);
    }
    for (int32_t j = 0; j < N; ++j) rowLatDeg[static_cast<size_t>(j)] = merc::tileLatDeg(p.y + (j + 0.5) / N, p.z);

    // Per block of the current block row: bound (dBm), bound as 0.5 * mW, and the visiting order.
    std::vector<double> ub(static_cast<size_t>(kBlocks) * KK), ubLin(static_cast<size_t>(kBlocks) * KK);
    std::vector<int32_t> ord(static_cast<size_t>(kBlocks) * KK);

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
                    const double ratio = std::sin(radius / kEarthRadiusM) / std::sin(dc / kEarthRadiusM);
                    halfWidth = std::asin(std::fmin(1.0, ratio)) * kRadToDeg * 1.01 + 0.01;
                }
                const Site& st = sites[static_cast<size_t>(s)];
                for (int32_t k = st.first; k < st.first + st.count; ++k) {
                    const Carrier& cr = car[static_cast<size_t>(k)];
                    const float d3Km = static_cast<float>(std::sqrt(dlo * dlo + cr.hd2) / 1000.0);
                    const PathLoss pl = indoor ? cr.pl.evaluateIndoor(d3Km) : cr.pl.evaluate(d3Km);
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
                    bub[k] = cr.eirpRe - aBound - pl.plDb + kBoundSlackDb;
                    if (p.debugNoBounds) bub[k] = std::numeric_limits<double>::infinity();
                    blin[k] = kLoad * std::pow(10.0, bub[k] / 10.0);
                    bord[k] = k;
                }
            }
            std::sort(bord, bord + K, [bub](int32_t a, int32_t b) { return bub[a] != bub[b] ? bub[a] > bub[b] : a < b; });
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

            for (int32_t i = 0; i < N; ++i) {
                const size_t bi = static_cast<size_t>(i / B);
                const double* bub = &ub[bi * KK];
                const double* blin = &ubLin[bi * KK];
                const int32_t* bord = &ord[bi * KK];
                const double cs = colSh[static_cast<size_t>(i)], cc = colCh[static_cast<size_t>(i)];
                ++stamp;
                double bestR = -std::numeric_limits<double>::infinity();
                float bestSigma = 8.0f;
                int32_t bestK = -1, nEval = 0;

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
                        sd2[s] = 2.0 * kEarthRadiusM * std::asin(std::sqrt(a));
                        const double by = 2.0 * sh * ch * cosP2;
                        const double bx = rowXa[s] - rowXb[s] * (1.0 - 2.0 * sh * sh);
                        double brg = std::atan2(by, bx) * kRadToDeg;
                        if (brg < 0.0) brg += 360.0;
                        sbrg[s] = brg;
                    }
                    const double d2 = sd2[s];
                    const float aDb = cr.ant.evaluate(static_cast<float>(sbrg[s]), static_cast<float>(d2));
                    const float d3Km = static_cast<float>(std::sqrt(d2 * d2 + cr.hd2) / 1000.0);
                    const PathLoss pl = indoor ? cr.pl.evaluateIndoor(d3Km) : cr.pl.evaluate(d3Km);
                    const double r = cr.eirpRe - aDb - pl.plDb;
                    evalStamp[static_cast<size_t>(k)] = stamp;
                    rs[static_cast<size_t>(k)] = r;
                    evk[static_cast<size_t>(nEval++)] = k;
                    if (bestK < 0 || r > bestR || (r == bestR && cr.orig < car[static_cast<size_t>(bestK)].orig)) {
                        bestR = r;
                        bestK = k;
                        bestSigma = pl.sigmaDb;
                    }
                };

                for (int32_t m = 0; m < K; ++m) {
                    const int32_t k = bord[m];
                    if (bestK >= 0 && bub[k] < bestR) break;  // nothing after this one can beat the best
                    evaluate(k);
                }

                double sinr = kNoCoverageSinrDb, prob = 0.0, rsrp = -200.0;
                uint16_t bestIdx = kNoBest;
                if (bestK >= 0) {
                    const Carrier& b = car[static_cast<size_t>(bestK)];
                    const int32_t g = b.group;
                    const double groupSize = groupFirst[static_cast<size_t>(g) + 1] - groupFirst[static_cast<size_t>(g)];
                    double interf = 0.0;
                    for (int32_t e = 0; e < nEval; ++e) {
                        const int32_t k = evk[static_cast<size_t>(e)];
                        const Carrier& o = car[static_cast<size_t>(k)];
                        if (o.group == g && o.sectorKey != b.sectorKey) {
                            interf += kLoad * std::pow(10.0, rs[static_cast<size_t>(k)] / 10.0);
                        }
                    }
                    if (groupSize > 1.0) {
                        for (int32_t m = 0; m < K; ++m) {
                            const int32_t k = bord[m];
                            const Carrier& o = car[static_cast<size_t>(k)];
                            if (o.group != g || evalStamp[static_cast<size_t>(k)] == stamp || o.sectorKey == b.sectorKey) continue;
                            if (blin[k] * groupSize < kInterferenceTol * (b.noiseMw + interf)) break;
                            evaluate(k);
                            interf += kLoad * std::pow(10.0, rs[static_cast<size_t>(k)] / 10.0);
                        }
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
            }
        }
    }

    // ---- Quantise (and bilinearly upsample 128 -> 256, floats before codes; `best` takes the nearest sample).
    if (N == 256) {
        for (size_t q = 0; q < NN; ++q) {
            const uint8_t code = rsrpCode(gR[q]);
            if (out.rsrp) out.rsrp[q] = code;
            if (out.sinr) out.sinr[q] = sinrCode(gS[q]);
            if (out.prob) out.prob[q] = probCode(gP[q]);
            if (out.best) out.best[q] = code == 0 ? kNoBest : gB[q];
        }
        return K;
    }
    Axis axis[256];
    const double scale = N / 256.0;
    for (int32_t q = 0; q < 256; ++q) {
        const double g = clampd((q + 0.5) * scale - 0.5, 0.0, N - 1.0);
        const int32_t i0 = static_cast<int32_t>(std::floor(g));
        axis[q] = {i0, std::min(i0 + 1, N - 1), std::min(static_cast<int32_t>(std::floor(g + 0.5)), N - 1), g - i0};
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
        for (int32_t px = 0; px < 256; ++px) {
            const Axis& ax = axis[px];
            const double tx = ax.t, ty = ay.t;
            const double vr = (r0[ax.i0] * (1.0 - tx) + r0[ax.i1] * tx) * (1.0 - ty) +
                              (r1[ax.i0] * (1.0 - tx) + r1[ax.i1] * tx) * ty;
            const double vs = (s0[ax.i0] * (1.0 - tx) + s0[ax.i1] * tx) * (1.0 - ty) +
                              (s1[ax.i0] * (1.0 - tx) + s1[ax.i1] * tx) * ty;
            const double vp = (p0[ax.i0] * (1.0 - tx) + p0[ax.i1] * tx) * (1.0 - ty) +
                              (p1[ax.i0] * (1.0 - tx) + p1[ax.i1] * tx) * ty;
            const size_t q = static_cast<size_t>(py) * 256 + static_cast<size_t>(px);
            const uint8_t code = rsrpCode(vr);
            if (out.rsrp) out.rsrp[q] = code;
            if (out.sinr) out.sinr[q] = sinrCode(vs);
            if (out.prob) out.prob[q] = probCode(vp);
            if (out.best) out.best[q] = code == 0 ? kNoBest : bn[ax.nearest];
        }
    }
    return K;
}

}  // namespace sa
