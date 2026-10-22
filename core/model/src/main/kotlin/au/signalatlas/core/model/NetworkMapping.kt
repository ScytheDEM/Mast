package au.signalatlas.core.model

object NetworkMapping {
    private val byLicensee: Map<String, Network> = mapOf(
        "TELSTRA LIMITED" to Network.TELSTRA,
        "Telstra 3G Spectrum Holdings Pty Ltd" to Network.TELSTRA,
        "Optus Mobile Pty Limited" to Network.OPTUS,
        "Vodafone Hutchison Australia Pty Limited" to Network.TPG,
        "Vodafone Australia Pty Limited" to Network.TPG,
        "TPG INTERNET PTY LTD" to Network.TPG,
        "MOBILE JV PTY LIMITED" to Network.OPTUS_TPG_JV,
        "NBN CO LIMITED" to Network.NBN,
    ).mapKeys { it.key.lowercase() }

    /** Exact match, case-insensitive after trimming; null when the licensee is not a tracked network. */
    fun fromLicensee(name: String): Network? = byLicensee[name.trim().lowercase()]
}
