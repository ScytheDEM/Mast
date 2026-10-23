#include <gtest/gtest.h>

#include "engine.h"

TEST(Smoke, Version) {
    EXPECT_EQ(sa_engine_version(), 1);
}
