// Task 6: tile renderer against the Python reference tiles, plus edge cases.
#include <gtest/gtest.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <thread>
#include <vector>

#include <random>

#include "render/mercator.h"
#include "render/tile.h"
#include "vectors.h"

namespace {

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

    sa::CarrierArrays arrays() const {
        return {n, lat.data(), lon.data(), hTx.data(), az.data(), tilt.data(), eirpRe.data(), freqMhz.data(),
                hBw.data(), fb.data(), gain.data(), rMaxKm.data(), tech.data(), sectorKey.data(), freqKey.data(),
                hrpId.data(), hrpPeak.data()};
    }
};

template <typename T>
std::vector<T> as(const std::vector<double>& v) {
    std::vector<T> out(v.size());
    for (size_t i = 0; i < v.size(); ++i) out[i] = static_cast<T>(v[i]);
    return out;
}

Vec loadVec(const std::string& file) {
    const std::string json = sa_test::readFile(file);
    Vec v;
    v.params.z = static_cast<int32_t>(sa_test::jsonNumber(json, "z"));
    v.params.x = static_cast<int32_t>(sa_test::jsonNumber(json, "x"));
    v.params.y = static_cast<int32_t>(sa_test::jsonNumber(json, "y"));
    v.params.grid = static_cast<int32_t>(sa_test::jsonNumber(json, "grid"));
    const std::string area = sa_test::jsonString(json, "area");
    v.params.area = area == "DENSE_URBAN" ? sa::Area::DenseUrban
                    : area == "URBAN"     ? sa::Area::Urban
                    : area == "SUBURBAN"  ? sa::Area::Suburban
                                          : sa::Area::Rural;
    v.params.indoor = sa_test::jsonNumber(json, "indoor") == 1.0;
    v.params.thrDbm = static_cast<float>(sa_test::jsonNumber(json, "thrDbm"));
    v.n = static_cast<int32_t>(sa_test::jsonNumber(json, "n"));
    v.evaluated = static_cast<int32_t>(sa_test::jsonNumber(json, "evaluated"));
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
    sa::TileOut view() { return {rsrp.data(), sinr.data(), prob.data(), best.data()}; }
};

const sa::PatternTable kNoPatterns{0, nullptr};

int32_t render(const Vec& v, Out& o, const volatile int32_t* cancel = nullptr, sa::PatternTable pat = kNoPatterns) {
    sa::TileOut view = o.view();
    return sa::renderTile(v.arrays(), pat, v.params, view, cancel);
}

// Asserts every pixel is within +-1 of the reference and at most 0.1% differ.
void compareCodes(const char* what, const std::vector<uint8_t>& got, const std::vector<double>& ref) {
    ASSERT_EQ(ref.size(), got.size());
    size_t diff = 0;
    int worst = 0;
    for (size_t i = 0; i < ref.size(); ++i) {
        const int d = std::abs(static_cast<int>(got[i]) - static_cast<int>(ref[i]));
        if (d != 0) ++diff;
        worst = std::max(worst, d);
    }
    EXPECT_LE(worst, 1) << what << ": worst code difference";
    EXPECT_LE(static_cast<double>(diff), 0.001 * static_cast<double>(ref.size()))
        << what << ": " << diff << " of " << ref.size() << " pixels differ";
    std::printf("[ tile ] %-5s %zu / %zu pixels differ (worst %d)\n", what, diff, ref.size(), worst);
}

class TileVectorTest : public ::testing::TestWithParam<const char*> {};

TEST_P(TileVectorTest, MatchesReference) {
    const Vec v = loadVec(GetParam());
    ASSERT_EQ(65536u, v.rsrp.size());
    ASSERT_EQ(65536u, v.sinr.size());
    ASSERT_EQ(65536u, v.prob.size());
    ASSERT_EQ(65536u, v.best.size());
    ASSERT_GT(v.n, 0);
    ASSERT_EQ(static_cast<size_t>(v.n), v.lat.size());
    ASSERT_EQ(static_cast<size_t>(v.n), v.rMaxKm.size());
    Out o;
    EXPECT_EQ(v.evaluated, render(v, o));
    compareCodes("rsrp", o.rsrp, v.rsrp);
    compareCodes("sinr", o.sinr, v.sinr);
    compareCodes("prob", o.prob, v.prob);

    // best: equal wherever the reference RSRP code is not 0, and kNoBest where our code is 0 (at most 0.1% apart).
    size_t coveredMismatch = 0, covered = 0;
    for (size_t i = 0; i < v.best.size(); ++i) {
        if (v.rsrp[i] != 0.0) ++covered;
        if (o.rsrp[i] != 0) {
            ASSERT_LT(o.best[i], v.n) << i;
        } else {
            ASSERT_EQ(sa::kNoBest, o.best[i]) << i;
        }
        if (v.rsrp[i] != 0.0 && o.rsrp[i] != 0 && static_cast<double>(o.best[i]) != v.best[i]) ++coveredMismatch;
    }
    std::printf("[ tile ] best  %zu of %zu covered pixels differ\n", coveredMismatch, covered);
    EXPECT_LE(static_cast<double>(coveredMismatch), 0.001 * static_cast<double>(v.best.size()));
}

INSTANTIATE_TEST_SUITE_P(Vectors, TileVectorTest,
                         ::testing::Values("tile_canberra_z13.json", "tile_edge_z12.json",
                                           "tile_canberra_z13_urban_indoor_hi.json",
                                           "tile_canberra_z13_urban_indoor_mid.json"));

TEST(Tile, NoCarriersInRangeIsAllZeroAndFast) {
    Vec v = loadVec("tile_canberra_z13.json");
    // Simpson Desert at z10: over 1 000 km from every carrier, whose R_max is at most 80 km.
    const double lat = -25.8, lon = 137.0;
    const int32_t z = 10;
    v.params.z = z;
    v.params.x = static_cast<int32_t>(std::floor((lon + 180.0) / 360.0 * (1 << z)));
    v.params.y = static_cast<int32_t>(std::floor(
        (1.0 - std::asinh(std::tan(lat * sa::merc::kDegToRad)) / sa::merc::kPi) / 2.0 * (1 << z)));
    Out o;
    double best = 1e9;
    for (int rep = 0; rep < 5; ++rep) {
        o = Out();
        const auto t0 = std::chrono::steady_clock::now();
        const int32_t r = render(v, o);
        const auto t1 = std::chrono::steady_clock::now();
        ASSERT_EQ(0, r);
        best = std::min(best, std::chrono::duration<double, std::milli>(t1 - t0).count());
    }
    EXPECT_LT(best, 20.0) << "ms (loose bound; the work is a handful of haversines and a zero fill)";
    for (int i = 0; i < sa::kTilePixels; ++i) {
        ASSERT_EQ(0, o.rsrp[i]);
        ASSERT_EQ(0, o.sinr[i]);
        ASSERT_EQ(0, o.prob[i]);
        ASSERT_EQ(sa::kNoBest, o.best[i]);
    }
}

TEST(Tile, ZeroCarriersIsAllZero) {
    Vec v = loadVec("tile_canberra_z13.json");
    v.n = 0;
    Out o;
    EXPECT_EQ(0, render(v, o));
    for (int i = 0; i < sa::kTilePixels; ++i) {
        ASSERT_EQ(0, o.rsrp[i]);
        ASSERT_EQ(sa::kNoBest, o.best[i]);
    }
}

TEST(Tile, CancelledBeforeStartReturnsMinusOneAndZeroes) {
    const Vec v = loadVec("tile_canberra_z13.json");
    const volatile int32_t flag = 1;
    Out o;
    EXPECT_EQ(sa::kRenderCancelled, render(v, o, &flag));
    for (int i = 0; i < sa::kTilePixels; ++i) {
        ASSERT_EQ(0, o.rsrp[i]);
        ASSERT_EQ(0, o.sinr[i]);
        ASSERT_EQ(0, o.prob[i]);
        ASSERT_EQ(sa::kNoBest, o.best[i]);
    }
}

TEST(Tile, CancelMidRenderReturnsMinusOnePromptly) {
    Vec v = loadVec("tile_canberra_z13.json");
    // Copies of the carriers at shifted positions (grid 256) so the render takes long enough to cancel mid-way.
    const size_t base = v.lat.size();
    for (int copy = 1; copy <= 3; ++copy) {
        for (size_t i = 0; i < base; ++i) {
            v.lat.push_back(v.lat[i] + 0.001 * copy);
            v.lon.push_back(v.lon[i] + 0.001 * copy);
            v.hTx.push_back(v.hTx[i]);
            v.az.push_back(v.az[i]);
            v.tilt.push_back(v.tilt[i]);
            v.eirpRe.push_back(v.eirpRe[i]);
            v.freqMhz.push_back(v.freqMhz[i]);
            v.hBw.push_back(v.hBw[i]);
            v.fb.push_back(v.fb[i]);
            v.gain.push_back(v.gain[i]);
            v.rMaxKm.push_back(v.rMaxKm[i]);
            v.tech.push_back(v.tech[i]);
            v.sectorKey.push_back(v.sectorKey[i] + 100000 * copy);
            v.freqKey.push_back(v.freqKey[i]);
            v.hrpId.push_back(0);
            v.hrpPeak.push_back(0);
        }
    }
    v.n = static_cast<int32_t>(v.lat.size());
    v.params.grid = 256;
    volatile int32_t flag = 0;
    Out o;
    std::atomic<int32_t> result{12345};
    std::thread worker([&] { result = render(v, o, &flag); });
    std::this_thread::sleep_for(std::chrono::milliseconds(3));
    flag = 1;
    const auto t0 = std::chrono::steady_clock::now();
    worker.join();
    const double afterMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    EXPECT_EQ(sa::kRenderCancelled, result.load());
    EXPECT_LT(afterMs, 1000.0) << "time from cancel to return (generous; one grid row is well under a ms)";
    for (int i = 0; i < sa::kTilePixels; ++i) {
        ASSERT_EQ(0, o.rsrp[i]);
        ASSERT_EQ(sa::kNoBest, o.best[i]);
    }
}

TEST(Tile, NonFiniteCarriersAreSkipped) {
    Vec v = loadVec("tile_canberra_z13.json");
    v.lat[0] = std::nan("");         // NaN latitude
    v.lon[1] = INFINITY;             // infinite longitude
    v.eirpRe[2] = std::nanf("");     // NaN EIRP
    Out o;
    EXPECT_EQ(v.n - 3, render(v, o));
    for (int i = 0; i < sa::kTilePixels; ++i) {
        ASSERT_NE(0, o.rsrp[i]);  // the remaining carriers still cover the tile
        ASSERT_GE(o.best[i], 3);  // the skipped carriers 0..2 are never best
        ASSERT_LT(o.best[i], v.n);
    }
}

TEST(Tile, InvalidArgumentsAreRejected) {
    Vec v = loadVec("tile_canberra_z13.json");
    Out o;
    v.params.grid = 100;
    EXPECT_EQ(sa::kRenderInvalid, render(v, o));
    v.params.grid = 128;
    v.params.x = 1 << 20;
    EXPECT_EQ(sa::kRenderInvalid, render(v, o));
}

TEST(Tile, HrpIdOutOfRangeIsIgnoredAndHrpIsApplied) {
    Vec v = loadVec("tile_canberra_z13.json");
    Out plain;
    ASSERT_EQ(v.evaluated, render(v, plain));

    // Out-of-range ids (above count, negative) behave exactly like no HRP.
    for (size_t i = 0; i < v.hrpId.size(); ++i) v.hrpId[i] = (i % 2 == 0) ? 999 : -5;
    Out bad;
    EXPECT_EQ(v.evaluated, render(v, bad));
    EXPECT_EQ(plain.rsrp, bad.rsrp);
    EXPECT_EQ(plain.best, bad.best);

    // A valid all-zero pattern means "no horizontal attenuation": identical to the same (omni) carriers without one.
    std::vector<uint8_t> zeros(360, 0);
    const sa::PatternTable table{1, zeros.data()};
    for (auto& id : v.hrpId) id = 1;
    for (auto& a : v.az) a = std::nanf("");
    Out hrp;
    EXPECT_EQ(v.evaluated, render(v, hrp, nullptr, table));
    std::fill(v.hrpId.begin(), v.hrpId.end(), 0);
    Out omni;
    EXPECT_EQ(v.evaluated, render(v, omni));
    EXPECT_EQ(omni.rsrp, hrp.rsrp);
    EXPECT_EQ(omni.best, hrp.best);
    EXPECT_NE(plain.rsrp, hrp.rsrp);  // and the sectored antennas really differ
}

TEST(Tile, Grid256RendersAndAgreesWithGrid128) {
    Vec v = loadVec("tile_canberra_z13.json");
    Out lo;
    ASSERT_EQ(v.evaluated, render(v, lo));
    v.params.grid = 256;
    Out hi;
    ASSERT_EQ(v.evaluated, render(v, hi));
    size_t far = 0;
    for (int i = 0; i < sa::kTilePixels; ++i) {
        if (std::abs(static_cast<int>(hi.rsrp[i]) - static_cast<int>(lo.rsrp[i])) > 2) ++far;
    }
    EXPECT_LT(far, 100u) << "the smooth rural field should differ little between grids";
}

// Randomised parity: the pruned render must equal the brute-force render (debugNoBounds). Fixed seed.
struct Scenario {
    Vec v;
    std::vector<uint8_t> patterns;
    int32_t patternCount = 0;
};

Scenario randomScenario(std::mt19937& rng, int index) {
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
    return sc;
}

TEST(Tile, PrunedRenderEqualsBruteForce) {
    std::mt19937 rng(20261023);
    for (int t = 0; t < 20; ++t) {
        Scenario sc = randomScenario(rng, t);
        const sa::PatternTable table{sc.patternCount, sc.patterns.data()};
        Out fast, brute;
        const int32_t a = render(sc.v, fast, nullptr, table);
        sc.v.params.debugNoBounds = true;
        const int32_t b = render(sc.v, brute, nullptr, table);
        ASSERT_EQ(a, b) << t;
        ASSERT_GT(a, 0) << t;
        size_t covered = 0;
        for (int i = 0; i < sa::kTilePixels; ++i) {
            ASSERT_EQ(brute.rsrp[i], fast.rsrp[i]) << "tile " << t << " px " << i;
            ASSERT_EQ(brute.prob[i], fast.prob[i]) << "tile " << t << " px " << i;
            ASSERT_EQ(brute.best[i], fast.best[i]) << "tile " << t << " px " << i;
            ASSERT_LE(std::abs(static_cast<int>(brute.sinr[i]) - static_cast<int>(fast.sinr[i])), 1)
                << "tile " << t << " px " << i;
            if (brute.rsrp[i] != 0) ++covered;
        }
        EXPECT_GT(covered, 0u) << "tile " << t << " is empty";
    }
}

}  // namespace
