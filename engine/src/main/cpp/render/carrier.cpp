#include "render/carrier.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>

#include "model/eirp.h"
#include "model/limits.h"

namespace sa {

Carrier prepareCarrier(const CarrierArrays& c, const PatternTable& patterns, int32_t i, Area area) {
    Carrier cr{};
    const uint8_t* hrp = nullptr;
    int16_t peak = 0;
    if (c.hrpId && patterns.att && c.hrpId[i] >= 1 && c.hrpId[i] <= patterns.count) {
        hrp = patterns.att + static_cast<size_t>(c.hrpId[i] - 1) * 360;
        peak = c.hrpPeak ? c.hrpPeak[i] : int16_t{0};
    }
    const AntennaParams ap{c.az[i], c.hBw[i], c.fb[i], c.gain[i], c.tilt[i], c.hTx[i], c.freqMhz[i], hrp, peak};
    cr.ant = prepareAntenna(ap);
    cr.pl = preparePathLoss(c.freqMhz[i], c.hTx[i], area);
    const float nan = std::numeric_limits<float>::quiet_NaN();
    cr.cal = prepareCal(c.calOffsetDb ? c.calOffsetDb[i] : nan, c.calSlope ? c.calSlope[i] : nan,
                        c.calDmedKm ? c.calDmedKm[i] : nan, c.calSigmaScale ? c.calSigmaScale[i] : nan);
    cr.eirpRe = c.eirpRe[i];
    const double dh = sanitizeHeight(c.hTx[i]) - kHRx;
    cr.hd2 = dh * dh;
    cr.hrpMinDb = 0.0;
    if (hrp != nullptr) {
        uint8_t lo = 255;
        for (int32_t q = 0; q < 360; ++q) lo = std::min(lo, hrp[q]);
        cr.hrpMinDb = lo * 0.25;
    }
    const Tech tech = c.tech[i] == 1 ? Tech::NR : Tech::LTE;
    const double scsHz = scsKhz(tech, c.freqMhz[i]) * 1000.0;
    cr.noiseMw = std::pow(10.0, (-174.0 + 10.0 * std::log10(scsHz) + 7.0) / 10.0);
    cr.terrainTable = c.terrainTable ? c.terrainTable[i] : nullptr;
    cr.terrainMask = c.terrainMask ? c.terrainMask[i] : nullptr;
    cr.rasterBits = 0;
    cr.freqKey = c.freqKey[i];
    cr.sectorKey = c.sectorKey[i];
    cr.orig = i;
    cr.group = 0;
    return cr;
}

}  // namespace sa
