package au.signalatlas.core.radio

import android.os.Build
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyDisplayInfo
import au.signalatlas.core.model.BandPlan
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech

/** Android CellInfo to ObservedCell. Every vendor sentinel becomes null. */
internal object CellMapper {
    fun map(info: CellInfo): ObservedCell? = try {
        when (info) {
            is CellInfoLte -> lte(info)
            is CellInfoNr -> nr(info)
            else -> null // GSM / WCDMA / CDMA: not shown
        }
    } catch (_: ClassCastException) {
        null // Some vendor builds hand back a mismatched identity
    }

    private fun lte(info: CellInfoLte): ObservedCell {
        val id: CellIdentityLte = info.cellIdentity
        val ss = info.cellSignalStrength
        val channel = Sentinels.earfcn(id.earfcn)
        val table = channel?.let { ChannelTables.lte(it) }
        val apiBand = if (Build.VERSION.SDK_INT >= 30) id.bands.firstOrNull()?.let { "B$it" } else null
        return ObservedCell(
            tech = Tech.LTE,
            channel = channel,
            freqMhz = table?.freqMhz,
            band = apiBand ?: table?.band,
            pci = Sentinels.pciLte(id.pci),
            cellId = Sentinels.eci(id.ci),
            tac = Sentinels.tacLte(id.tac),
            rsrp = Sentinels.rsrp(ss.rsrp),
            rsrq = Sentinels.rsrq(ss.rsrq),
            sinr = Sentinels.sinr(ss.rssnr),
            taMeters = Sentinels.lteTaMeters(ss.timingAdvance),
            registered = info.isRegistered,
        )
    }

    private fun nr(info: CellInfoNr): ObservedCell {
        val id = info.cellIdentity as CellIdentityNr
        val ss = info.cellSignalStrength as CellSignalStrengthNr
        val channel = Sentinels.nrarfcn(id.nrarfcn)
        val freq = channel?.let { ChannelTables.nrFreqMhz(it) }
        val apiBand = if (Build.VERSION.SDK_INT >= 30) id.bands.firstOrNull()?.let { "n$it" } else null
        return ObservedCell(
            tech = Tech.NR,
            channel = channel,
            freqMhz = freq,
            band = apiBand ?: freq?.let { nrBandFromFreq(it) },
            pci = Sentinels.pciNr(id.pci),
            cellId = Sentinels.nci(id.nci),
            tac = Sentinels.tacNr(id.tac),
            rsrp = Sentinels.rsrp(ss.ssRsrp) ?: Sentinels.rsrp(ss.csiRsrp),
            rsrq = Sentinels.rsrq(ss.ssRsrq) ?: Sentinels.rsrq(ss.csiRsrq),
            sinr = Sentinels.sinr(ss.ssSinr) ?: Sentinels.sinr(ss.csiSinr),
            taMeters = null, // NR TA is not used in M4
            registered = info.isRegistered,
        )
    }

    // The network argument only matters for Telstra's 850 MHz labelling, which is rewritten below anyway.
    private fun nrBandFromFreq(mhz: Double): String? =
        BandPlan.classify((mhz * 1e6).toLong(), Network.OPTUS)?.band?.let { "n" + it.trimStart('B', 'n') }

    /** Rule 4: an LTE anchor plus an NR NSA / advanced display override shows "5G". */
    fun displayFiveG(serving: ObservedCell?, overrideNetworkType: Int): Boolean =
        serving?.tech == Tech.LTE && (
            overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED
            )

    class Split(val serving: ObservedCell?, val neighbours: List<ObservedCell>, val nrSecondaries: List<ObservedCell>)

    /**
     * Serving = the registered LTE cell (NSA anchor), else the registered NR cell (SA).
     * NR cells other than the serving one are secondaries; the rest of the serving tech are neighbours.
     */
    fun split(cells: List<ObservedCell>): Split {
        val serving = cells.firstOrNull { it.registered && it.tech == Tech.LTE }
            ?: cells.firstOrNull { it.registered && it.tech == Tech.NR }
        val others = cells.filter { it !== serving }
        val servingTech = serving?.tech ?: Tech.LTE
        val nrSecondaries = if (servingTech == Tech.LTE) others.filter { it.tech == Tech.NR } else emptyList()
        val neighbours = others.filter { it.tech == servingTech }
        return Split(serving, neighbours, nrSecondaries)
    }
}
