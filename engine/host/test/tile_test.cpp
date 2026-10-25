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
#include "render/point.h"
#include "render/tile.h"
#include "terrain/polar_table.h"
#include "tile_fixtures.h"
#include "vectors.h"

namespace {

using sa_test::jsonArray;
using namespace sa_tile_test;

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
                                           "tile_canberra_z13_urban_indoor_mid.json",
                                           "tile_canberra_z13_carrier_area.json",
                                           "tile_canberra_z13_buildings.json"));

// Rule 5a (M3b): the building grid changes the Hata-family receiver terms per pixel; the point query uses the same
// class at the same point, and a tile without the grid is the plain per-carrier tile.
TEST(TileBuildings, PointQueryUsesTheSameEnvironmentAndNoGridIsUnchanged) {
    Vec v = loadVec("tile_canberra_z13_buildings.json");
    ASSERT_FALSE(v.bldCover.empty());
    v.params.grid = 256;
    Out o;
    ASSERT_EQ(v.n, render(v, o));
    const sa::RenderParams withGrid = v.withBuildings(v.params);
    std::vector<float> got(static_cast<size_t>(v.n));
    int differentClass = 0;
    for (int32_t j = 5; j < 256; j += 37) {
        for (int32_t i = 3; i < 256; i += 41) {
            const double lat = sa::merc::tileLatDeg(v.params.y + (j + 0.5) / 256.0, v.params.z);
            const double lon = sa::merc::tileLonDeg(v.params.x + (i + 0.5) / 256.0, v.params.z);
            differentClass += sa::environmentAt(withGrid.buildings, lat, lon) != sa::kNoEnv;
            ASSERT_EQ(v.n, sa::predictPoint(v.arrays(), kNoPatterns, lat, lon, v.params.area, false, got.data(), withGrid.buildings));
            int32_t best = 0;
            for (int32_t k = 1; k < v.n; ++k) {
                if (got[static_cast<size_t>(k)] > got[static_cast<size_t>(best)]) best = k;
            }
            const size_t px = static_cast<size_t>(j) * 256 + static_cast<size_t>(i);
            EXPECT_NEAR(std::floor((got[static_cast<size_t>(best)] + 140.0) * 2.0 + 0.5), o.rsrp[px], 1.0) << i << "," << j;
        }
    }
    EXPECT_GT(differentClass, 10);
    Vec plain = loadVec("tile_canberra_z13_carrier_area.json");
    Vec stripped = loadVec("tile_canberra_z13_buildings.json");
    stripped.bldCover.clear();
    Out a, b;
    render(plain, a);
    render(stripped, b);
    EXPECT_EQ(a.rsrp, b.rsrp);
}

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


// ---------------------------------------------------------------- terrain (Terrain rules 5-6)

// Owns decoded polar tables; `assign` points the carriers of a Vec at them.
struct Tables {
    std::vector<std::vector<uint8_t>> codes, mask;
    void assign(Vec& v, const std::vector<int>& carrierTable) const {
        v.terrainTable.assign(carrierTable.size(), nullptr);
        v.terrainMask.assign(carrierTable.size(), nullptr);
        for (size_t i = 0; i < carrierTable.size(); ++i) {
            if (carrierTable[i] < 0) continue;
            v.terrainTable[i] = codes[static_cast<size_t>(carrierTable[i])].data();
            v.terrainMask[i] = mask[static_cast<size_t>(carrierTable[i])].data();
        }
    }
};

TEST(TileTerrain, CanberraTerrainTileMatchesReference) {
    const std::string file = "terrain/tile_canberra_terrain_z12.json";
    Vec v = loadVec(file);
    const std::string json = sa_test::readFile(file);
    ASSERT_EQ(65536u, v.rsrp.size());
    Tables t;
    for (const std::string& obj : sa_test::objectsStartingWith(json, "tables", "{\"siteLat\"")) {
        t.codes.push_back(sa_test::base64Decode(sa_test::jsonString(obj, "codes")));
        t.mask.push_back(sa_test::base64Decode(sa_test::jsonString(obj, "mask")));
        ASSERT_EQ(static_cast<size_t>(sa::kPolarTableCells), t.codes.back().size());
        ASSERT_EQ(static_cast<size_t>(sa::kPolarMaskBytes), t.mask.back().size());
    }
    const std::vector<double> ct = jsonArray(json, "carrierTable");
    ASSERT_EQ(static_cast<size_t>(v.n), ct.size());
    ASSERT_EQ(32u, t.codes.size());
    t.assign(v, std::vector<int>(ct.begin(), ct.end()));

    Out o;
    EXPECT_EQ(v.evaluated, render(v, o));
    compareCodes("rsrp", o.rsrp, v.rsrp);
    compareCodes("sinr", o.sinr, v.sinr);
    compareCodes("prob", o.prob, v.prob);
    size_t coveredMismatch = 0;
    for (size_t i = 0; i < v.best.size(); ++i) {
        if (v.rsrp[i] != 0.0 && o.rsrp[i] != 0 && static_cast<double>(o.best[i]) != v.best[i]) ++coveredMismatch;
    }
    EXPECT_LE(static_cast<double>(coveredMismatch), 0.001 * static_cast<double>(v.best.size()));

    // The terrain statistics agree with the reference's accuracy bookkeeping (the TERRAIN label rule).
    const int32_t refCovered = static_cast<int32_t>(sa_test::jsonNumber(json, "coveredPixels"));
    const int32_t refTerrain = static_cast<int32_t>(sa_test::jsonNumber(json, "terrainPixels"));
    EXPECT_NEAR(o.covered, refCovered, 0.001 * 65536);
    EXPECT_NEAR(o.covered - o.incomplete, refTerrain, 0.001 * 65536);
    EXPECT_EQ("TERRAIN", sa_test::jsonString(json, "accuracy"));
    EXPECT_GE(o.covered - o.incomplete, 0.95 * o.covered);

    // And the tables really matter: without them the tile is the M2 quick-model tile.
    Vec quick = v;
    quick.terrainTable.clear();
    quick.terrainMask.clear();
    Out q;
    ASSERT_EQ(v.evaluated, render(quick, q));
    size_t changed = 0;
    for (int i = 0; i < sa::kTilePixels; ++i) changed += o.rsrp[i] != q.rsrp[i];
    EXPECT_NEAR(static_cast<double>(changed), sa_test::jsonNumber(json, "pixelsChangedFromQuick"), 0.002 * 65536);
    EXPECT_GT(changed, 30000u);
    EXPECT_EQ(q.covered, q.incomplete) << "without tables every covered pixel is terrain-incomplete";
}

TEST(TileTerrain, UniformLossShiftsEveryPixelAndIncompleteRulesHold) {
    Vec v = loadVec("tile_canberra_z13.json");
    Out plain;
    ASSERT_EQ(v.evaluated, render(v, plain));
    ASSERT_GT(plain.covered, 1000);
    EXPECT_EQ(plain.covered, plain.incomplete);  // no tables: quick model everywhere

    // 10 dB (code 40) everywhere, complete: every pixel loses 10 dB = 20 RSRP codes (all ranges here are < 50 m or
    // within the bins, none beyond 80 km).
    Tables t;
    t.codes.push_back(std::vector<uint8_t>(sa::kPolarTableCells, 40));
    t.mask.push_back(std::vector<uint8_t>(sa::kPolarMaskBytes, 0xFF));
    t.codes.push_back(std::vector<uint8_t>(sa::kPolarTableCells, 0));
    t.mask.push_back(std::vector<uint8_t>(sa::kPolarMaskBytes, 0x00));  // incomplete everywhere
    t.assign(v, std::vector<int>(static_cast<size_t>(v.n), 0));
    Out lossy;
    ASSERT_EQ(v.evaluated, render(v, lossy));
    int shifted = 0, strong = 0;
    for (int i = 0; i < sa::kTilePixels; ++i) {
        if (plain.rsrp[i] < 30) continue;
        ++strong;
        if (std::abs(static_cast<int>(lossy.rsrp[i]) - (static_cast<int>(plain.rsrp[i]) - 20)) <= 1) ++shifted;
    }
    ASSERT_GT(strong, 1000);
    EXPECT_GE(shifted, strong - strong / 200) << "pixels shifted by 20 codes";
    EXPECT_LT(lossy.incomplete, lossy.covered / 20) << "complete lookups almost everywhere (only r < 50 m is free)";

    // A fully masked table is the quick model with every pixel incomplete.
    t.assign(v, std::vector<int>(static_cast<size_t>(v.n), 1));
    Out masked;
    ASSERT_EQ(v.evaluated, render(v, masked));
    EXPECT_EQ(plain.rsrp, masked.rsrp);
    EXPECT_EQ(plain.sinr, masked.sinr);
    EXPECT_EQ(plain.best, masked.best);
    EXPECT_EQ(masked.covered, masked.incomplete);

    // A table with a null mask and a null table entry behave as "no terrain" (no crash, quick model).
    for (auto& m : v.terrainMask) m = nullptr;
    Out nomask;
    ASSERT_EQ(v.evaluated, render(v, nomask));
    EXPECT_EQ(plain.rsrp, nomask.rsrp);
    EXPECT_EQ(nomask.covered, nomask.incomplete);
}

TEST(TileTerrain, StatsAreZeroedWhenOutputsAre) {
    Vec v = loadVec("tile_canberra_z13.json");
    const volatile int32_t flag = 1;
    Out o;
    EXPECT_EQ(sa::kRenderCancelled, render(v, o, &flag));
    EXPECT_EQ(0, o.covered);
    EXPECT_EQ(0, o.incomplete);
    v.n = 0;
    Out e;
    EXPECT_EQ(0, render(v, e));
    EXPECT_EQ(0, e.covered);
    EXPECT_EQ(0, e.incomplete);
    // Null stats pointers are allowed.
    Vec w = loadVec("tile_canberra_z13.json");
    Out n;
    sa::TileOut view{n.rsrp.data(), n.sinr.data(), n.prob.data(), n.best.data()};
    EXPECT_EQ(w.evaluated, sa::renderTile(w.arrays(), kNoPatterns, w.params, view, nullptr));
}

// Random synthetic tables: bumpy codes (up to 40 dB) with random masked holes, shared between carriers or absent.
Tables randomTables(std::mt19937& rng, int count) {
    Tables t;
    for (int k = 0; k < count; ++k) {
        std::vector<uint8_t> codes(sa::kPolarTableCells), mask(sa::kPolarMaskBytes, 0xFF);
        const double a = 0.02 + 0.03 * static_cast<double>(rng() % 5), b = 0.3 + 0.1 * static_cast<double>(rng() % 5);
        const double ph = static_cast<double>(rng() % 628) / 100.0;
        for (int r = 0; r < sa::kTerrainRadials; ++r) {
            for (int j = 0; j < sa::kTerrainBins; ++j) {
                const double v = 80.0 * (0.5 + 0.5 * std::sin(a * r + ph) * std::cos(b * j * 0.1 + ph)) * j / 255.0;
                codes[static_cast<size_t>(r * sa::kTerrainBins + j)] = static_cast<uint8_t>(std::clamp(v, 0.0, 160.0));
            }
        }
        for (int h = 0; h < 40; ++h) {  // masked holes: whole wedges plus single cells
            const int r0 = static_cast<int>(rng() % 360), j0 = static_cast<int>(rng() % 256);
            const int dr = h % 4 == 0 ? 6 : 1, dj = h % 4 == 0 ? 20 : 1;
            for (int r = r0; r < r0 + dr; ++r) {
                for (int j = j0; j < std::min(256, j0 + dj); ++j) {
                    const int cell = (r % 360) * sa::kTerrainBins + j;
                    mask[static_cast<size_t>(cell >> 3)] = static_cast<uint8_t>(mask[static_cast<size_t>(cell >> 3)] & ~(1 << (cell & 7)));
                }
            }
        }
        t.codes.push_back(std::move(codes));
        t.mask.push_back(std::move(mask));
    }
    return t;
}

// The branch and bound keeps L_terr = 0 in its upper bound; with arbitrary non-negative tables the pruned render
// must still equal the brute-force one (Review Focus 2).
TEST(TileTerrain, PrunedRenderEqualsBruteForceWithRandomTables) {
    std::mt19937 rng(20261024);
    size_t changedByTables = 0;
    for (int t = 0; t < 12; ++t) {
        Scenario sc = randomScenario(rng, t);
        const Tables tables = randomTables(rng, 4);
        std::vector<int> assignment(static_cast<size_t>(sc.v.n));
        for (int& a : assignment) a = static_cast<int>(rng() % 5) - 1;  // -1 (no table) or tables 0..3
        tables.assign(sc.v, assignment);
        const sa::PatternTable table{sc.patternCount, sc.patterns.data()};
        Out fast, brute;
        const int32_t a = render(sc.v, fast, nullptr, table);
        sc.v.params.debugNoBounds = true;
        const int32_t b = render(sc.v, brute, nullptr, table);
        ASSERT_EQ(a, b) << t;
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
        EXPECT_EQ(brute.covered, fast.covered) << t;
        EXPECT_EQ(brute.incomplete, fast.incomplete) << t;
        EXPECT_EQ(static_cast<size_t>(brute.covered), covered) << t;
        // The tables bite: the render differs from the same scenario without them somewhere.
        sc.v.terrainTable.clear();
        sc.v.terrainMask.clear();
        Out quick;
        ASSERT_EQ(a, render(sc.v, quick, nullptr, table));
        for (int i = 0; i < sa::kTilePixels; ++i) changedByTables += quick.rsrp[i] != fast.rsrp[i];
    }
    EXPECT_GT(changedByTables, 10000u);
}

}  // namespace
