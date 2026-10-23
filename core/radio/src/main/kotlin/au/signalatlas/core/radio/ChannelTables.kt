package au.signalatlas.core.radio

import kotlin.math.roundToLong

/** Channel number to downlink frequency. LTE per TS 36.101 Table 5.7.3-1, NR per TS 38.104 section 5.4.2.1. */
object ChannelTables {
    class LteBand(val name: String, val fDlLowMhz: Double, val nOffsDl: Int, val nDlMax: Int)

    val lteBands: List<LteBand> = listOf(
        LteBand("B1", 2110.0, 0, 599),
        LteBand("B3", 1805.0, 1200, 1949),
        LteBand("B5", 869.0, 2400, 2649),
        LteBand("B7", 2620.0, 2750, 3449),
        LteBand("B8", 925.0, 3450, 3799),
        LteBand("B26", 859.0, 8690, 9039),
        LteBand("B28", 758.0, 9210, 9659),
        LteBand("B40", 2300.0, 38650, 39649),
        LteBand("B42", 3400.0, 41590, 43589),
    )

    class LteChannel(val band: String, val freqMhz: Double)

    fun lte(earfcn: Int): LteChannel? {
        val b = lteBands.firstOrNull { earfcn >= it.nOffsDl && earfcn <= it.nDlMax } ?: return null
        return LteChannel(b.name, round3(b.fDlLowMhz + 0.1 * (earfcn - b.nOffsDl)))
    }

    const val NR_ARFCN_MAX = 3279165

    /** Global frequency raster; null outside 0..3279165. */
    fun nrFreqMhz(nrarfcn: Int): Double? = when {
        nrarfcn < 0 || nrarfcn > NR_ARFCN_MAX -> null
        nrarfcn < 600000 -> round3(0.005 * nrarfcn)
        nrarfcn < 2016667 -> round3(3000.0 + 0.015 * (nrarfcn - 600000))
        else -> round3(24250.08 + 0.06 * (nrarfcn - 2016667))
    }

    // Kilohertz resolution is exact on every raster; this removes binary-float noise.
    private fun round3(v: Double) = (v * 1000).roundToLong() / 1000.0
}
