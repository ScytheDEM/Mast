package au.signalatlas.core.model

/** Tower DB `site.site_kind` (schema v2); the column stores [ordinal]. */
enum class SiteKind {
    MACRO,
    DAS_BUILDING,
    DAS_TUNNEL,
    ;

    val isDas: Boolean get() = this != MACRO

    companion object {
        fun fromOrdinal(i: Int): SiteKind = entries.getOrElse(i) { MACRO }
    }
}

/** Import rule 12 (tools/acma/README.md); `tools/acma/reference.py` implements the same rule. */
object SiteClassifier {
    private val tunnel = Regex("""\b(tunnel|tunl)\b""", RegexOption.IGNORE_CASE)
    private val dasName = Regex(
        """\b(ibc|in[- ]?building|das|distributed antenna|shopping (centre|center)|westfield|stadium|arena|station""" +
            """|airport terminal|hospital|university|plaza|tower \d+(?!\.\d)|level \d+(?!\.\d)|carpark)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Blanked out of the name before [dasName]: macro structure types and street / rural "station" uses. */
    private val dasNameNeg = Regex(
        """\b(lattice|monopole|guyed|mast|pole|steel|\d+ ?m) tower\b""" +
            """|\bstation (road|rd|street|st|avenue|ave|pier|drive|dr|lane|ln|way|parade|pde|creek|hill|point)\b""" +
            """|\b(repeater|pump|pumping|fire|police|petrol|service|power|cattle|sheep|weather) station\b""",
        RegexOption.IGNORE_CASE,
    )

    /** [allWide]: every sector is omni (no azimuth) or has an antenna with horizontal beamwidth >= 90. */
    fun classify(name: String, maxHeightM: Double, maxEirpDbm: Double, allWide: Boolean): SiteKind = when {
        tunnel.containsMatchIn(name) -> SiteKind.DAS_TUNNEL
        dasName.containsMatchIn(dasNameNeg.replace(name, " ")) ||
            (maxHeightM <= 6.0 && maxEirpDbm <= 40.0 && allWide) -> SiteKind.DAS_BUILDING
        else -> SiteKind.MACRO
    }
}
