// Tile-seam regression (2026-10-24 seam bug): two adjacent tiles rendered over the same carriers must agree along
// their shared edge. A pixel on the east edge of tile x and the one on the west edge of tile x + 1 are 1/256 of a tile
// apart, so where the same carrier serves both their RSRP may differ by the field's gradient only.
#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "render/tile.h"
#include "tile_fixtures.h"

namespace {

using namespace sa_tile_test;

constexpr double kMaxEdgeDeltaDb = 1.5;

struct EdgeStats {
    double worstDb = 0.0;  // largest |dRSRP| among edge pairs with the same best server
    int pairs = 0;         // edge pairs compared (both covered, same server)
};

// Compares the east column of `west` with the west column of `east` (codes are 0.5 dB steps).
EdgeStats eastWestEdge(const Out& west, const Out& east) {
    EdgeStats s;
    for (int row = 0; row < 256; ++row) {
        const size_t a = static_cast<size_t>(row) * 256 + 255, b = static_cast<size_t>(row) * 256;
        if (west.rsrp[a] == 0 || east.rsrp[b] == 0 || west.best[a] != east.best[b]) continue;
        ++s.pairs;
        s.worstDb = std::max(s.worstDb, std::abs(static_cast<int>(west.rsrp[a]) - static_cast<int>(east.rsrp[b])) * 0.5);
    }
    return s;
}

// Compares the south row of `north` with the north row of `south`.
EdgeStats northSouthEdge(const Out& north, const Out& south) {
    EdgeStats s;
    for (int col = 0; col < 256; ++col) {
        const size_t a = static_cast<size_t>(255) * 256 + static_cast<size_t>(col), b = static_cast<size_t>(col);
        if (north.rsrp[a] == 0 || south.rsrp[b] == 0 || north.best[a] != south.best[b]) continue;
        ++s.pairs;
        s.worstDb = std::max(s.worstDb, std::abs(static_cast<int>(north.rsrp[a]) - static_cast<int>(south.rsrp[b])) * 0.5);
    }
    return s;
}

Out renderAt(Vec& v, int32_t z, int32_t x, int32_t y, sa::Area tileArea, int32_t grid) {
    v.params.z = z;
    v.params.x = x;
    v.params.y = y;
    v.params.area = tileArea;
    v.params.grid = grid;
    Out o;
    EXPECT_GT(render(v, o), 0);
    return o;
}

// What the tile service did before the fix: one area class per tile, from the site count around the tile's centre.
// Neighbouring tiles on either side of a density step (a town edge) get different classes, so every carrier changes
// path-loss model at the border.
TEST(Seam, PerTileAreaClassBreaksTheSharedEdge) {
    Vec v = loadVec("tile_canberra_z13.json");
    const int32_t z = v.params.z, x = v.params.x, y = v.params.y;
    const Out west = renderAt(v, z, x, y, sa::Area::Urban, 128);
    const Out east = renderAt(v, z, x + 1, y, sa::Area::Rural, 128);
    const EdgeStats s = eastWestEdge(west, east);
    std::printf("[ seam ] per-tile URBAN | RURAL: %d pairs, worst %.1f dB\n", s.pairs, s.worstDb);
    ASSERT_GT(s.pairs, 100);
    EXPECT_GT(s.worstDb, 10.0) << "documents the bug: a class step at the border is a 10+ dB seam";
}

// The fix: the area class is a property of the carrier (rule 5, amended 2026-10-24), so the tile's own class (only the
// fallback for carriers without one) no longer matters and the edge is continuous.
TEST(Seam, PerCarrierAreaClassKeepsTheSharedEdgeContinuous) {
    for (const int32_t grid : {128, 256}) {
        Vec v = loadVec("tile_canberra_z13.json");
        v.area.assign(static_cast<size_t>(v.n), 0);
        for (int32_t i = 0; i < v.n; ++i) v.area[static_cast<size_t>(i)] = static_cast<uint8_t>(i % 4);  // mixed classes
        const int32_t z = v.params.z, x = v.params.x, y = v.params.y;
        const Out west = renderAt(v, z, x, y, sa::Area::Urban, grid);
        const Out east = renderAt(v, z, x + 1, y, sa::Area::Rural, grid);
        const Out south = renderAt(v, z, x, y + 1, sa::Area::Suburban, grid);
        const EdgeStats ew = eastWestEdge(west, east), ns = northSouthEdge(west, south);
        std::printf("[ seam ] grid %d per-carrier: E-W %d pairs worst %.1f dB; N-S %d pairs worst %.1f dB\n", grid, ew.pairs,
                    ew.worstDb, ns.pairs, ns.worstDb);
        ASSERT_GT(ew.pairs, 100);
        ASSERT_GT(ns.pairs, 100);
        EXPECT_LE(ew.worstDb, kMaxEdgeDeltaDb);
        EXPECT_LE(ns.worstDb, kMaxEdgeDeltaDb);
    }
}

// A carrier with an out-of-range class value falls back to the tile's class, exactly as with no array.
TEST(Seam, OutOfRangeCarrierAreaFallsBackToTheTileClass) {
    Vec v = loadVec("tile_canberra_z13.json");
    Out plain;
    ASSERT_EQ(v.evaluated, render(v, plain));
    v.area.assign(static_cast<size_t>(v.n), 0xFF);
    Out fallback;
    ASSERT_EQ(v.evaluated, render(v, fallback));
    EXPECT_EQ(plain.rsrp, fallback.rsrp);
    v.area.assign(static_cast<size_t>(v.n), static_cast<uint8_t>(v.params.area));
    Out explicitArea;
    ASSERT_EQ(v.evaluated, render(v, explicitArea));
    EXPECT_EQ(plain.rsrp, explicitArea.rsrp);
    v.area.assign(static_cast<size_t>(v.n), static_cast<uint8_t>(sa::Area::Urban));
    Out urban;
    ASSERT_EQ(v.evaluated, render(v, urban));
    EXPECT_NE(plain.rsrp, urban.rsrp);  // the rural tile's class really is overridden
}

}  // namespace
