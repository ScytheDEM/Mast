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

// Literal values from tools/engine/reference.py (python3 -I): o2i_low_loss_db, indoor_sigma, and
// path_loss + o2i_low_loss_db.
TEST(PathLoss, O2iLowLoss) {
    EXPECT_NEAR(sa::o2iLowLossDb(700.0f), 12.736703295401664, 1e-4);
    EXPECT_NEAR(sa::o2iLowLossDb(2100.0f), 14.407212794424003, 1e-4);
    EXPECT_NEAR(sa::o2iLowLossDb(3500.0f), 15.19750345954387, 1e-4);
}

TEST(PathLoss, IndoorSigmaQuadrature) {
    EXPECT_EQ(sa::kO2iSigmaDb, 4.4);
    EXPECT_NEAR(sa::indoorSigmaDb(8.0f), 9.130169768410662, 1e-5);
    EXPECT_NEAR(sa::indoorSigmaDb(6.0f), 7.4404300950953095, 1e-5);
    EXPECT_NEAR(sa::indoorSigmaDb(4.0f), 5.946427498927402, 1e-5);
    EXPECT_NEAR(sa::indoorSigmaDb(7.82f), 8.972870220837924, 1e-5);
}

TEST(PathLoss, IndoorMatchesReference) {
    struct Case { float f, d, h; sa::Area a; double pl, sigma; };
    const Case cases[] = {
        {900.0f, 1.0f, 30.0f, sa::Area::Urban, 139.45926002029756, 9.130169768410662},
        {3500.0f, 3.0f, 30.0f, sa::Area::Urban, 175.30498563305068, 7.43075618726367},
        {2100.0f, 0.5f, 10.0f, sa::Area::Suburban, 135.48523733272, 9.130169768410662},
    };
    for (const Case& c : cases) {
        const sa::PathLoss r = sa::pathLossIndoor(c.f, c.d, c.h, c.a);
        EXPECT_NEAR(r.plDb, c.pl, 0.01) << c.f;
        EXPECT_NEAR(r.sigmaDb, c.sigma, 0.01) << c.f;
    }
}

TEST(PathLoss, PreparedMatchesWrappers) {
    const sa::PreparedPathLoss pp = sa::preparePathLoss(3500.0f, 30.0f, sa::Area::Rural);
    for (float d : {0.01f, 0.3f, 3.0f, 30.0f}) {
        EXPECT_EQ(pp.evaluate(d).plDb, sa::pathLoss(3500.0f, d, 30.0f, sa::Area::Rural).plDb);
        EXPECT_EQ(pp.evaluateIndoor(d).sigmaDb, sa::pathLossIndoor(3500.0f, d, 30.0f, sa::Area::Rural).sigmaDb);
    }
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
