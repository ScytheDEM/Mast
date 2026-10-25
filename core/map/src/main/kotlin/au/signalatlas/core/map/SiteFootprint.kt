package au.signalatlas.core.map

import au.signalatlas.core.model.Network

/**
 * Where one network's carriers on a selected site reach (their predicted RSRP stays above the threshold), traced
 * around the site with terrain, buildings and antenna height: [ring] is the outline as (lat, lon), closed.
 */
class FootprintShape(val network: Network, val ring: List<Pair<Double, Double>>)

internal object SiteFootprints {
    const val FILL_OPACITY = 0.10f
    const val OUTLINE_OPACITY = 0.7f

    fun toGeoJson(shapes: List<FootprintShape>): String = buildString {
        append("""{"type":"FeatureCollection","features":[""")
        shapes.filter { it.ring.size >= 4 }.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append("""{"type":"Feature","properties":{"net":"""").append(s.network.name).append(""""},""")
            append(""""geometry":{"type":"Polygon","coordinates":[[""")
            s.ring.forEachIndexed { j, (lat, lon) ->
                if (j > 0) append(',')
                append('[').append(lon).append(',').append(lat).append(']')
            }
            append("]]}}")
        }
        append("]}")
    }
}
