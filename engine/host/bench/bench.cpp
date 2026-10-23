// Micro-benchmark for the per-carrier prepared forms. Built in Release without sanitizers; not a ctest.
#include <chrono>
#include <cstdio>

#include "model/antenna.h"
#include "model/pathloss.h"

namespace {

template <typename F>
double nsPerCall(F&& fn, int n) {
    const auto t0 = std::chrono::steady_clock::now();
    float sink = 0.0f;
    for (int i = 0; i < n; ++i) sink += fn(i);
    const auto t1 = std::chrono::steady_clock::now();
    if (sink == 12345.678f) std::puts("");  // keep the loop observable
    return std::chrono::duration<double, std::nano>(t1 - t0).count() / n;
}

}  // namespace

int main() {
    constexpr int kN = 20000000;
    const sa::AntennaParams ap{90.0f, 65.0f, 25.0f, 17.0f, 3.0f, 30.0f, 1800.0f, nullptr, 0};
    const sa::PreparedAntenna pa = sa::prepareAntenna(ap);
    std::printf("antenna prepared evaluate:    %6.1f ns\n",
                nsPerCall([&](int i) { return pa.evaluate(static_cast<float>(i % 360), 50.0f + (i % 5000)); }, kN));
    std::printf("antenna unprepared wrapper:   %6.1f ns\n",
                nsPerCall([&](int i) { return sa::antennaAttenuation(ap, static_cast<float>(i % 360), 50.0f + (i % 5000)); }, kN / 4));
    const struct { const char* name; float f; sa::Area a; } cases[] = {
        {"hata 900 rural   ", 900.0f, sa::Area::Rural},
        {"cost231 1800 urb ", 1800.0f, sa::Area::Urban},
        {"uma 3500 urban   ", 3500.0f, sa::Area::Urban},
        {"rma 3500 rural   ", 3500.0f, sa::Area::Rural},
        {"umi 26000        ", 26000.0f, sa::Area::Urban},
    };
    for (const auto& c : cases) {
        const sa::PreparedPathLoss pp = sa::preparePathLoss(c.f, 30.0f, c.a);
        std::printf("path loss prepared %s %6.1f ns\n", c.name,
                    nsPerCall([&](int i) { return pp.evaluate(0.05f + (i % 20000) * 0.001f).plDb; }, kN));
        std::printf("path loss unprepared %s %4.1f ns\n", c.name,
                    nsPerCall([&](int i) { return sa::pathLoss(c.f, 0.05f + (i % 20000) * 0.001f, 30.0f, c.a).plDb; }, kN / 4));
    }
    return 0;
}
