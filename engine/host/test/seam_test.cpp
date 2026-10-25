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
#include "terrain/dem_grid.h"
#include "terrain/polar_table.h"
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

// Rule 9 as amended 2026-10-24: the 128 grid's outermost pixels are extrapolated, not clamped, so next to a site (steep
// field) the step across a tile border is no larger than the field's own pixel-to-pixel gradient there.
TEST(Seam, EdgeStepNearASiteFollowsTheLocalGradient) {
    Vec v = loadVec("tile_canberra_z13.json");
    v.n = 1;  // the first carrier only, moved 40 m west of a z16 tile border
    v.area.assign(1, static_cast<uint8_t>(sa::Area::Urban));
    v.az[0] = std::nanf("");  // omni
    const int32_t z = 16;
    const double n = std::ldexp(1.0, z);
    const int32_t x = static_cast<int32_t>(std::floor((v.lon[0] + 180.0) / 360.0 * n));
    const double borderLon = (x + 1) / n * 360.0 - 180.0;
    v.lon[0] = borderLon - 40.0 / (111195.0 * std::cos(v.lat[0] * M_PI / 180.0));
    const double latR = v.lat[0] * M_PI / 180.0;
    const int32_t y = static_cast<int32_t>(std::floor((1.0 - std::log(std::tan(latR) + 1.0 / std::cos(latR)) / M_PI) / 2.0 * n));
    const Out west = renderAt(v, z, x, y, sa::Area::Rural, 128);
    const Out east = renderAt(v, z, x + 1, y, sa::Area::Rural, 128);
    int compared = 0, worse = 0;
    double worstExcess = 0.0;
    for (int row = 0; row < 256; ++row) {
        const size_t r = static_cast<size_t>(row) * 256;
        if (west.rsrp[r + 255] == 0 || east.rsrp[r] == 0) continue;
        const double step = std::abs(west.rsrp[r + 255] - east.rsrp[r]) * 0.5;
        const double inner = std::max(std::abs(west.rsrp[r + 254] - west.rsrp[r + 253]), std::abs(east.rsrp[r + 1] - east.rsrp[r + 2])) * 0.5;
        ++compared;
        worstExcess = std::max(worstExcess, step - inner);
        if (step > inner + 0.5) ++worse;
    }
    std::printf("[ seam ] near-site z16 edge: %d rows, %d steeper than the local gradient (worst excess %.1f dB)\n", compared,
                worse, worstExcess);
    ASSERT_GT(compared, 100);
    EXPECT_EQ(worse, 0);
}

// M3b (seam report H3): with terrain, a carrier serving both sides of a z10 border must render with its terrain table on
// both sides. The tables are now global (one per site key, built from the site's home DEM), so both regions hand the
// renderer the same table and the edge stays continuous; before, one region could lack the table (per-region budget),
// which left a step of L_terr at the border. A ridge between the site and the border makes L_terr large there.
TEST(Seam, TerrainEdgeAcrossAZ10BorderIsContinuousWithOneSharedTable) {
    Vec v = loadVec("tile_canberra_z13.json");
    v.n = 1;
    v.area.assign(1, static_cast<uint8_t>(sa::Area::Urban));
    v.az[0] = std::nanf("");  // omni
    v.eirpRe[0] += 20.0f;  // strong enough to cover both tiles behind the ridge
    const int32_t z10x = static_cast<int32_t>(std::floor((v.lon[0] + 180.0) / 360.0 * 1024.0));
    const double borderLon = (z10x + 1) / 1024.0 * 360.0 - 180.0;
    const double mPerDegLon = 111195.0 * std::cos(v.lat[0] * M_PI / 180.0);
    v.lon[0] = borderLon - 1500.0 / mPerDegLon;  // 1.5 km west of the z10 border
    const double ridgeLon = v.lon[0] + 700.0 / mPerDegLon;

    // Flat 600 m DEM, 0.2 degrees around the site, with a 15 m N-S ridge 0.7 km east of it.
    constexpr int32_t kN = 481;
    const double south = std::floor((v.lat[0] - 0.1) * 1200.0) / 1200.0, west = std::floor((v.lon[0] - 0.1) * 1200.0) / 1200.0;
    std::vector<int16_t> h(static_cast<size_t>(kN) * kN, 600);
    for (int32_t r = 0; r < kN; ++r) {
        for (int32_t c = 0; c < kN; ++c) {
            if (std::abs(west + c / 1200.0 - ridgeLon) * mPerDegLon < 150.0) h[static_cast<size_t>(r) * kN + c] = 615;
        }
    }
    const sa::DemGrid dem{south, west, 1.0 / 1200.0, kN, kN, h.data()};
    std::vector<uint8_t> table(sa::kPolarTableCells), mask(sa::kPolarMaskBytes);
    ASSERT_EQ(sa::buildPolarTable(dem, v.lat[0], v.lon[0], v.hTx[0], v.freqMhz[0], table.data(), mask.data(), nullptr), 0);
    v.terrainTable.assign(1, table.data());
    v.terrainMask.assign(1, mask.data());

    const int32_t z = 13, x = z10x * 8 + 7;  // the z13 tiles either side of the z10 border
    const double latR = v.lat[0] * M_PI / 180.0;
    const int32_t y = static_cast<int32_t>(std::floor((1.0 - std::log(std::tan(latR) + 1.0 / std::cos(latR)) / M_PI) / 2.0 * 8192.0));
    const Out west13 = renderAt(v, z, x, y, sa::Area::Urban, 128);
    const Out east13 = renderAt(v, z, x + 1, y, sa::Area::Urban, 128);
    const EdgeStats shared = eastWestEdge(west13, east13);
    ASSERT_GT(west13.covered, 0);
    EXPECT_EQ(west13.incomplete, 0);
    EXPECT_EQ(east13.incomplete, 0);

    v.terrainTable.assign(1, nullptr);  // the old failure: the east region never got this carrier's table
    v.terrainMask.assign(1, nullptr);
    const Out eastQuick = renderAt(v, z, x + 1, y, sa::Area::Urban, 128);
    const EdgeStats mixed = eastWestEdge(west13, eastQuick);
    std::printf("[ seam ] z10 border with terrain: shared table worst %.1f dB (%d pairs); table on one side only %.1f dB\n",
                shared.worstDb, shared.pairs, mixed.worstDb);
    ASSERT_GT(shared.pairs, 100);
    EXPECT_LE(shared.worstDb, kMaxEdgeDeltaDb);
    EXPECT_GT(mixed.worstDb, 5.0) << "documents the bug: a table on one side only is an L_terr step";
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
