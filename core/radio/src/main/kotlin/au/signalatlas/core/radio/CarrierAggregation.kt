package au.signalatlas.core.radio

import android.telephony.TelephonyDisplayInfo
import au.signalatlas.core.model.Tech

/**
 * Carrier aggregation as an ordinary app can see it: `ServiceState.getCellBandwidths()` (kHz per aggregated component
 * carrier). The secondary carriers' bands need the privileged READ_PRECISE_PHONE_STATE and are not available.
 */
data class CarrierAggregation(val bandwidthsKhz: List<Int>) {
    /** Number of aggregated component carriers (primary included). */
    val count: Int get() = bandwidthsKhz.size
    val totalKhz: Int get() = bandwidthsKhz.sum()
    val totalMhz: Int get() = (totalKhz / 1000.0).let { Math.round(it).toInt() }

    /** True when more than one component carrier is aggregated. */
    val isAggregated: Boolean get() = count > 1

    /** "CA ×3 · 60 MHz". */
    val text: String get() = "CA ×$count · $totalMhz MHz"

    companion object {
        /** Null for a missing array, no positive bandwidth, or a single unaggregated carrier is still reported (count 1). */
        fun from(cellBandwidths: IntArray?): CarrierAggregation? {
            val bw = cellBandwidths?.filter { it > 0 && it != Int.MAX_VALUE }.orEmpty()
            return if (bw.isEmpty()) null else CarrierAggregation(bw)
        }
    }
}

/** The label the phone's status bar would show. */
enum class DisplayLabel(val text: String) {
    LTE("LTE"), LTE_A("LTE-A"), FIVE_G("5G"), FIVE_G_PLUS("5G+");

    companion object {
        /**
         * From the serving cell's tech, `TelephonyDisplayInfo.getOverrideNetworkType()` and the aggregation: an NR
         * advanced override is 5G+, any NR (SA, or NSA through the override) is 5G, LTE-CA / LTE-Advanced-Pro
         * (or more than one aggregated LTE carrier) is LTE-A.
         */
        fun of(tech: Tech?, override: Int, ca: CarrierAggregation?): DisplayLabel? {
            if (tech == null) return null
            @Suppress("DEPRECATION")
            return when {
                override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED ||
                    override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE -> FIVE_G_PLUS
                override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA || tech == Tech.NR -> FIVE_G
                override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA ||
                    override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO ||
                    ca?.isAggregated == true -> LTE_A
                else -> LTE
            }
        }
    }
}
