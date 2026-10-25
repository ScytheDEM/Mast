package au.signalatlas.core.geo.packs

import au.signalatlas.core.model.GeoBounds

enum class PackKind { STATE, METRO, CORRIDOR, CUSTOM }

/**
 * A pack that can be downloaded. [basemapFill] is the share of the area's tile count that really holds data
 * (a state's bounding box is mostly empty outback and ocean), used only for the size estimate.
 */
data class PackDef(
    val id: String,
    val name: String,
    val kind: PackKind,
    val area: PackArea,
    val basemapFill: Double = 1.0,
)

object PackCatalogue {
    private fun box(id: String, name: String, kind: PackKind, s: Double, w: Double, n: Double, e: Double, fill: Double = 1.0) =
        PackDef(id, name, kind, PackArea.Box(GeoBounds(s, w, n, e)), fill)

    private fun road(id: String, name: String, vararg pts: Double) =
        PackDef(id, name, PackKind.CORRIDOR, PackArea.Corridor(pts.toList().chunked(2).map { LatLon(it[0], it[1]) }))

    val states = listOf(
        box("state-nsw", "New South Wales", PackKind.STATE, -37.51, 140.99, -28.16, 153.64, 0.35),
        box("state-vic", "Victoria", PackKind.STATE, -39.20, 140.96, -33.98, 150.03, 0.5),
        box("state-qld", "Queensland", PackKind.STATE, -29.18, 138.00, -9.14, 153.55, 0.3),
        box("state-sa", "South Australia", PackKind.STATE, -38.06, 129.00, -26.00, 141.00, 0.3),
        box("state-wa", "Western Australia", PackKind.STATE, -35.13, 112.92, -13.69, 129.00, 0.25),
        box("state-tas", "Tasmania", PackKind.STATE, -43.64, 143.82, -39.58, 148.50, 0.5),
        box("state-nt", "Northern Territory", PackKind.STATE, -26.00, 129.00, -10.97, 138.00, 0.25),
        box("state-act", "Australian Capital Territory", PackKind.STATE, -35.92, 148.76, -35.12, 149.40, 0.8),
    )

    val metros = listOf(
        box("metro-sydney", "Sydney", PackKind.METRO, -34.20, 150.60, -33.45, 151.35),
        box("metro-melbourne", "Melbourne", PackKind.METRO, -38.20, 144.50, -37.45, 145.55),
        box("metro-brisbane", "Brisbane", PackKind.METRO, -27.80, 152.65, -27.05, 153.45),
        box("metro-perth", "Perth", PackKind.METRO, -32.45, 115.60, -31.45, 116.10),
        box("metro-adelaide", "Adelaide", PackKind.METRO, -35.20, 138.40, -34.60, 138.80),
        box("metro-canberra", "Canberra", PackKind.METRO, -35.50, 148.95, -35.15, 149.30),
        box("metro-hobart", "Hobart", PackKind.METRO, -43.05, 147.10, -42.70, 147.55),
        box("metro-darwin", "Darwin", PackKind.METRO, -12.65, 130.75, -12.30, 131.10),
    )

    /** Highway centre lines through the main towns, buffered by [PackArea.CORRIDOR_BUFFER_KM] each side. */
    val corridors = listOf(
        road("road-hume", "Hume Highway (Sydney to Melbourne)",
            -33.87, 151.21, -34.75, 149.72, -34.84, 148.91, -35.07, 148.11, -36.08, 146.92, -36.36, 146.31, -37.03, 145.13, -37.81, 144.96),
        road("road-pacific", "Pacific Highway (Sydney to Brisbane)",
            -33.87, 151.21, -32.93, 151.78, -31.43, 152.91, -30.30, 153.11, -28.87, 153.56, -28.00, 153.43, -27.47, 153.03),
        road("road-bruce", "Bruce Highway (Brisbane to Cairns)",
            -27.47, 153.03, -26.19, 152.67, -24.87, 152.35, -23.38, 150.51, -21.14, 149.19, -19.26, 146.82, -16.92, 145.77),
        road("road-stuart", "Stuart Highway (Port Augusta to Darwin)",
            -32.49, 137.77, -29.01, 134.75, -23.70, 133.88, -19.65, 134.19, -14.47, 132.26, -12.46, 130.84),
        road("road-princes", "Princes Highway (Sydney to Melbourne, coast)",
            -33.87, 151.21, -34.43, 150.89, -34.88, 150.60, -35.71, 150.18, -36.67, 149.84, -37.70, 148.46, -38.11, 147.07, -38.20, 146.54, -37.81, 144.96),
    )

    val all: List<PackDef> = states + metros + corridors

    fun byId(id: String): PackDef? = all.firstOrNull { it.id == id }
}
