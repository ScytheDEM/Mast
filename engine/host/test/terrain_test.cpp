// Terrain stage (rules 1-6) against tools/engine/terrain_reference.py's vectors, plus a brute-force cross-check of
// the incremental polar-table evaluation on rough synthetic terrain.
#include <gtest/gtest.h>

#include <cmath>
#include <cstring>
#include <string>
#include <vector>

#include "terrain/bullington.h"
#include "terrain/dem_grid.h"
#include "terrain/polar_table.h"
#include "terrain/profile.h"
#include "vectors.h"

namespace {

// A DEM decoded from a vector file; keeps the samples alive for the DemGrid view.
struct TestDem {
    std::vector<int16_t> h;
    sa::DemGrid grid{};
};

TestDem demFromJson(const std::string& json) {
    TestDem d;
    const double steps = sa_test::jsonNumber(json, "stepsPerDegree");
    const std::vector<uint8_t> raw = sa_test::base64Decode(sa_test::jsonString(json, "h"));
    d.h.resize(raw.size() / 2);
    for (size_t i = 0; i < d.h.size(); ++i) {
        d.h[i] = static_cast<int16_t>(static_cast<uint16_t>(raw[2 * i] | (raw[2 * i + 1] << 8)));
    }
    d.grid.south = sa_test::jsonNumber(json, "southIndex") / steps;
    d.grid.west = sa_test::jsonNumber(json, "westIndex") / steps;
    d.grid.stepDeg = 1.0 / steps;
    d.grid.rows = static_cast<int32_t>(sa_test::jsonNumber(json, "rows"));
    d.grid.cols = static_cast<int32_t>(sa_test::jsonNumber(json, "cols"));
    d.grid.h = d.h.data();
    return d;
}

// The JSON text of each object that starts with `marker` (e.g. {"name"), up to the next marker.
std::vector<std::string> objectsStartingWith(const std::string& json, const std::string& arrayKey,
                                             const std::string& marker) {
    std::vector<std::string> out;
    size_t at = json.find("\"" + arrayKey + "\"");
    if (at == std::string::npos) return out;
    at = json.find(marker, at);
    while (at != std::string::npos) {
        const size_t next = json.find(marker, at + marker.size());
        out.push_back(json.substr(at, next == std::string::npos ? std::string::npos : next - at));
        at = next;
    }
    return out;
}

struct Diff {
    int32_t maskMismatch = 0, codeOff1 = 0, codeBad = 0;
    int32_t firstBad = -1;
};

// Builds the table for `site` and compares with the reference: masks exactly, codes within +-1.
Diff compareToReference(const sa::DemGrid& dem, double lat, double lon, float hTx, float freq,
                        const std::vector<uint8_t>& refCodes, const std::vector<uint8_t>& refMask) {
    std::vector<uint8_t> codes(sa::kPolarTableCells), mask(sa::kPolarMaskBytes);
    EXPECT_EQ(sa::buildPolarTable(dem, lat, lon, hTx, freq, codes.data(), mask.data(), nullptr), 0);
    Diff d;
    EXPECT_EQ(refCodes.size(), static_cast<size_t>(sa::kPolarTableCells));
    EXPECT_EQ(refMask.size(), static_cast<size_t>(sa::kPolarMaskBytes));
    if (refCodes.size() != codes.size() || refMask.size() != mask.size()) return d;
    for (int32_t k = 0; k < sa::kPolarTableCells; ++k) {
        const int a = (mask[k >> 3] >> (k & 7)) & 1, b = (refMask[k >> 3] >> (k & 7)) & 1;
        if (a != b) ++d.maskMismatch;
        const int diff = std::abs(static_cast<int>(codes[k]) - static_cast<int>(refCodes[k]));
        if (diff == 1) ++d.codeOff1;
        if (diff > 1) {
            ++d.codeBad;
            if (d.firstBad < 0) d.firstBad = k;
        }
    }
    return d;
}

void expectMatches(const Diff& d, const std::string& what) {
    EXPECT_EQ(d.maskMismatch, 0) << what;
    EXPECT_EQ(d.codeBad, 0) << what << ": first bad cell b=" << d.firstBad / 256 << " j=" << d.firstBad % 256;
    // Off-by-one codes come only from ties at a quantisation boundary: well under 0.1% of the cells.
    EXPECT_LT(d.codeOff1, sa::kPolarTableCells / 1000) << what;
}

}  // namespace

// ---------------------------------------------------------------- rules 3-5

TEST(Bullington, KnifeEdgeFunction) {
    // Values from terrain_reference.j_nu.
    EXPECT_NEAR(sa::knifeEdgeJ(0.0), 6.032852208563606, 1e-9);
    EXPECT_NEAR(sa::knifeEdgeJ(1.0), 13.925728934959924, 1e-9);
    EXPECT_NEAR(sa::knifeEdgeJ(-0.5), 1.9592497062281762, 1e-9);
    EXPECT_NEAR(sa::knifeEdgeJ(-0.77), 0.06940616271663202, 1e-9);  // nearly continuous with the zero below -0.78
    EXPECT_EQ(sa::knifeEdgeJ(-0.78), 0.0);
    EXPECT_EQ(sa::knifeEdgeJ(-3.0), 0.0);
}

TEST(Bullington, MatchesProfileVectors) {
    const auto rows = sa_test::readCsv("terrain/profiles.csv");
    ASSERT_EQ(rows.size(), 18u);
    for (const auto& r : rows) {
        ASSERT_EQ(r.size(), 12u);
        std::vector<float> heights;
        for (const std::string& tok : sa_test::split(r[8], ' ')) heights.push_back(static_cast<float>(sa_test::num(tok)));
        const std::string what = r[0] + " " + r[1] + " MHz";
        const sa::TerrainExcess e = sa::bullingtonExcess(
            heights.data(), static_cast<int32_t>(heights.size()), static_cast<float>(sa_test::num(r[3])),
            static_cast<float>(sa_test::num(r[2])), static_cast<float>(sa_test::num(r[4])),
            static_cast<float>(sa_test::num(r[5])), static_cast<float>(sa_test::num(r[6])),
            static_cast<float>(sa_test::num(r[7])), static_cast<float>(sa_test::num(r[1])));
        EXPECT_NEAR(e.lUcActual, sa_test::num(r[9]), 0.05) << what;
        EXPECT_NEAR(e.lUcRef, sa_test::num(r[10]), 0.05) << what;
        EXPECT_NEAR(e.lTerr, sa_test::num(r[11]), 0.05) << what;
        EXPECT_NEAR(sa::bullingtonExcessDb(heights.data(), static_cast<int32_t>(heights.size()),
                                           static_cast<float>(sa_test::num(r[3])), static_cast<float>(sa_test::num(r[2])),
                                           static_cast<float>(sa_test::num(r[4])), static_cast<float>(sa_test::num(r[5])),
                                           static_cast<float>(sa_test::num(r[6])), static_cast<float>(sa_test::num(r[7])),
                                           static_cast<float>(sa_test::num(r[1]))),
                    sa_test::num(r[11]), 0.05)
            << what;
    }
}

TEST(Bullington, InvalidInputGivesZero) {
    const float h[3] = {10.0f, 500.0f, 10.0f};
    EXPECT_EQ(sa::bullingtonExcessDb(nullptr, 3, 90.0f, 180.0f, 10.0f, 10.0f, 30.0f, 1.5f, 1800.0f), 0.0f);
    EXPECT_EQ(sa::bullingtonExcessDb(h, 2, 90.0f, 180.0f, 10.0f, 10.0f, 30.0f, 1.5f, 1800.0f), 0.0f);
    EXPECT_EQ(sa::bullingtonExcessDb(h, 3, 90.0f, 0.0f, 10.0f, 10.0f, 30.0f, 1.5f, 1800.0f), 0.0f);
    EXPECT_EQ(sa::bullingtonExcessDb(h, 3, 90.0f, 180.0f, 10.0f, 10.0f, 30.0f, 1.5f, 0.0f), 0.0f);
    EXPECT_EQ(sa::bullingtonExcessDb(h, 3, 90.0f, 180.0f, NAN, 10.0f, 30.0f, 1.5f, 1800.0f), 0.0f);
    EXPECT_GT(sa::bullingtonExcessDb(h, 3, 90.0f, 180.0f, 10.0f, 10.0f, 30.0f, 1.5f, 1800.0f), 0.0f);  // sanity
}

// ---------------------------------------------------------------- DEM sampling

TEST(DemGrid, BilinearAndBounds) {
    // 3 x 3 nodes: heights 0..8 row-major, row 0 at the south edge.
    const int16_t h[9] = {0, 1, 2, 3, 4, 5, 6, 7, 8};
    const sa::DemGrid g{-35.0, 149.0, 1.0 / 1200.0, 3, 3, h};
    double v = -1.0;
    ASSERT_TRUE(sa::demHeight(g, -35.0, 149.0, &v));
    EXPECT_EQ(v, 0.0);
    ASSERT_TRUE(sa::demHeight(g, -35.0 + 0.5 / 1200.0, 149.0 + 0.5 / 1200.0, &v));
    EXPECT_NEAR(v, 2.0, 1e-9);  // mean of 0, 1, 3, 4
    ASSERT_TRUE(sa::demHeight(g, -35.0 + 2.0 / 1200.0, 149.0 + 2.0 / 1200.0, &v));  // the last node uses the cell below
    EXPECT_NEAR(v, 8.0, 1e-9);
    ASSERT_TRUE(sa::demHeight(g, -35.0 + 1.25 / 1200.0, 149.0 + 1.5 / 1200.0, &v));
    EXPECT_NEAR(v, 4.0 + 0.25 * 3.0 + 0.5, 1e-9);
    v = 99.0;
    EXPECT_FALSE(sa::demHeight(g, -35.0 - 1e-9, 149.0, &v));
    EXPECT_FALSE(sa::demHeight(g, -35.0, 149.0 + 2.01 / 1200.0, &v));
    EXPECT_FALSE(sa::demHeight(g, NAN, 149.0, &v));
    EXPECT_EQ(v, 99.0);
    const sa::DemGrid bad{-35.0, 149.0, 1.0 / 1200.0, 1, 3, h};
    EXPECT_FALSE(sa::demHeight(bad, -35.0, 149.0, &v));
}

// ---------------------------------------------------------------- rule 6 tables

TEST(PolarTable, MatchesSyntheticReferenceTables) {
    const std::string json = sa_test::readFile("terrain/tables.json");
    ASSERT_FALSE(json.empty());
    const TestDem dem = demFromJson(json.substr(0, json.find("\"tables\"")));
    ASSERT_EQ(dem.h.size(), 361u * 361u);
    const auto tables = objectsStartingWith(json, "tables", "{\"name\"");
    ASSERT_EQ(tables.size(), 2u);
    for (const std::string& t : tables) {
        const std::string name = sa_test::jsonString(t, "name");
        const Diff d = compareToReference(
            dem.grid, sa_test::jsonNumber(t, "siteLat"), sa_test::jsonNumber(t, "siteLon"),
            static_cast<float>(sa_test::jsonNumber(t, "hTxAgl")), static_cast<float>(sa_test::jsonNumber(t, "freqMhz")),
            sa_test::base64Decode(sa_test::jsonString(t, "codes")), sa_test::base64Decode(sa_test::jsonString(t, "mask")));
        expectMatches(d, "table " + name);
        RecordProperty("offByOne_" + name, d.codeOff1);
    }
}

TEST(PolarTable, MatchesCanberraTileTables) {
    const std::string json = sa_test::readFile("terrain/tile_canberra_terrain_z12.json");
    const std::string demJson = sa_test::readFile("terrain/dem_canberra_glo90.json");
    ASSERT_FALSE(json.empty());
    ASSERT_FALSE(demJson.empty());
    const TestDem dem = demFromJson(demJson);
    ASSERT_EQ(dem.h.size(), 951u * 1170u);
    const auto tables = objectsStartingWith(json, "tables", "{\"siteLat\"");
    ASSERT_EQ(tables.size(), 32u);
    int64_t off1 = 0;
    for (size_t i = 0; i < tables.size(); ++i) {
        const std::string& t = tables[i];
        const Diff d = compareToReference(
            dem.grid, sa_test::jsonNumber(t, "siteLat"), sa_test::jsonNumber(t, "siteLon"),
            static_cast<float>(sa_test::jsonNumber(t, "hTxAgl")),
            static_cast<float>(sa_test::jsonNumber(t, "freqKeyMhz")),
            sa_test::base64Decode(sa_test::jsonString(t, "codes")), sa_test::base64Decode(sa_test::jsonString(t, "mask")));
        expectMatches(d, "tile table " + std::to_string(i));
        off1 += d.codeOff1;
    }
    RecordProperty("offByOneTotal", static_cast<int>(off1));
}

namespace {

// Deterministic rough terrain: smooth ridges and valleys plus per-node noise.
TestDem roughDem(double southIdx, double westIdx, int32_t rows, int32_t cols) {
    TestDem d;
    d.h.resize(static_cast<size_t>(rows) * cols);
    uint32_t rng = 12345u;
    for (int32_t r = 0; r < rows; ++r) {
        for (int32_t c = 0; c < cols; ++c) {
            rng = rng * 1664525u + 1013904223u;
            const double noise = ((rng >> 16) & 0xFF) / 255.0 * 24.0 - 12.0;
            const double x = c * 0.0925, y = r * 0.0925;  // km
            const double v = 500.0 + 140.0 * std::sin(x / 6.3 + 0.4) * std::cos(y / 8.1) + 90.0 * std::sin(x / 2.9 + y / 3.7) +
                             60.0 * std::sin(y / 17.0 - x / 23.0) + noise;
            d.h[static_cast<size_t>(r) * cols + c] = static_cast<int16_t>(std::lround(v));
        }
    }
    d.grid = {southIdx / 1200.0, westIdx / 1200.0, 1.0 / 1200.0, rows, cols, d.h.data()};
    return d;
}

}  // namespace

// The incremental evaluation (prefix maxima, hull tangent, pruned line-of-sight pass) must agree with the brute-force
// construction on profiles sampled independently for a few radials.
TEST(PolarTable, IncrementalMatchesBruteForceOnRoughTerrain) {
    const TestDem dem = roughDem(-42600.0 - 960.0, 178680.0 - 1100.0, 1920, 2200);
    const double lat = -42600.0 / 1200.0, lon = 178680.0 / 1200.0;
    struct Setup { float hTx, freq; };
    int32_t cells = 0, nonZero = 0, off1 = 0;
    for (const Setup su : {Setup{3.0f, 900.0f}, Setup{40.0f, 3500.0f}}) {
        std::vector<uint8_t> codes(sa::kPolarTableCells), mask(sa::kPolarMaskBytes);
        ASSERT_EQ(sa::buildPolarTable(dem.grid, lat, lon, su.hTx, su.freq, codes.data(), mask.data(), nullptr), 0);
        for (const int b : {0, 37, 123, 211, 300}) {
            for (int j = 0; j < sa::kTerrainBins; ++j) {
                const double r = sa::kTerrainMinRangeM * std::pow(sa::kTerrainMaxRangeM / sa::kTerrainMinRangeM, j / 255.0);
                const int nBelow = static_cast<int>(std::ceil(r / sa::kProfileStepM)) - 1;
                std::vector<float> h;
                bool ok = true;
                for (int i = 0; i <= nBelow + 1 && ok; ++i) {
                    const double dist = i <= nBelow ? i * sa::kProfileStepM : r;
                    const sa::LatLon p = sa::destinationPoint(lat, lon, b, dist);
                    double v = 0.0;
                    ok = sa::demHeight(dem.grid, p.lat, p.lon, &v);
                    h.push_back(static_cast<float>(v));
                }
                const int k = b * sa::kTerrainBins + j;
                ASSERT_TRUE(ok) << "grid too small";
                ASSERT_TRUE((mask[k >> 3] >> (k & 7)) & 1) << b << " " << j;
                const double l = sa::bullingtonExcessDb(h.data(), static_cast<int32_t>(h.size()), 90.0f,
                                                        static_cast<float>(r), h.front(), h.back(), su.hTx, 1.5f, su.freq);
                const int expect = std::min(255, static_cast<int>(std::floor(l / 0.25 + 0.5)));
                ASSERT_LE(std::abs(expect - static_cast<int>(codes[k])), 1)
                    << "hTx " << su.hTx << " b " << b << " j " << j << " brute " << l << " got " << int(codes[k]) * 0.25;
                off1 += expect != codes[k];
                nonZero += codes[k] != 0;
                ++cells;
            }
        }
    }
    EXPECT_GT(nonZero, cells / 4) << "terrain too tame to exercise the construction";
    EXPECT_LT(off1, cells / 500);
}

TEST(PolarTable, OutsideTheGridIsIncompleteAndZero) {
    const TestDem dem = roughDem(0.0, 0.0, 200, 200);  // ~18 km square at (0, 0)
    std::vector<uint8_t> codes(sa::kPolarTableCells, 0xAA), mask(sa::kPolarMaskBytes, 0xAA);
    // Site off the grid: all incomplete.
    ASSERT_EQ(sa::buildPolarTable(dem.grid, 5.0, 5.0, 30.0f, 1800.0f, codes.data(), mask.data(), nullptr), 0);
    for (uint8_t c : codes) ASSERT_EQ(c, 0);
    for (uint8_t m : mask) ASSERT_EQ(m, 0);
    // Site inside: every cell whose end point or profile leaves the grid is masked out, and masked cells hold 0.
    const double lat = 100.0 / 1200.0, lon = 100.0 / 1200.0;
    ASSERT_EQ(sa::buildPolarTable(dem.grid, lat, lon, 30.0f, 1800.0f, codes.data(), mask.data(), nullptr), 0);
    int complete = 0;
    for (int b = 0; b < sa::kTerrainRadials; ++b) {
        bool lost = false;
        for (int j = 0; j < sa::kTerrainBins; ++j) {
            const int k = b * sa::kTerrainBins + j;
            const bool bit = (mask[k >> 3] >> (k & 7)) & 1;
            if (!bit) EXPECT_EQ(codes[k], 0);
            complete += bit;
            if (lost) EXPECT_FALSE(bit) << "complete cell after the profile left the grid: b " << b << " j " << j;
            lost = lost || !bit;
        }
        EXPECT_TRUE((mask[(b * sa::kTerrainBins) >> 3] >> ((b * sa::kTerrainBins) & 7)) & 1) << "first bin, radial " << b;
    }
    EXPECT_GT(complete, 360 * 100);
    EXPECT_LT(complete, sa::kPolarTableCells);
}

TEST(PolarTable, CancelZeroesOutputs) {
    const TestDem dem = roughDem(0.0, 0.0, 200, 200);
    std::vector<uint8_t> codes(sa::kPolarTableCells, 0xAA), mask(sa::kPolarMaskBytes, 0xAA);
    volatile int32_t cancel = 1;
    EXPECT_EQ(sa::buildPolarTable(dem.grid, 100.0 / 1200.0, 100.0 / 1200.0, 30.0f, 1800.0f, codes.data(), mask.data(), &cancel),
              sa::kTerrainCancelled);
    for (uint8_t c : codes) ASSERT_EQ(c, 0);
    for (uint8_t m : mask) ASSERT_EQ(m, 0);
    cancel = 0;
    EXPECT_EQ(sa::buildPolarTable(dem.grid, 100.0 / 1200.0, 100.0 / 1200.0, 30.0f, 1800.0f, codes.data(), mask.data(), &cancel), 0);
}

TEST(PolarTable, InvalidArguments) {
    const TestDem dem = roughDem(0.0, 0.0, 10, 10);
    std::vector<uint8_t> codes(sa::kPolarTableCells, 0x55), mask(sa::kPolarMaskBytes, 0x55);
    EXPECT_EQ(sa::buildPolarTable(dem.grid, 0.0, 0.0, 30.0f, 1800.0f, nullptr, mask.data(), nullptr), sa::kTerrainInvalid);
    EXPECT_EQ(sa::buildPolarTable(dem.grid, 0.0, 0.0, 30.0f, 1800.0f, codes.data(), nullptr, nullptr), sa::kTerrainInvalid);
    sa::DemGrid empty = dem.grid;
    empty.h = nullptr;
    EXPECT_EQ(sa::buildPolarTable(empty, 0.0, 0.0, 30.0f, 1800.0f, codes.data(), mask.data(), nullptr), sa::kTerrainInvalid);
    empty = dem.grid;
    empty.rows = 1;
    EXPECT_EQ(sa::buildPolarTable(empty, 0.0, 0.0, 30.0f, 1800.0f, codes.data(), mask.data(), nullptr), sa::kTerrainInvalid);
    EXPECT_EQ(codes[0], 0x55);  // untouched
}

// ---------------------------------------------------------------- the renderer lookup

TEST(TerrainLookup, MatchesReferenceAndEdgeRules) {
    const std::string json = sa_test::readFile("terrain/tables.json");
    const auto tables = objectsStartingWith(json, "tables", "{\"name\"");
    ASSERT_EQ(tables.size(), 2u);
    const std::vector<uint8_t> codes = sa_test::base64Decode(sa_test::jsonString(tables[0], "codes"));
    const std::vector<uint8_t> mask = sa_test::base64Decode(sa_test::jsonString(tables[0], "mask"));
    ASSERT_EQ(codes.size(), static_cast<size_t>(sa::kPolarTableCells));
    struct Case { float bearing, r; double expect; bool incomplete; };
    // Expected values from terrain_reference.table_lookup on table A.
    const Case cases[] = {
        {10.0f, 123.0f, 0.0, false},
        {10.5f, 1500.0f, 20.5, false},
        {-10.0f, 5000.0f, 12.605451089308403, false},
        {725.5f, 700.0f, 0.05365987228819691, false},
        {120.3f, 12345.6f, 35.77676860984886, false},
        {90.25f, 49.0f, 0.0, false},    // below the first bin
        {45.0f, 81000.0f, 0.0, true},   // beyond the last bin
        {359.5f, 30000.0f, 0.0, true},  // a neighbouring cell lies outside the DEM
        {200.0f, 79999.0f, 0.0, true},
    };
    for (const Case& c : cases) {
        bool incomplete = !c.incomplete;
        const float v = sa::terrainLookup(codes.data(), mask.data(), c.bearing, c.r, &incomplete);
        EXPECT_NEAR(v, c.expect, 1e-3) << c.bearing << " " << c.r;
        EXPECT_EQ(incomplete, c.incomplete) << c.bearing << " " << c.r;
    }
    // The out-param is optional; NaN and a missing table are incomplete.
    EXPECT_EQ(sa::terrainLookup(codes.data(), mask.data(), 10.5f, 1500.0f, nullptr), 20.5f);
    bool inc = false;
    EXPECT_EQ(sa::terrainLookup(codes.data(), mask.data(), NAN, 1500.0f, &inc), 0.0f);
    EXPECT_TRUE(inc);
    EXPECT_EQ(sa::terrainLookup(codes.data(), mask.data(), 10.0f, NAN, &inc), 0.0f);
    EXPECT_TRUE(inc);
    EXPECT_EQ(sa::terrainLookup(nullptr, mask.data(), 10.0f, 1000.0f, &inc), 0.0f);
    EXPECT_TRUE(inc);
}

TEST(TerrainLookup, BilinearInBearingAndLogRangeWithWrap) {
    // A hand-made table: radial 359 all code 8 (2 dB), radial 0 all code 24 (6 dB), complete everywhere.
    std::vector<uint8_t> codes(sa::kPolarTableCells, 0), mask(sa::kPolarMaskBytes, 0xFF);
    for (int j = 0; j < sa::kTerrainBins; ++j) {
        codes[359 * sa::kTerrainBins + j] = 8;
        codes[j] = 24;
    }
    bool inc = true;
    EXPECT_NEAR(sa::terrainLookup(codes.data(), mask.data(), 359.0f, 1000.0f, &inc), 2.0, 1e-6);
    EXPECT_FALSE(inc);
    EXPECT_NEAR(sa::terrainLookup(codes.data(), mask.data(), 359.5f, 1000.0f, &inc), 4.0, 1e-6);  // wraps to radial 0
    EXPECT_NEAR(sa::terrainLookup(codes.data(), mask.data(), 359.75f, 1000.0f, &inc), 5.0, 1e-6);
    EXPECT_NEAR(sa::terrainLookup(codes.data(), mask.data(), 0.0f, 80000.0f, &inc), 6.0, 1e-6);
    EXPECT_FALSE(inc);
    // Bilinear in ln r between two bins: cell values 4 and 8 of one radial at the geometric mean range.
    std::vector<uint8_t> ramp(sa::kPolarTableCells, 0);
    ramp[100] = 4;
    ramp[101] = 8;
    ramp[sa::kTerrainBins + 100] = 4;
    ramp[sa::kTerrainBins + 101] = 8;
    const double rMid = 50.0 * std::pow(1600.0, 100.5 / 255.0);
    EXPECT_NEAR(sa::terrainLookup(ramp.data(), mask.data(), 0.5f, static_cast<float>(rMid), &inc), 1.5, 1e-3);
    // One masked corner makes the whole lookup incomplete.
    mask[(100) >> 3] = static_cast<uint8_t>(mask[100 >> 3] & ~(1u << (100 & 7)));
    EXPECT_EQ(sa::terrainLookup(ramp.data(), mask.data(), 0.5f, static_cast<float>(rMid), &inc), 0.0f);
    EXPECT_TRUE(inc);
}
