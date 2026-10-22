package au.signalatlas.core.model

object BandPlan {
    private class Range(val lowMhz: Double, val highMhz: Double, val band: String, val tech: Tech)

    // Inclusive MHz ranges, first match wins.
    private val ranges = listOf(
        Range(758.0, 803.0, "B28", Tech.LTE),
        Range(859.0, 868.999, "B26", Tech.LTE),
        Range(869.0, 894.0, "B5", Tech.LTE),
        Range(925.0, 960.0, "B8", Tech.LTE),
        Range(1805.0, 1880.0, "B3", Tech.LTE),
        Range(2110.0, 2170.0, "B1", Tech.LTE),
        Range(2300.0, 2400.0, "B40", Tech.LTE),
        Range(2620.0, 2690.0, "B7", Tech.LTE),
        Range(3300.0, 3800.0, "n78", Tech.NR),
        Range(3800.001, 4200.0, "n77", Tech.NR),
        Range(24250.0, 27499.999, "n258", Tech.NR),
        Range(27500.0, 29500.0, "n257", Tech.NR),
    )

    fun classify(freqHz: Long, network: Network): BandInfo? {
        val mhz = freqHz / 1e6
        val r = ranges.firstOrNull { mhz >= it.lowMhz && mhz <= it.highMhz } ?: return null
        // Telstra runs 850 MHz as NR (n5, n26); everyone else as LTE (B5, B26).
        if (network == Network.TELSTRA) {
            if (r.band == "B5") return BandInfo("n5", Tech.NR)
            if (r.band == "B26") return BandInfo("n26", Tech.NR)
        }
        return BandInfo(r.band, r.tech)
    }
}
