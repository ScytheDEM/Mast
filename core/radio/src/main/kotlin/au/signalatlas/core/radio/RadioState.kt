package au.signalatlas.core.radio

import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech

enum class Permission { GRANTED, MISSING_PHONE, MISSING_LOCATION }

data class SimInfo(
    val subId: Int,
    /** Null when the MCC-MNC is not one of the tracked Australian networks. */
    val network: Network?,
    /** From SubscriptionInfo.carrierName, e.g. "Boost" or "amaysim". */
    val carrierName: String?,
    val mccMnc: String?,
    val roaming: Boolean,
)

/** Every numeric field is null when the modem reported a sentinel or an out-of-range value. */
data class ObservedCell(
    val tech: Tech,
    val channel: Int?,
    val freqMhz: Double?,
    val band: String?,
    val pci: Int?,
    val cellId: Long?,
    val tac: Int?,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val taMeters: Double?,
    val registered: Boolean,
)

data class RadioState(
    val permission: Permission,
    val sim: SimInfo?,
    val serving: ObservedCell?,
    val neighbours: List<ObservedCell>,
    val nrSecondaries: List<ObservedCell>,
    /** LTE serving cell with an NR NSA / advanced display override: the phone shows "5G". */
    val displayFiveG: Boolean,
    val airplane: Boolean,
    val updatedAtElapsedMs: Long,
) {
    companion object {
        val EMPTY = RadioState(Permission.GRANTED, null, null, emptyList(), emptyList(), false, false, 0L)
    }
}
