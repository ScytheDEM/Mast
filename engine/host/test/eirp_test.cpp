#include <gtest/gtest.h>

#include "model/eirp.h"
#include "vectors.h"

using sa::Tech;

TEST(EirpPerRe, MatchesReferenceVectors) {
    const auto rows = sa_test::readCsv("eirp_re.csv");
    ASSERT_GT(rows.size(), 50u);
    for (const auto& r : rows) {
        const Tech tech = r[0] == "NR" ? Tech::NR : Tech::LTE;
        const float got = sa::eirpPerRe(static_cast<float>(sa_test::num(r[3])), tech,
                                        static_cast<float>(sa_test::num(r[1])),
                                        static_cast<int32_t>(sa_test::num(r[2])));
        EXPECT_NEAR(got, sa_test::num(r[4]), 0.01) << r[0] << " f=" << r[1] << " bw=" << r[2];
    }
}

TEST(EirpPerRe, KnownValues) {
    EXPECT_NEAR(sa::eirpPerRe(60.0f, Tech::LTE, 900.0f, 20000000), 60.0 - 30.791812, 0.001);
    EXPECT_NEAR(sa::eirpPerRe(60.0f, Tech::NR, 3500.0f, 100000000), 60.0 - 35.153439, 0.001);
}

TEST(EirpPerRe, MinimumSixResourceBlocks) {
    EXPECT_EQ(sa::numResourceBlocks(Tech::LTE, 900.0f, 100000), 6);
    EXPECT_EQ(sa::numResourceBlocks(Tech::LTE, 900.0f, 0), 6);
}
