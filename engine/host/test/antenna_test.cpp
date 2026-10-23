#include <gtest/gtest.h>

#include <map>
#include <vector>

#include "model/antenna.h"
#include "vectors.h"

namespace {

std::map<int, std::vector<uint8_t>> loadPatterns() {
    std::map<int, std::vector<uint8_t>> out;
    for (const auto& r : sa_test::readCsv("patterns.csv")) {
        std::vector<uint8_t> att;
        for (const std::string& v : sa_test::split(r[1], ' ')) att.push_back(static_cast<uint8_t>(std::atoi(v.c_str())));
        out[std::atoi(r[0].c_str())] = att;
    }
    return out;
}

}  // namespace

TEST(AntennaAttenuation, MatchesReferenceVectors) {
    const auto patterns = loadPatterns();
    ASSERT_EQ(patterns.size(), 2u);
    for (const auto& p : patterns) ASSERT_EQ(p.second.size(), 360u);
    const auto rows = sa_test::readCsv("antenna.csv");
    ASSERT_GT(rows.size(), 100u);
    size_t hrpRows = 0;
    for (const auto& r : rows) {
        sa::AntennaParams p{};
        p.azimuthDeg = static_cast<float>(sa_test::num(r[2]));
        p.hBwDeg = static_cast<float>(sa_test::num(r[3]));
        p.fbDb = static_cast<float>(sa_test::num(r[4]));
        p.gainDbi = static_cast<float>(sa_test::num(r[5]));
        p.tiltDeg = static_cast<float>(sa_test::num(r[6]));
        p.hTxM = static_cast<float>(sa_test::num(r[7]));
        p.freqMhz = static_cast<float>(sa_test::num(r[10]));
        const int hrpId = std::atoi(r[11].c_str());
        p.hrp = hrpId == 0 ? nullptr : patterns.at(hrpId).data();
        p.hrpPeakBearing = static_cast<int16_t>(std::atoi(r[12].c_str()));
        if (hrpId != 0) ++hrpRows;
        const float got = sa::antennaAttenuation(p, static_cast<float>(sa_test::num(r[0])),
                                                 static_cast<float>(sa_test::num(r[8])));
        EXPECT_NEAR(got, sa_test::num(r[9]), 0.01) << "row bearing=" << r[0] << " d=" << r[8] << " hrp=" << r[11];
    }
    EXPECT_GT(hrpRows, 10u);
}

TEST(AntennaAttenuation, OmniOnAxisIsOnlyVertical) {
    sa::AntennaParams p{};
    p.azimuthDeg = p.hBwDeg = p.fbDb = p.gainDbi = std::nanf("");
    p.tiltDeg = 0.0f;
    p.hTxM = 30.0f;
    p.freqMhz = 900.0f;
    const float a0 = sa::antennaAttenuation(p, 0.0f, 500.0f);
    EXPECT_FLOAT_EQ(a0, sa::antennaAttenuation(p, 133.0f, 500.0f));
    EXPECT_GE(a0, 0.0f);
}
