// Ruling AA: the engine never throws or aborts. Garbage in (NaN, inf, zero, out of range) gives finite values out.
#include <gtest/gtest.h>

#include <cmath>
#include <limits>
#include <vector>

#include "model/antenna.h"
#include "model/eirp.h"
#include "model/pathloss.h"
#include "model/stats.h"

namespace {

const float kNaN = std::numeric_limits<float>::quiet_NaN();
const float kInf = std::numeric_limits<float>::infinity();
const std::vector<float> kBad = {kNaN, kInf, -kInf, 0.0f, -1.0f, 1e30f, -1e30f, 1e-30f};
const sa::Area kAreas[] = {sa::Area::DenseUrban, sa::Area::Urban, sa::Area::Suburban, sa::Area::Rural,
                           static_cast<sa::Area>(200)};

}  // namespace

TEST(Robustness, EirpPerRe) {
    for (float e : kBad)
        for (float f : kBad)
            for (int32_t bw : {0, -1, 1, 2147483647, -2147483647 - 1, 20000000})
                for (sa::Tech t : {sa::Tech::LTE, sa::Tech::NR, static_cast<sa::Tech>(77)})
                    EXPECT_TRUE(std::isfinite(sa::eirpPerRe(e, t, f, bw))) << e << " " << f << " " << bw;
}

TEST(Robustness, ZeroBandwidthUsesMinimumResourceBlocks) {
    EXPECT_NEAR(sa::eirpPerRe(50.0f, sa::Tech::LTE, 900.0f, 0), 50.0 - 10.0 * std::log10(72.0), 1e-4);
}

TEST(Robustness, PathLossAndRanges) {
    std::vector<float> goods = {900.0f, 0.5f, 30.0f};
    for (float f : kBad)
        for (float d : kBad)
            for (float h : kBad)
                for (sa::Area a : kAreas) {
                    const sa::PathLoss pl = sa::pathLoss(f, d, h, a);
                    EXPECT_TRUE(std::isfinite(pl.plDb) && std::isfinite(pl.sigmaDb)) << f << " " << d << " " << h;
                }
    for (float e : kBad)
        for (float f : kBad)
            for (float h : kBad) {
                for (sa::Area a : kAreas) {
                    const float r = sa::maxRangeKm(e, f, h, a);
                    EXPECT_TRUE(std::isfinite(r) && r >= 0.0f && r <= 80.0f);
                }
                const float c = sa::cullingRangeKm(e, f, h);
                EXPECT_TRUE(std::isfinite(c) && c >= 0.0f && c <= 80.0f);
            }
    for (float f : kBad) EXPECT_TRUE(std::isfinite(sa::o2iLowLossDb(f)));
    for (float s : kBad) EXPECT_TRUE(std::isfinite(sa::indoorSigmaDb(s)));
    for (float f : kBad)
        for (float d : kBad) {
            const sa::PathLoss r = sa::pathLossIndoor(f, d, 30.0f, sa::Area::Urban);
            EXPECT_TRUE(std::isfinite(r.plDb) && std::isfinite(r.sigmaDb));
        }
}

TEST(Robustness, FrequencyOutsideEveryRangeClampsToNearestRange) {
    // Below 30 MHz and above 100 GHz behave as the nearest valid frequency.
    EXPECT_EQ(sa::pathLoss(1.0f, 1.0f, 30.0f, sa::Area::Urban).plDb, sa::pathLoss(30.0f, 1.0f, 30.0f, sa::Area::Urban).plDb);
    EXPECT_EQ(sa::pathLoss(1e9f, 1.0f, 30.0f, sa::Area::Urban).plDb, sa::pathLoss(1e5f, 1.0f, 30.0f, sa::Area::Urban).plDb);
}

TEST(Robustness, ZeroHeightIsClampedToThreeMetres) {
    EXPECT_EQ(sa::pathLoss(3500.0f, 1.0f, 0.0f, sa::Area::Rural).plDb, sa::pathLoss(3500.0f, 1.0f, 3.0f, sa::Area::Rural).plDb);
}

TEST(Robustness, Antenna) {
    std::vector<uint8_t> hrp(360, 200);
    const uint8_t* const kPats[] = {nullptr, hrp.data()};
    for (const uint8_t* pat : kPats)
        for (float az : kBad)
            for (float bw : kBad)
                for (float fb : kBad)
                    for (float g : kBad)
                        for (float tilt : kBad)
                            for (float h : kBad) {
                                sa::AntennaParams p{az, bw, fb, g, tilt, h, 1800.0f, pat, -32768};
                                for (float bearing : kBad)
                                    for (float d : {kNaN, kInf, 0.0f, 1.0f, 5e8f}) {
                                        const float a = sa::antennaAttenuation(p, bearing, d);
                                        ASSERT_TRUE(std::isfinite(a) && a >= 0.0f);
                                    }
                            }
    sa::AntennaParams q{90.0f, 65.0f, 25.0f, 15.0f, 0.0f, 30.0f, kNaN, hrp.data(), 32767};
    EXPECT_TRUE(std::isfinite(sa::antennaAttenuation(q, -359.5f, 100.0f)));
}

TEST(Robustness, CoverageProbability) {
    for (float r : kBad)
        for (float t : kBad)
            for (float s : kBad) {
                const float p = sa::coverageProbability(r, t, s);
                EXPECT_TRUE(std::isfinite(p) && p >= 0.0f && p <= 1.0f) << r << " " << t << " " << s;
            }
}
