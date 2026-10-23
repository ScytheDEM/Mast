package au.signalatlas.core.map

/**
 * The tower the phone is connected to, as the map draws it: a pulse ring on the serving site, a thin line from the
 * user's position to it, and faint dots on the other candidate sites. All positions are (lat, lon).
 */
data class ConnectedHighlight(
    val serving: Pair<Double, Double>,
    val user: Pair<Double, Double>?,
    val neighbours: List<Pair<Double, Double>> = emptyList(),
) {
    /** GeoJSON for the `connected` source: `kind` is "serving", "neighbour" or "line" (a LineString). */
    fun toGeoJson(): String {
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        fun point(p: Pair<Double, Double>, kind: String) =
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[${p.second},${p.first}]},"properties":{"kind":"$kind"}}"""
        sb.append(point(serving, "serving"))
        neighbours.forEach { sb.append(',').append(point(it, "neighbour")) }
        user?.let {
            sb.append(
                """,{"type":"Feature","geometry":{"type":"LineString","coordinates":[[${it.second},${it.first}],[${serving.second},${serving.first}]]},"properties":{"kind":"line"}}""",
            )
        }
        return sb.append("]}").toString()
    }
}
