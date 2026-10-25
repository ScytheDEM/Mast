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

/**
 * Import rule 12 (tools/acma/README.md); `tools/acma/reference.py` implements the same rule.
 *
 * Name-only and deliberately strict: a site is DAS only when its licence name says so. Low, low-power, wide-beam
 * sites are outdoor small cells (light poles, bus shelters), not DAS, and words such as "station", "plaza",
 * "hospital", "university", "level N" or "tower N" mostly name rooftop macro sites, so none of them count alone.
 * Applied when sites are read as well as at import, so a tightened rule reaches an existing tower DB at once.
 */
object SiteClassifier {
    private val tunnel = Regex("""\b(tunnel|tunl)\b""", RegexOption.IGNORE_CASE)
    private val dasName = Regex(
        """\b(ibc|in[- ]?building|das|distributed antenna|indoor coverage|shopping (centre|center)|westfield""" +
            """|stadium|arena|airport terminal|(railway|train|metro|underground) station|carpark|car park)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun classify(name: String): SiteKind = when {
        tunnel.containsMatchIn(name) -> SiteKind.DAS_TUNNEL
        dasName.containsMatchIn(name) -> SiteKind.DAS_BUILDING
        else -> SiteKind.MACRO
    }
}
