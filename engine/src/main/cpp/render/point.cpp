#include "render/point.h"

#include <cmath>
#include <limits>

#include "render/carrier.h"
#include "render/mercator.h"

namespace sa {

int32_t predictPoint(const CarrierArrays& c, const PatternTable& patterns, double lat, double lon, Area area,
                     bool indoor, float* outRsrp) {
    if (c.n < 0 || c.n > 65535) return kRenderInvalid;
    if (c.n == 0) return 0;
    if (!outRsrp || !c.lat || !c.lon || !c.hTx || !c.az || !c.tilt || !c.eirpRe || !c.freqMhz || !c.hBw || !c.fb ||
        !c.gain || !c.rMaxKm || !c.tech || !c.sectorKey || !c.freqKey) {
        return kRenderInvalid;
    }
    if (!std::isfinite(lat) || !std::isfinite(lon) || std::fabs(lat) > 90.0) return kRenderInvalid;
    for (int32_t i = 0; i < c.n; ++i) {
        if (!carrierUsable(c, i)) {
            outRsrp[i] = std::numeric_limits<float>::quiet_NaN();
            continue;
        }
        const Carrier cr = prepareCarrier(c, patterns, i, area);
        const double d2 = merc::haversineM(c.lat[i], c.lon[i], lat, lon);
        const double brg = merc::bearingDeg(c.lat[i], c.lon[i], lat, lon);
        outRsrp[i] = static_cast<float>(evaluateCarrier(cr, brg, d2, indoor).rsrp);
    }
    return c.n;
}

}  // namespace sa
