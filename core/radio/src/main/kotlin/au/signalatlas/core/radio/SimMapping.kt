package au.signalatlas.core.radio

import au.signalatlas.core.model.Network

/** Radio rule 1: network from the SIM's MCC-MNC (MVNOs carry their host's code). */
object SimMapping {
    private val byMccMnc: Map<String, Network> = buildMap {
        listOf("505-01", "505-11", "505-71", "505-72").forEach { put(it, Network.TELSTRA) }
        listOf("505-02", "505-90").forEach { put(it, Network.OPTUS) }
        listOf("505-03", "505-06", "505-12").forEach { put(it, Network.TPG) }
    }

    /** Accepts "50501", "505-01" or "505 01"; null when unknown. */
    fun networkFor(mccMnc: String?): Network? {
        val digits = mccMnc?.filter { it.isDigit() } ?: return null
        if (digits.length != 5 && digits.length != 6) return null
        return byMccMnc["${digits.substring(0, 3)}-${digits.substring(3)}"]
    }
}
