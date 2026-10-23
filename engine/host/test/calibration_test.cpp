// M4 Task 2: calibration terms, residual rasters and the point query, against the Python reference and the
// brute-force renderer.
#include <gtest/gtest.h>

#include <cmath>
#include <cstdint>
#include <limits>
#include <random>
#include <string>
#include <vector>

#include "model/pathloss.h"
#include "render/calibration.h"
#include "render/mercator.h"
#include "render/point.h"
#include "render/tile.h"
#include "tile_fixtures.h"
#include "vectors.h"

namespace {

using namespace sa_tile_test;
using sa_test::jsonArray;

sa::Area areaOf(const std::string& a) {
    return a == "DENSE_URBAN" ? sa::Area::DenseUrban
           : a == "URBAN"     ? sa::Area::Urban
           : a == "SUBURBAN"  ? sa::Area::Suburban
                              : sa::Area::Rural;
}

TEST(Calibration, TermsMatchTheRule) {
    const sa::PreparedCal none = sa::prepareCal(NAN, NAN, NAN, NAN);
    EXPECT_EQ(0.0, none.db(1234.0));
    EXPECT_EQ(8.0f, none.sigma(8.0f));
    const sa::PreparedCal c = sa::prepareCal(-3.0f, 10.0f, 2.0f, 0.5f);
    EXPECT_NEAR(-3.0, c.db(2000.0), 1e-12);
    EXPECT_NEAR(7.0, c.db(20000.0), 1e-12);       // one decade out
    EXPECT_NEAR(7.0, c.db(2.0e6), 1e-12);         // clamped at 10x
    EXPECT_NEAR(-13.0, c.db(0.0), 1e-12);         // clamped at 0.1x (d = 0 is fine)
    EXPECT_NEAR(-3.0, c.db(std::nan("")), 1e-12); // NaN distance: offset only
    EXPECT_FLOAT_EQ(5.0f, c.sigma(10.0f));
    EXPECT_FLOAT_EQ(4.0f, c.sigma(6.0f));         // the 4 dB floor
    EXPECT_NEAR(7.0, c.upperBound(100.0, 20000.0), 1e-12);
    const sa::PreparedCal neg = sa::prepareCal(1.0f, -5.0f, 1.0f, 2.0f);
    EXPECT_NEAR(6.0, neg.upperBound(50.0, 300.0), 1e-12);  // decreasing: the near end
    EXPECT_FLOAT_EQ(9.0f, neg.sigma(9.0f));                 // scale >= 1 leaves sigma alone
    EXPECT_EQ(2.0, sa::prepareCal(2.0f, 5.0f, 0.0f, NAN).db(500.0));  // d_median 0: no slope term
}

TEST(Calibration, CalibratedTileMatchesReference) {
    Vec v = loadVec("calibration/tile_canberra_z13_calibrated.json");
    ASSERT_EQ(65536u, v.rsrp.size());
    ASSERT_EQ(static_cast<size_t>(v.n), v.calOffsetDb.size());
    ASSERT_EQ(static_cast<size_t>(v.n), v.network.size());
    ASSERT_EQ(2u, v.rasterCodes.size());
    ASSERT_EQ(65536u, v.rasterCodes[0].size());
    Out o;
    EXPECT_EQ(v.evaluated, renderWithRasters(v, o));
    compareCodes("rsrp", o.rsrp, v.rsrp);
    compareCodes("sinr", o.sinr, v.sinr);
    compareCodes("prob", o.prob, v.prob);
    size_t mismatch = 0;
    for (size_t i = 0; i < v.best.size(); ++i) {
        if (v.rsrp[i] != 0.0 && o.rsrp[i] != 0 && static_cast<double>(o.best[i]) != v.best[i]) ++mismatch;
    }
    EXPECT_LE(static_cast<double>(mismatch), 0.001 * 65536);
}

TEST(Calibration, PointMatchesReference) {
    const std::string json = sa_test::readFile("calibration/point_canberra.json");
    Vec v = loadVec("calibration/point_canberra.json");
    ASSERT_GT(v.n, 100);
    const std::vector<std::string> pts = sa_test::objectsStartingWith(json, "points", "{\"lat\"");
    ASSERT_EQ(8u, pts.size());
    std::vector<float> got(static_cast<size_t>(v.n));
    double worst = 0.0;
    for (const std::string& pt : pts) {
        const std::vector<double> want = jsonArray(pt, "rsrp");
        ASSERT_EQ(static_cast<size_t>(v.n), want.size());
        const bool indoor = sa_test::jsonNumber(pt, "indoor") == 1.0;
        ASSERT_EQ(v.n, sa::predictPoint(v.arrays(), kNoPatterns, sa_test::jsonNumber(pt, "lat"),
                                        sa_test::jsonNumber(pt, "lon"), areaOf(sa_test::jsonString(pt, "area")), indoor,
                                        got.data()));
        for (int32_t i = 0; i < v.n; ++i) {
            ASSERT_TRUE(std::isfinite(got[static_cast<size_t>(i)])) << i;
            worst = std::max(worst, std::fabs(got[static_cast<size_t>(i)] - want[static_cast<size_t>(i)]));
        }
    }
    EXPECT_LT(worst, 2e-3) << "dB";
}

TEST(Calibration, PointQueryAgreesWithTheRenderer) {
    // At each grid sample of a 256 grid, the best predicted RSRP equals the rendered pixel (same model, no culling
    // difference: every carrier of this tile passes culling).
    Vec v = loadVec("calibration/tile_canberra_z13_calibrated.json");
    v.params.grid = 256;
    Out o;
    ASSERT_EQ(v.n, render(v, o));
    std::vector<float> got(static_cast<size_t>(v.n));
    for (int32_t j = 7; j < 256; j += 61) {
        for (int32_t i = 3; i < 256; i += 53) {
            const double lat = sa::merc::tileLatDeg(v.params.y + (j + 0.5) / 256.0, v.params.z);
            const double lon = sa::merc::tileLonDeg(v.params.x + (i + 0.5) / 256.0, v.params.z);
            ASSERT_EQ(v.n, sa::predictPoint(v.arrays(), kNoPatterns, lat, lon, v.params.area, false, got.data()));
            int32_t best = 0;
            for (int32_t k = 1; k < v.n; ++k) {
                if (got[static_cast<size_t>(k)] > got[static_cast<size_t>(best)]) best = k;
            }
            const size_t px = static_cast<size_t>(j) * 256 + static_cast<size_t>(i);
            const double code = std::floor((got[static_cast<size_t>(best)] + 140.0) * 2.0 + 0.5);
            EXPECT_NEAR(code, o.rsrp[px], 1.0) << i << "," << j;
            EXPECT_EQ(best, o.best[px]) << i << "," << j;
        }
    }
}

TEST(Calibration, PointQueryEdgeCases) {
    Vec v = loadVec("tile_canberra_z13.json");
    std::vector<float> out(static_cast<size_t>(v.n), 0.0f);
    v.eirpRe[4] = NAN;
    v.lat[5] = NAN;
    const double lat = -35.28, lon = 149.13;
    ASSERT_EQ(v.n, sa::predictPoint(v.arrays(), kNoPatterns, lat, lon, sa::Area::Rural, false, out.data()));
    EXPECT_TRUE(std::isnan(out[4]));
    EXPECT_TRUE(std::isnan(out[5]));
    EXPECT_TRUE(std::isfinite(out[6]));
    // Far beyond every R_max: still predicted (the matcher needs it), and much weaker.
    std::vector<float> far(static_cast<size_t>(v.n));
    ASSERT_EQ(v.n, sa::predictPoint(v.arrays(), kNoPatterns, -25.0, 137.0, sa::Area::Rural, false, far.data()));
    EXPECT_TRUE(std::isfinite(far[6]));
    EXPECT_LT(far[6], out[6] - 40.0f);
    // Indoor costs O2I loss.
    std::vector<float> in(static_cast<size_t>(v.n));
    ASSERT_EQ(v.n, sa::predictPoint(v.arrays(), kNoPatterns, lat, lon, sa::Area::Rural, true, in.data()));
    EXPECT_LT(in[6], out[6]);
    EXPECT_EQ(sa::kRenderInvalid, sa::predictPoint(v.arrays(), kNoPatterns, NAN, lon, sa::Area::Rural, false, out.data()));
    EXPECT_EQ(sa::kRenderInvalid, sa::predictPoint(v.arrays(), kNoPatterns, lat, lon, sa::Area::Rural, false, nullptr));
    v.n = 0;
    EXPECT_EQ(0, sa::predictPoint(v.arrays(), kNoPatterns, lat, lon, sa::Area::Rural, false, nullptr));
}

TEST(Calibration, NeutralTermsAndForeignRastersChangeNothing) {
    Vec v = loadVec("tile_canberra_z13.json");
    Out plain;
    ASSERT_EQ(v.evaluated, render(v, plain));
    const size_t n = static_cast<size_t>(v.n);
    v.calOffsetDb.assign(n, 0.0f);
    v.calSlope.assign(n, 0.0f);
    v.calDmedKm.assign(n, 1.0f);
    v.calSigmaScale.assign(n, 1.0f);
    v.network.assign(n, 0);
    v.rasterCodes.push_back(std::vector<int8_t>(65536, 100));
    v.rasterMask.push_back(1u << 7);  // no carrier is network 7
    Out neutral;
    ASSERT_EQ(v.evaluated, renderWithRasters(v, neutral));
    EXPECT_EQ(plain.rsrp, neutral.rsrp);
    EXPECT_EQ(plain.sinr, neutral.sinr);
    EXPECT_EQ(plain.prob, neutral.prob);
    EXPECT_EQ(plain.best, neutral.best);
}

TEST(Calibration, UniformOffsetsRastersAndSigmaShiftAsExpected) {
    Vec v = loadVec("tile_canberra_z13.json");
    Out plain;
    ASSERT_EQ(v.evaluated, render(v, plain));
    const size_t n = static_cast<size_t>(v.n);
    // +4 dB offset everywhere and a +2 dB raster (code 8) for network 0 = every carrier: +6 dB = 12 codes; SINR is
    // unchanged (signal and interference shift together, noise aside) and the best server too.
    v.calOffsetDb.assign(n, 4.0f);
    v.network.assign(n, 0);
    v.rasterCodes.push_back(std::vector<int8_t>(65536, 8));
    v.rasterMask.push_back(1u);
    Out up;
    ASSERT_EQ(v.evaluated, renderWithRasters(v, up));
    int shifted = 0;
    for (int i = 0; i < sa::kTilePixels; ++i) {
        if (std::abs(static_cast<int>(up.rsrp[i]) - (static_cast<int>(plain.rsrp[i]) + 12)) <= 1) ++shifted;
        ASSERT_EQ(plain.best[i], up.best[i]) << i;
    }
    EXPECT_GE(shifted, sa::kTilePixels - sa::kTilePixels / 500);
    // Sigma scale: same RSRP, a sharper probability (closer to 0 or 1) and never below the 4 dB floor's.
    v.calOffsetDb.clear();
    v.rasterCodes.clear();
    v.rasterMask.clear();
    v.calSigmaScale.assign(n, 0.5f);
    Out sharp;
    ASSERT_EQ(v.evaluated, render(v, sharp));
    EXPECT_EQ(plain.rsrp, sharp.rsrp);
    int sharper = 0;
    for (int i = 0; i < sa::kTilePixels; ++i) {
        const int a = std::abs(static_cast<int>(plain.prob[i]) - 128), b = std::abs(static_cast<int>(sharp.prob[i]) - 128);
        if (b >= a) ++sharper;
    }
    EXPECT_EQ(sa::kTilePixels, sharper);
    v.calSigmaScale.assign(n, 0.0f);  // floor: sigma 4 everywhere
    Out floor4;
    ASSERT_EQ(v.evaluated, render(v, floor4));
    v.calSigmaScale.assign(n, 0.5f);  // 8 * 0.5 = 4 for the Hata/COST-231 tile: the same as the floor
    EXPECT_EQ(sharp.prob, floor4.prob);
}

TEST(Calibration, InvalidRastersAreRejected) {
    Vec v = loadVec("tile_canberra_z13.json");
    Out o;
    sa::RenderParams p = v.params;
    std::vector<sa::ResidualRaster> rs(sa::kMaxResidualRasters + 1, sa::ResidualRaster{nullptr, 1});
    std::vector<int8_t> q(65536, 0);
    for (auto& r : rs) r.q = q.data();
    p.residuals = rs.data();
    p.residualCount = sa::kMaxResidualRasters + 1;
    sa::TileOut view = o.view();
    EXPECT_EQ(sa::kRenderInvalid, sa::renderTile(v.arrays(), kNoPatterns, p, view, nullptr));
    p.residualCount = 1;
    rs[0].q = nullptr;
    EXPECT_EQ(sa::kRenderInvalid, sa::renderTile(v.arrays(), kNoPatterns, p, view, nullptr));
    p.residuals = nullptr;
    EXPECT_EQ(sa::kRenderInvalid, sa::renderTile(v.arrays(), kNoPatterns, p, view, nullptr));
}

// Random calibration terms (strong offsets and slopes of both signs, NaNs) and random residual rasters (extreme
// codes, overlapping masks): the pruned render must still equal the brute-force one, i.e. the bound stays an upper
// bound.
TEST(Calibration, PrunedRenderEqualsBruteForceWithCalibration) {
    std::mt19937 rng(20261025);
    auto uni = [&](double lo, double hi) { return std::uniform_real_distribution<double>(lo, hi)(rng); };
    size_t changed = 0;
    for (int t = 0; t < 16; ++t) {
        Scenario sc = randomScenario(rng, t);
        Vec& v = sc.v;
        const size_t n = static_cast<size_t>(v.n);
        for (size_t i = 0; i < n; ++i) {
            const bool on = rng() % 4 != 0;
            v.calOffsetDb.push_back(on ? static_cast<float>(uni(-20, 20)) : NAN);
            v.calSlope.push_back(on && rng() % 2 ? static_cast<float>(uni(-25, 25)) : NAN);
            v.calDmedKm.push_back(static_cast<float>(uni(0.05, 3.0)));
            v.calSigmaScale.push_back(on ? static_cast<float>(uni(0.2, 1.0)) : NAN);
            v.network.push_back(static_cast<uint8_t>(rng() % 3));
        }
        const int rasters = static_cast<int>(rng() % 4);
        for (int r = 0; r < rasters; ++r) {
            std::vector<int8_t> q(65536);
            const int kind = static_cast<int>(rng() % 3);
            for (int py = 0; py < 256; ++py) {
                for (int px = 0; px < 256; ++px) {
                    int val = 0;
                    if (kind == 0) val = static_cast<int>(rng() % 255) - 127;                  // noise
                    if (kind == 1) val = ((px / 40 + py / 40) % 2) ? 120 : -120;              // squares
                    if (kind == 2) val = (px == 77 || py == 131) ? 127 : 0;                   // thin lines
                    q[static_cast<size_t>(py) * 256 + static_cast<size_t>(px)] = static_cast<int8_t>(val);
                }
            }
            v.rasterCodes.push_back(std::move(q));
            v.rasterMask.push_back(1u + static_cast<uint32_t>(rng() % 7));
        }
        const sa::PatternTable table{sc.patternCount, sc.patterns.data()};
        Out fast, brute, uncal;
        const int32_t a = renderWithRasters(v, fast, table);
        v.params.debugNoBounds = true;
        const int32_t b = renderWithRasters(v, brute, table);
        ASSERT_EQ(a, b) << t;
        for (int i = 0; i < sa::kTilePixels; ++i) {
            ASSERT_EQ(brute.rsrp[i], fast.rsrp[i]) << "tile " << t << " px " << i;
            ASSERT_EQ(brute.prob[i], fast.prob[i]) << "tile " << t << " px " << i;
            ASSERT_EQ(brute.best[i], fast.best[i]) << "tile " << t << " px " << i;
            ASSERT_LE(std::abs(static_cast<int>(brute.sinr[i]) - static_cast<int>(fast.sinr[i])), 1)
                << "tile " << t << " px " << i;
        }
        v.params.debugNoBounds = false;
        v.calOffsetDb.clear();
        v.calSlope.clear();
        v.rasterCodes.clear();
        v.rasterMask.clear();
        ASSERT_EQ(a, render(v, uncal, nullptr, table));
        for (int i = 0; i < sa::kTilePixels; ++i) changed += uncal.rsrp[i] != fast.rsrp[i];
    }
    EXPECT_GT(changed, 100000u) << "the calibration terms bite";
}

}  // namespace
