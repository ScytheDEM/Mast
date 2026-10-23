// Tile renderer benchmark: z13 tile, 300 carriers (the Canberra vector's carriers replicated with small position
// offsets), grid 128, single thread. Built in Release without sanitizers; not a ctest.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "render/tile.h"
#include "vectors.h"

namespace {

struct Soa {
    std::vector<double> lat, lon;
    std::vector<float> hTx, az, tilt, eirpRe, freqMhz, hBw, fb, gain, rMaxKm;
    std::vector<uint8_t> tech;
    std::vector<int32_t> sectorKey, freqKey, hrpId;
    std::vector<int16_t> hrpPeak;
};

template <typename T>
std::vector<T> as(const std::vector<double>& v) {
    std::vector<T> out(v.size());
    for (size_t i = 0; i < v.size(); ++i) out[i] = static_cast<T>(v[i]);
    return out;
}

// `perCarrierOffset`: every carrier gets its own site (worst case for site sharing); otherwise each replica of the
// base set shifts as one, so sectors of a site stay together.
Soa build(const std::string& json, int count, bool perCarrierOffset) {
    const auto lat = sa_test::jsonArray(json, "lat"), lon = sa_test::jsonArray(json, "lon");
    const auto hTx = as<float>(sa_test::jsonArray(json, "hTx")), az = as<float>(sa_test::jsonArray(json, "az"));
    const auto tilt = as<float>(sa_test::jsonArray(json, "tilt")), eirp = as<float>(sa_test::jsonArray(json, "eirpRe"));
    const auto f = as<float>(sa_test::jsonArray(json, "freqMhz")), bw = as<float>(sa_test::jsonArray(json, "hBw"));
    const auto fb = as<float>(sa_test::jsonArray(json, "fb")), gain = as<float>(sa_test::jsonArray(json, "gain"));
    const auto rmax = as<float>(sa_test::jsonArray(json, "rMaxKm"));
    const auto tech = as<uint8_t>(sa_test::jsonArray(json, "tech"));
    const auto sk = as<int32_t>(sa_test::jsonArray(json, "sectorKey"));
    const auto fk = as<int32_t>(sa_test::jsonArray(json, "freqKey"));
    const size_t base = lat.size();
    Soa s;
    std::srand(7);
    for (int c = 0; c < count; ++c) {
        const size_t i = static_cast<size_t>(c) % base;
        const int copy = c / static_cast<int>(base);
        double dLat = 0.0004 * copy, dLon = -0.0003 * copy;  // ~50 m per replica
        if (perCarrierOffset && copy > 0) {
            dLat = (std::rand() / static_cast<double>(RAND_MAX) - 0.5) * 0.01;  // up to ~550 m
            dLon = (std::rand() / static_cast<double>(RAND_MAX) - 0.5) * 0.01;
        }
        s.lat.push_back(lat[i] + dLat);
        s.lon.push_back(lon[i] + dLon);
        s.hTx.push_back(hTx[i]);
        s.az.push_back(az[i]);
        s.tilt.push_back(tilt[i]);
        s.eirpRe.push_back(eirp[i]);
        s.freqMhz.push_back(f[i]);
        s.hBw.push_back(bw[i]);
        s.fb.push_back(fb[i]);
        s.gain.push_back(gain[i]);
        s.rMaxKm.push_back(rmax[i]);
        s.tech.push_back(tech[i]);
        s.sectorKey.push_back(sk[i] + 100000 * copy);
        s.freqKey.push_back(fk[i]);
        s.hrpId.push_back(0);
        s.hrpPeak.push_back(0);
    }
    return s;
}

// Synthetic HRP: 8 sector-shaped 360-bin patterns (0.25 dB steps, relative to the peak) with narrow deep nulls and a
// weak back lobe, assigned round-robin; the peak bearing is the carrier's azimuth (0 when omni).
std::vector<uint8_t> hrpTable() {
    std::vector<uint8_t> t(8 * 360);
    for (int p = 0; p < 8; ++p) {
        const double w = 50.0 + 8.0 * p;
        for (int k = 0; k < 360; ++k) {
            const double d = std::min(k, 360 - k);
            double q = std::min(12.0 * (d / w) * (d / w), 28.0) * 4.0;
            if (k % 40 >= 37) q += 60.0;
            if (d > 150.0) q = std::max(0.0, q - 40.0);
            t[static_cast<size_t>(p) * 360 + static_cast<size_t>(k)] = static_cast<uint8_t>(std::min(q, 255.0));
        }
    }
    return t;
}

void assignHrp(Soa& s) {
    for (size_t i = 0; i < s.lat.size(); ++i) {
        s.hrpId[i] = 1 + static_cast<int32_t>(i % 8);
        s.hrpPeak[i] = std::isnan(s.az[i]) ? int16_t{0} : static_cast<int16_t>(std::lround(s.az[i]));
    }
}

double runMs(const Soa& s, const sa::RenderParams& p, int reps, int32_t* evaluated, const sa::PatternTable* pat) {
    const sa::CarrierArrays c{static_cast<int32_t>(s.lat.size()), s.lat.data(), s.lon.data(), s.hTx.data(),
                              s.az.data(), s.tilt.data(), s.eirpRe.data(), s.freqMhz.data(), s.hBw.data(),
                              s.fb.data(), s.gain.data(), s.rMaxKm.data(), s.tech.data(), s.sectorKey.data(),
                              s.freqKey.data(), s.hrpId.data(), s.hrpPeak.data()};
    static std::vector<uint8_t> r(sa::kTilePixels), si(sa::kTilePixels), pr(sa::kTilePixels);
    static std::vector<uint16_t> b(sa::kTilePixels);
    sa::TileOut out{r.data(), si.data(), pr.data(), b.data()};
    const sa::PatternTable& table = *pat;
    std::vector<double> times;
    for (int i = 0; i < reps; ++i) {
        const auto t0 = std::chrono::steady_clock::now();
        *evaluated = sa::renderTile(c, table, p, out, nullptr);
        times.push_back(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
    }
    std::sort(times.begin(), times.end());
    return times[times.size() / 2];
}

}  // namespace

int main() {
    const std::string json = sa_test::readFile("tile_canberra_z13.json");
    sa::RenderParams p{13, 7489, 4954, 128, sa::Area::Rural, false, -118.0f};
    int32_t ev = 0;
    struct Case {
        const char* name;
        int count;
        bool perCarrier;
        bool indoor;
        bool hrp;
    } cases[] = {
        {"109 carriers (as in vector)", 109, false, false, false},
        {"300 carriers, replicas shifted as sites", 300, false, false, false},
        {"300 carriers, every carrier its own site", 300, true, false, false},
        {"300 carriers, replicas, indoor", 300, false, true, false},
        {"300 carriers, replicas, HRP", 300, false, false, true},
        {"300 carriers, own sites, HRP", 300, true, false, true},
        {"300 carriers, replicas, HRP, indoor", 300, false, true, true},
    };
    for (const Case& c : cases) {
        Soa s = build(json, c.count, c.perCarrier);
        const std::vector<uint8_t> hrp = hrpTable();
        const sa::PatternTable table{8, hrp.data()};
        if (c.hrp) assignHrp(s);
        p.indoor = c.indoor;
        runMs(s, p, 2, &ev, &table);  // warm up
        std::printf("%-44s z13 grid128 single thread: %7.2f ms median of 15 (evaluated %d)\n", c.name,
                    runMs(s, p, 15, &ev, &table), ev);
    }
    return 0;
}
