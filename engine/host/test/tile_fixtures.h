// Shared fixtures of the tile tests: a tile vector with owned storage, its loader, output buffers, code comparison and
// the random scenarios of the parity tests. Test-only.
#ifndef SIGNAL_ATLAS_TEST_TILE_FIXTURES_H
#define SIGNAL_ATLAS_TEST_TILE_FIXTURES_H

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <string>
#include <vector>

#include "render/mercator.h"
#include "render/tile.h"
#include "vectors.h"

namespace sa_tile_test {

using sa_test::jsonArray;

// A tile vector: owns the structure-of-arrays storage that CarrierArrays points into.
struct Vec {
    sa::RenderParams params{};
    int32_t n = 0;
    int32_t evaluated = 0;
    std::vector<double> lat, lon;
    std::vector<float> hTx, az, tilt, eirpRe, freqMhz, hBw, fb, gain, rMaxKm;
    std::vector<uint8_t> tech;
    std::vector<int32_t> sectorKey, freqKey, hrpId;
    std::vector<int16_t> hrpPeak;
    std::vector<double> rsrp, sinr, prob, best;
    std::vector<const uint8_t*> terrainTable, terrainMask;  // per carrier (empty = no terrain)
    // Calibration (each empty = no array) and residual rasters (Calibration rules 4-6).
    std::vector<float> calOffsetDb, calSlope, calDmedKm, calSigmaScale;
    std::vector<uint8_t> network;
    std::vector<uint8_t> area;  // per-carrier area class (rule 5, amended 2026-10-24); empty = no array
    std::vector<std::vector<int8_t>> rasterCodes;
    std::vector<uint32_t> rasterMask;
    std::vector<sa::ResidualRaster> rasters;  // rebuilt by params()
    // Rule 5a building grid (empty cover = none).
    double bldSouth = 0.0, bldWest = 0.0;
    int32_t bldRows = 0, bldCols = 0;
    std::vector<uint8_t> bldCover, bldHeight;
    sa::BuildingGrid bld{};

    // `p` with the building grid attached (valid while this Vec is alive and unchanged).
    sa::RenderParams withBuildings(sa::RenderParams p) const {
        if (bldCover.empty()) return p;
        const_cast<Vec*>(this)->bld = {bldSouth, bldWest, 0.01, bldRows, bldCols, bldCover.data(), bldHeight.data()};
        p.buildings = &bld;
        return p;
    }

    template <typename T>
    static const T* ptr(const std::vector<T>& v) { return v.empty() ? nullptr : v.data(); }

    sa::CarrierArrays arrays() const {
        sa::CarrierArrays c{n, lat.data(), lon.data(), hTx.data(), az.data(), tilt.data(), eirpRe.data(), freqMhz.data(),
                hBw.data(), fb.data(), gain.data(), rMaxKm.data(), tech.data(), sectorKey.data(), freqKey.data(),
                hrpId.data(), hrpPeak.data(), ptr(terrainTable), ptr(terrainMask)};
        c.calOffsetDb = ptr(calOffsetDb);
        c.calSlope = ptr(calSlope);
        c.calDmedKm = ptr(calDmedKm);
        c.calSigmaScale = ptr(calSigmaScale);
        c.network = ptr(network);
        c.area = ptr(area);
        return c;
    }

    // `params` with the residual rasters attached (valid while this Vec is alive and unchanged).
    sa::RenderParams withRasters() {
        rasters.clear();
        for (size_t r = 0; r < rasterCodes.size(); ++r) rasters.push_back({rasterCodes[r].data(), rasterMask[r]});
        sa::RenderParams q = params;
        q.residuals = rasters.empty() ? nullptr : rasters.data();
        q.residualCount = static_cast<int32_t>(rasters.size());
        return q;
    }
};

template <typename T>
inline std::vector<T> as(const std::vector<double>& v) {
    std::vector<T> out(v.size());
    for (size_t i = 0; i < v.size(); ++i) out[i] = static_cast<T>(v[i]);
    return out;
}

inline Vec loadVec(const std::string& file) {
    const std::string json = sa_test::readFile(file);
    Vec v;
    // Integer scalars; 0 when absent (a carriers-only vector such as the point query's).
    auto integer = [&json](const char* key) {
        const double d = sa_test::jsonNumber(json, key);
        return std::isfinite(d) ? static_cast<int32_t>(d) : 0;
    };
    v.params.z = integer("z");
    v.params.x = integer("x");
    v.params.y = integer("y");
    v.params.grid = integer("grid");
    const std::string area = sa_test::jsonString(json, "area");
    v.params.area = area == "DENSE_URBAN" ? sa::Area::DenseUrban
                    : area == "URBAN"     ? sa::Area::Urban
                    : area == "SUBURBAN"  ? sa::Area::Suburban
                                          : sa::Area::Rural;
    v.params.indoor = sa_test::jsonNumber(json, "indoor") == 1.0;
    v.params.thrDbm = static_cast<float>(sa_test::jsonNumber(json, "thrDbm"));
    v.n = integer("n");
    v.evaluated = integer("evaluated");
    v.lat = jsonArray(json, "lat");
    v.lon = jsonArray(json, "lon");
    v.hTx = as<float>(jsonArray(json, "hTx"));
    v.az = as<float>(jsonArray(json, "az"));  // null -> NaN
    v.tilt = as<float>(jsonArray(json, "tilt"));
    v.eirpRe = as<float>(jsonArray(json, "eirpRe"));
    v.freqMhz = as<float>(jsonArray(json, "freqMhz"));
    v.hBw = as<float>(jsonArray(json, "hBw"));
    v.fb = as<float>(jsonArray(json, "fb"));
    v.gain = as<float>(jsonArray(json, "gain"));
    v.rMaxKm = as<float>(jsonArray(json, "rMaxKm"));
    v.tech = as<uint8_t>(jsonArray(json, "tech"));
    v.sectorKey = as<int32_t>(jsonArray(json, "sectorKey"));
    v.freqKey = as<int32_t>(jsonArray(json, "freqKey"));
    v.hrpId = as<int32_t>(jsonArray(json, "hrpId"));
    v.hrpPeak = as<int16_t>(jsonArray(json, "hrpPeak"));
    v.calOffsetDb = as<float>(jsonArray(json, "calOffsetDb"));  // absent -> empty -> no array
    v.calSlope = as<float>(jsonArray(json, "calSlope"));
    v.calDmedKm = as<float>(jsonArray(json, "calDmedKm"));
    v.calSigmaScale = as<float>(jsonArray(json, "calSigmaScale"));
    v.network = as<uint8_t>(jsonArray(json, "network"));
    v.area = as<uint8_t>(jsonArray(json, "carrierArea"));  // absent -> empty -> the tile's class for every carrier
    for (const std::string& obj : sa_test::objectsStartingWith(json, "residuals", "{\"mask\"")) {
        const std::vector<uint8_t> raw = sa_test::base64Decode(sa_test::jsonString(obj, "codes"));
        v.rasterCodes.emplace_back(raw.begin(), raw.end());
        v.rasterMask.push_back(static_cast<uint32_t>(sa_test::jsonNumber(obj, "mask")));
    }
    v.bldCover = as<uint8_t>(jsonArray(json, "bldCover"));
    v.bldHeight = as<uint8_t>(jsonArray(json, "bldHeight"));
    if (!v.bldCover.empty()) {
        v.bldSouth = sa_test::jsonNumber(json, "bldSouth");
        v.bldWest = sa_test::jsonNumber(json, "bldWest");
        v.bldRows = integer("bldRows");
        v.bldCols = integer("bldCols");
    }
    v.rsrp = jsonArray(json, "rsrpCodes");
    v.sinr = jsonArray(json, "sinrCodes");
    v.prob = jsonArray(json, "probCodes");
    v.best = jsonArray(json, "best");
    return v;
}

struct Out {
    std::vector<uint8_t> rsrp = std::vector<uint8_t>(sa::kTilePixels, 0xAA);
    std::vector<uint8_t> sinr = std::vector<uint8_t>(sa::kTilePixels, 0xAA);
    std::vector<uint8_t> prob = std::vector<uint8_t>(sa::kTilePixels, 0xAA);
    std::vector<uint16_t> best = std::vector<uint16_t>(sa::kTilePixels, 0x1234);
    int32_t covered = -1, incomplete = -1;  // terrain statistics (-1 = not written)
    sa::TileOut view() { return {rsrp.data(), sinr.data(), prob.data(), best.data(), &covered, &incomplete}; }
};

inline const sa::PatternTable kNoPatterns{0, nullptr};

inline int32_t render(const Vec& v, Out& o, const volatile int32_t* cancel = nullptr, sa::PatternTable pat = kNoPatterns) {
    sa::TileOut view = o.view();
    return sa::renderTile(v.arrays(), pat, v.withBuildings(v.params), view, cancel);
}

// render() with the Vec's residual rasters attached.
inline int32_t renderWithRasters(Vec& v, Out& o, sa::PatternTable pat = kNoPatterns) {
    sa::TileOut view = o.view();
    return sa::renderTile(v.arrays(), pat, v.withBuildings(v.withRasters()), view, nullptr);
}

// Asserts every pixel is within +-1 of the reference and at most 0.1% differ (SINR: 2%, because the engine truncates the
// interference sum at 1e-3 of noise + interference, which lifts SINR by up to 0.0043 dB and so moves the codes of pixels
// that sit on a rounding edge; probability shares it since rule 8 was amended to include an SINR condition; RSRP and
// the best server are exact).
inline void compareCodes(const char* what, const std::vector<uint8_t>& got, const std::vector<double>& ref) {
    const double share = std::string(what) == "sinr" || std::string(what) == "prob" ? 0.02 : 0.001;
    ASSERT_EQ(ref.size(), got.size());
    size_t diff = 0;
    int worst = 0;
    for (size_t i = 0; i < ref.size(); ++i) {
        const int d = std::abs(static_cast<int>(got[i]) - static_cast<int>(ref[i]));
        if (d != 0) ++diff;
        worst = std::max(worst, d);
    }
    EXPECT_LE(worst, 1) << what << ": worst code difference";
    EXPECT_LE(static_cast<double>(diff), share * static_cast<double>(ref.size()))
        << what << ": " << diff << " of " << ref.size() << " pixels differ";
    std::printf("[ tile ] %-5s %zu / %zu pixels differ (worst %d)\n", what, diff, ref.size(), worst);
}

// A random scenario for the pruned-vs-brute-force parity tests (fixed seeds in the tests).
struct Scenario {
    Vec v;
    std::vector<uint8_t> patterns;
    int32_t patternCount = 0;
};

inline Scenario randomScenario(std::mt19937& rng, int index) {
    auto uni = [&](double lo, double hi) { return std::uniform_real_distribution<double>(lo, hi)(rng); };
    auto pick = [&](std::initializer_list<double> xs) { return *(xs.begin() + rng() % xs.size()); };
    Scenario sc;
    Vec& v = sc.v;
    v.params.z = 14;
    v.params.x = 14978 + static_cast<int32_t>(rng() % 3);
    v.params.y = 9908 + static_cast<int32_t>(rng() % 3);
    v.params.grid = index % 2 == 0 ? 128 : 256;
    v.params.area = static_cast<sa::Area>(rng() % 4);
    v.params.indoor = index % 3 == 0;
    v.params.thrDbm = -118.0f;
    // Patterns: sector-shaped with a random null band of deep attenuation.
    sc.patternCount = 6;
    sc.patterns.assign(static_cast<size_t>(sc.patternCount) * 360, 0);
    for (int p = 0; p < sc.patternCount; ++p) {
        const int nullAt = static_cast<int>(rng() % 360), nullWidth = 1 + static_cast<int>(rng() % 12);
        for (int k = 0; k < 360; ++k) {
            const double d = std::min(k, 360 - k);
            double q = std::min(12.0 * (d / 65.0) * (d / 65.0), 25.0) * 4.0;
            if (((k - nullAt + 360) % 360) < nullWidth) q = 255;
            sc.patterns[static_cast<size_t>(p) * 360 + static_cast<size_t>(k)] = static_cast<uint8_t>(q);
        }
    }
    const double cLat = sa::merc::tileLatDeg(v.params.y + 0.5, 14), cLon = sa::merc::tileLonDeg(v.params.x + 0.5, 14);
    const int n = 14 + static_cast<int>(rng() % 12);
    std::vector<std::pair<double, double>> sites;
    for (int s = 0; s < 4; ++s) sites.push_back({cLat + uni(-0.02, 0.02), cLon + uni(-0.02, 0.02)});
    sites.push_back({cLat + uni(-0.002, 0.002), cLon + uni(-0.002, 0.002)});  // inside the tile, within a block
    for (int i = 0; i < n; ++i) {
        const auto& site = sites[static_cast<size_t>(i) % sites.size()];
        const float f = static_cast<float>(pick({700, 780, 1800, 2100, 2600, 3500, 3500, 26000, 28000}));
        const float h = static_cast<float>(pick({3, 8, 15, 25, 45, 80}));
        const float eirpRe = static_cast<float>(uni(-12, 15));
        v.lat.push_back(site.first);
        v.lon.push_back(site.second);
        v.hTx.push_back(h);
        v.az.push_back(rng() % 6 == 0 ? std::nanf("")
                                      : static_cast<float>(pick({0, 0.5, 1, 359, 359.5, 360, 90, 180, 270, 45})));
        v.tilt.push_back(static_cast<float>(pick({0, 0, 2, -4, 6, 12})));
        v.eirpRe.push_back(eirpRe);
        v.freqMhz.push_back(f);
        v.hBw.push_back(rng() % 4 == 0 ? std::nanf("") : static_cast<float>(pick({30, 65, 90})));
        v.fb.push_back(rng() % 4 == 0 ? std::nanf("") : static_cast<float>(pick({20, 25, 30})));
        v.gain.push_back(rng() % 4 == 0 ? std::nanf("") : static_cast<float>(pick({12, 16, 19})));
        v.rMaxKm.push_back(sa::cullingRangeKm(eirpRe, f, h));
        v.tech.push_back(f > 2500 && rng() % 5 != 0 ? 1 : 0);
        v.sectorKey.push_back(i / 2);
        v.freqKey.push_back(static_cast<int32_t>(1 + rng() % 3) * 1000000 + static_cast<int32_t>(f) / 10 % 5);
        const bool hasHrp = rng() % 3 != 0;
        v.hrpId.push_back(hasHrp ? 1 + static_cast<int32_t>(rng() % 6) : 0);
        v.hrpPeak.push_back(static_cast<int16_t>(rng() % 360));
    }
    v.n = n;
    // Every third scenario has a building grid (rule 5a): random cells of every class around the tile, some without data.
    if (index % 3 != 2) {
        v.bldSouth = std::floor((cLat - 0.03) * 100.0) / 100.0;
        v.bldWest = std::floor((cLon - 0.03) * 100.0) / 100.0;
        v.bldRows = 7;
        v.bldCols = 7;
        for (int k = 0; k < 49; ++k) {
            v.bldCover.push_back(rng() % 9 == 0 ? 255 : static_cast<uint8_t>(pick({0, 10, 30, 60, 100, 160})));
            v.bldHeight.push_back(static_cast<uint8_t>(pick({0, 8, 20, 30, 50, 90})));
        }
    }
    return sc;
}

}  // namespace sa_tile_test

#endif  // SIGNAL_ATLAS_TEST_TILE_FIXTURES_H
