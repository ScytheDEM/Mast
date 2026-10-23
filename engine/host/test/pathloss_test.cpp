#include <gtest/gtest.h>

#include "model/pathloss.h"
#include "model/stats.h"
#include "vectors.h"

namespace {

sa::Area areaOf(const std::string& s) {
    if (s == "DENSE_URBAN") return sa::Area::DenseUrban;
    if (s == "URBAN") return sa::Area::Urban;
    if (s == "SUBURBAN") return sa::Area::Suburban;
    return sa::Area::Rural;
}

constexpr sa::Area kAreas[] = {sa::Area::DenseUrban, sa::Area::Urban, sa::Area::Suburban, sa::Area::Rural};

}  // namespace

TEST(PathLoss, MatchesReferenceVectors) {
    const auto rows = sa_test::readCsv("pathloss.csv");
    ASSERT_EQ(rows.size(), 504u);
    for (const auto& r : rows) {
        const sa::PathLoss got = sa::pathLoss(static_cast<float>(sa_test::num(r[0])),
                                              static_cast<float>(sa_test::num(r[1])),
                                              static_cast<float>(sa_test::num(r[2])), areaOf(r[3]));
        EXPECT_NEAR(got.plDb, sa_test::num(r[5]), 0.01) << r[0] << " " << r[1] << " " << r[2] << " " << r[3];
        EXPECT_NEAR(got.sigmaDb, sa_test::num(r[6]), 0.01) << r[0] << " " << r[1] << " " << r[2] << " " << r[3];
    }
}

TEST(PathLoss, O2iLowLoss) {
    // Rule 7 closed form at 3.5 GHz, checked independently of the implementation's code path.
    const double f = 3.5;
    const double expected = 5.0 - 10.0 * std::log10(0.3 * std::pow(10.0, -(2.0 + 0.2 * f) / 10.0) +
                                                    0.7 * std::pow(10.0, -(5.0 + 4.0 * f) / 10.0)) + 2.5;
    EXPECT_NEAR(sa::o2iLowLossDb(3500.0f), expected, 1e-4);
    EXPECT_GT(sa::o2iLowLossDb(900.0f), 5.0f);
}

TEST(CoverageProbability, Basics) {
    EXPECT_NEAR(sa::coverageProbability(-100.0f, -100.0f, 8.0f), 0.5f, 1e-6);
    EXPECT_NEAR(sa::coverageProbability(-92.0f, -100.0f, 8.0f), 0.8413447f, 1e-5);  // +1 sigma
    EXPECT_LT(sa::coverageProbability(-140.0f, -100.0f, 8.0f), 1e-6f);
    EXPECT_GT(sa::coverageProbability(-60.0f, -100.0f, 8.0f), 0.999999f);
}

TEST(MaxRange, MonotonicInEirp) {
    for (sa::Area a : kAreas) {
        for (float f : {700.0f, 1800.0f, 3500.0f, 26000.0f}) {
            float prev = -1.0f;
            for (float e = 0.0f; e <= 80.0f; e += 2.5f) {
                const float r = sa::maxRangeKm(e, f, 30.0f, a);
                EXPECT_GE(r, prev) << "f=" << f << " e=" << e;
                EXPECT_LE(r, 80.0f);
                prev = r;
            }
        }
    }
}

TEST(MaxRange, CapAndZero) {
    EXPECT_EQ(sa::maxRangeKm(100.0f, 700.0f, 60.0f, sa::Area::Rural), 80.0f);
    EXPECT_EQ(sa::maxRangeKm(-100.0f, 3500.0f, 30.0f, sa::Area::Urban), 0.0f);
}

TEST(MaxRange, CullingIsMaxOverClassesAndLandsAtFloor) {
    float best = 0.0f;
    for (sa::Area a : kAreas) best = std::fmax(best, sa::maxRangeKm(45.0f, 3500.0f, 3.0f, a));
    EXPECT_EQ(sa::cullingRangeKm(45.0f, 3500.0f, 3.0f), best);
    // Rule 9 example: URBAN (UMa) reaches much further than RURAL (RMa) at 3.5 GHz, h 3 m.
    EXPECT_GT(sa::maxRangeKm(45.0f, 3500.0f, 3.0f, sa::Area::Urban), sa::maxRangeKm(45.0f, 3500.0f, 3.0f, sa::Area::Rural));
    const float r = sa::maxRangeKm(45.0f, 3500.0f, 3.0f, sa::Area::Urban);
    EXPECT_GE(45.0f - sa::pathLoss(3500.0f, r, 3.0f, sa::Area::Urban).plDb, -140.01f);
}

TEST(MaxRange, CullingMatchesReferenceCarriers) {
    for (const char* tile : {"tile_canberra_z13.json", "tile_edge_z12.json"}) {
        const std::string json = sa_test::readFile(tile);
        const auto eirpRe = sa_test::jsonArray(json, "eirpRe");
        const auto f = sa_test::jsonArray(json, "freqMhz");
        const auto h = sa_test::jsonArray(json, "hTx");
        const auto rMax = sa_test::jsonArray(json, "rMaxKm");
        ASSERT_EQ(eirpRe.size(), 109u) << tile;
        ASSERT_EQ(f.size(), eirpRe.size());
        ASSERT_EQ(h.size(), eirpRe.size());
        ASSERT_EQ(rMax.size(), eirpRe.size());
        for (size_t i = 0; i < rMax.size(); ++i) {
            EXPECT_NEAR(sa::cullingRangeKm(static_cast<float>(eirpRe[i]), static_cast<float>(f[i]),
                                           static_cast<float>(h[i])),
                        rMax[i], 0.01)
                << tile << " carrier " << i;
        }
    }
}
