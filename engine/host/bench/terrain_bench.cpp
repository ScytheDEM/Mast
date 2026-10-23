// Polar-table benchmark (terrain rule 6). Built in Release without sanitizers; not a ctest.
// Builds the 360 x 256 table for (a) a synthetic rough 1.7 x 2 degree DEM, where every radial reaches 80 km, and
// (b) the real Canberra GLO-90 crop of the terrain vectors (radials leave the crop at 40-50 km), and times the
// renderer lookup. Single thread; median of 15 runs.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "terrain/dem_grid.h"
#include "terrain/polar_table.h"
#include "vectors.h"

namespace {

using Clock = std::chrono::steady_clock;

double medianMs(std::vector<double> v) {
    std::sort(v.begin(), v.end());
    return v[v.size() / 2];
}

template <typename F>
double timeMs(F&& fn, int runs, double* worst) {
    std::vector<double> t;
    for (int i = 0; i < runs; ++i) {
        const auto t0 = Clock::now();
        fn();
        t.push_back(std::chrono::duration<double, std::milli>(Clock::now() - t0).count());
    }
    *worst = *std::max_element(t.begin(), t.end());
    return medianMs(t);
}

int32_t countComplete(const std::vector<uint8_t>& mask) {
    int32_t n = 0;
    for (uint8_t m : mask) n += __builtin_popcount(m);
    return n;
}

void report(const char* name, const sa::DemGrid& dem, double lat, double lon, float hTx, float freq) {
    std::vector<uint8_t> codes(sa::kPolarTableCells), mask(sa::kPolarMaskBytes);
    sa::buildPolarTable(dem, lat, lon, hTx, freq, codes.data(), mask.data(), nullptr);  // warm-up
    double worst = 0.0;
    const double med = timeMs([&] { sa::buildPolarTable(dem, lat, lon, hTx, freq, codes.data(), mask.data(), nullptr); }, 15, &worst);
    int32_t nz = 0;
    for (uint8_t c : codes) nz += c != 0;
    std::printf("%-44s median %6.1f ms  worst %6.1f ms  (%d of %d cells complete, %d non-zero)\n", name, med, worst,
                countComplete(mask), sa::kPolarTableCells, nz);
}

}  // namespace

int main() {
    {
        const int32_t rows = 2000, cols = 2300;
        std::vector<int16_t> h(static_cast<size_t>(rows) * cols);
        uint32_t rng = 12345u;
        for (int32_t r = 0; r < rows; ++r) {
            for (int32_t c = 0; c < cols; ++c) {
                rng = rng * 1664525u + 1013904223u;
                const double noise = ((rng >> 16) & 0xFF) / 255.0 * 24.0 - 12.0;
                const double x = c * 0.0925, y = r * 0.0925;
                h[static_cast<size_t>(r) * cols + c] = static_cast<int16_t>(std::lround(
                    500.0 + 140.0 * std::sin(x / 6.3 + 0.4) * std::cos(y / 8.1) + 90.0 * std::sin(x / 2.9 + y / 3.7) +
                    60.0 * std::sin(y / 17.0 - x / 23.0) + noise));
            }
        }
        const sa::DemGrid dem{-35.9, 148.1, 1.0 / 1200.0, rows, cols, h.data()};
        const double lat = dem.south + 1000.0 / 1200.0, lon = dem.west + 1150.0 / 1200.0;
        report("synthetic rough, 30 m, 1800 MHz", dem, lat, lon, 30.0f, 1800.0f);
        report("synthetic rough, 3 m, 700 MHz", dem, lat, lon, 3.0f, 700.0f);
        report("synthetic rough, 45 m, 3500 MHz", dem, lat, lon, 45.0f, 3500.0f);
        std::vector<uint8_t> codes(sa::kPolarTableCells), mask(sa::kPolarMaskBytes);
        sa::buildPolarTable(dem, lat, lon, 30.0f, 1800.0f, codes.data(), mask.data(), nullptr);
        const int kN = 20000000;
        float sink = 0.0f;
        const auto t0 = Clock::now();
        for (int i = 0; i < kN; ++i) {
            bool inc;
            sink += sa::terrainLookup(codes.data(), mask.data(), static_cast<float>(i % 3600) * 0.1f,
                                      60.0f + static_cast<float>((i * 7) % 79000), &inc);
        }
        const double ns = std::chrono::duration<double, std::nano>(Clock::now() - t0).count() / kN;
        if (sink == 12345.678f) std::puts("");
        std::printf("terrainLookup: %.1f ns per call\n", ns);
    }
    {
        const std::string demJson = sa_test::readFile("terrain/dem_canberra_glo90.json");
        const std::vector<uint8_t> raw = sa_test::base64Decode(sa_test::jsonString(demJson, "h"));
        std::vector<int16_t> h(raw.size() / 2);
        for (size_t i = 0; i < h.size(); ++i) h[i] = static_cast<int16_t>(static_cast<uint16_t>(raw[2 * i] | (raw[2 * i + 1] << 8)));
        const double steps = sa_test::jsonNumber(demJson, "stepsPerDegree");
        const sa::DemGrid dem{sa_test::jsonNumber(demJson, "southIndex") / steps, sa_test::jsonNumber(demJson, "westIndex") / steps,
                              1.0 / steps, static_cast<int32_t>(sa_test::jsonNumber(demJson, "rows")),
                              static_cast<int32_t>(sa_test::jsonNumber(demJson, "cols")), h.data()};
        report("Canberra GLO-90 crop, 27 m, 2130 MHz", dem, -35.298383, 149.130877, 27.0f, 2130.0f);
        report("Canberra GLO-90 crop, 16 m, 880 MHz", dem, -35.308428, 149.133846, 16.0f, 880.0f);
    }
    return 0;
}
