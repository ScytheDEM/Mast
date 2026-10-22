package au.signalatlas.core.map

import au.signalatlas.core.model.SiteSummary
import au.signalatlas.core.model.Tech
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import java.math.BigDecimal
import kotlin.math.abs

/** Builds the GeoJSON fed to the `sites` source. Call off the main thread for large lists. */
object TowerLayer {
    /**
     * One Point per site with properties `id`, `net` (primary network: the first in enum order),
     * `n` (network count) and `nr` (whether the site has any NR carrier).
     */
    fun featureCollection(sites: List<SiteSummary>): String {
        val sb = StringBuilder(64 + sites.size * 120)
        sb.append("""{"type":"FeatureCollection","features":[""")
        sites.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            val primary = s.networks.minByOrNull { it.ordinal }
            sb.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[""")
                .append(num(s.lon)).append(',').append(num(s.lat))
                .append("""]},"properties":{"id":""").append(s.id)
                .append(""","net":""").append(if (primary == null) "null" else "\"${primary.name}\"")
                .append(""","n":""").append(s.networks.size)
                .append(""","nr":""").append(Tech.NR in s.techs)
                .append("}}")
        }
        return sb.append("]}").toString()
    }

    /** Filter for the selected-site layer: just the feature whose `id` is [selectedId], or nothing. */
    fun selectedFilter(selectedId: Int?): Expression =
        if (selectedId == null) literal(false) else eq(get("id"), literal(selectedId))

    /** JSON number without exponent notation (Kotlin prints 1.0E-4 for tiny values). */
    private fun num(d: Double): String =
        if (d == 0.0 || abs(d) in 1e-3..<1e7) d.toString() else BigDecimal(d).toPlainString()
}
