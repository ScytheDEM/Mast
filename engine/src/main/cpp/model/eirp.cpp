#include "model/eirp.h"

#include <cmath>

#include "model/stats.h"

namespace sa {
namespace {

struct RbEntry {
    int32_t bwHz;
    int32_t nRb;
};

constexpr RbEntry kLte[] = {{1400000, 6}, {3000000, 15}, {5000000, 25}, {10000000, 50}, {15000000, 75}, {20000000, 100}};
constexpr RbEntry kNr15[] = {{5000000, 25},  {10000000, 52}, {15000000, 79},  {20000000, 106},
                             {25000000, 133}, {30000000, 160}, {40000000, 216}, {50000000, 270}};
constexpr RbEntry kNr30[] = {{10000000, 24},  {15000000, 38},  {20000000, 51},  {25000000, 65},
                             {30000000, 78},  {40000000, 106}, {50000000, 133}, {60000000, 162},
                             {70000000, 189}, {80000000, 217}, {90000000, 245}, {100000000, 273}};
constexpr RbEntry kNr120[] = {{50000000, 32}, {100000000, 66}, {200000000, 132}, {400000000, 264}};

template <size_t N>
int32_t lookup(const RbEntry (&table)[N], int32_t bwHz) {
    for (const RbEntry& e : table) {
        if (e.bwHz == bwHz) return e.nRb;
    }
    return 0;
}

double sanitizeFreq(float f) { return sane(f, 30.0, 100000.0, 1800.0); }

}  // namespace

int32_t scsKhz(Tech tech, float freqMhz) {
    const double f = sanitizeFreq(freqMhz);
    if (tech != Tech::NR || f < 2300.0) return 15;
    return f >= 24250.0 ? 120 : 30;
}

int32_t numResourceBlocks(Tech tech, float freqMhz, int32_t bandwidthHz) {
    const int32_t scs = scsKhz(tech, freqMhz);
    int32_t n = 0;
    if (tech != Tech::NR) {
        n = lookup(kLte, bandwidthHz);
    } else if (scs == 15) {
        n = lookup(kNr15, bandwidthHz);
    } else if (scs == 30) {
        n = lookup(kNr30, bandwidthHz);
    } else {
        n = lookup(kNr120, bandwidthHz);
    }
    if (n > 0) return n;
    // Not a table bandwidth (including 0 and negatives): floor(BW * 0.9 / (12 SCS)), minimum 6.
    const double fallback = std::floor(static_cast<double>(bandwidthHz) * 0.9 / (12.0 * scs * 1000.0));
    return fallback < 6.0 ? 6 : static_cast<int32_t>(fallback);
}

float eirpPerRe(float eirpDbm, Tech tech, float freqMhz, int32_t bandwidthHz) {
    // Double: the reference is double and the log term must not add float rounding beyond the output cast.
    const double eirp = sane(eirpDbm, -300.0, 300.0, -300.0);
    const int32_t nRb = numResourceBlocks(tech, freqMhz, bandwidthHz);
    return static_cast<float>(eirp - 10.0 * std::log10(12.0 * nRb));
}

}  // namespace sa
