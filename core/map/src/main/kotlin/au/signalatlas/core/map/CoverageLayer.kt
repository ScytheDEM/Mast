package au.signalatlas.core.map

import au.signalatlas.core.coverage.CoverageSelection
import au.signalatlas.core.model.GeoBounds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.rgba
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.layers.ColorReliefLayer
import org.maplibre.android.style.layers.PropertyFactory.colorReliefColor
import org.maplibre.android.style.sources.RasterDemSource
import org.maplibre.android.style.sources.TileSet
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * The coverage overlay: a `raster-dem` source of Terrarium tiles drawn by a GPU `color-relief` layer. Elevation is
 * the RSRP in dBm (−200 = no coverage) or, in the probability view, P×100, so the colour ramp (legend, rule 11)
 * lives entirely in the layer's `color-relief-color` and a colour change never touches the tiles.
 */
object CoverageLayer {
    const val SOURCE_ID = "coverage"
    const val LAYER_ID = "coverage-relief"
    const val MIN_ZOOM = 8
    const val MAX_ZOOM = 16
    const val DEFAULT_OPACITY = 0.45f

    private const val TILE_SIZE = 256

    private class Stop(val elevation: Double, val r: Int, val g: Int, val b: Int, val alpha: Boolean = true)

    // Legend rule 11. RSRP: the lower bound of each band; below -118 dBm (and -200 = none) is transparent.
    private val RSRP_STOPS = listOf(
        Stop(-200.0, 0, 0, 0, alpha = false),
        Stop(-119.0, 244, 162, 97, alpha = false),
        Stop(-118.0, 244, 162, 97), // Weak     #F4A261
        Stop(-110.0, 233, 196, 106), // Fair     #E9C46A
        Stop(-100.0, 42, 157, 143), // Good     #2A9D8F
        Stop(-85.0, 11, 95, 165), // Excellent #0B5FA5
    )
    private val PROBABILITY_STOPS = listOf(
        Stop(0.0, 244, 162, 97, alpha = false),
        Stop(50.0, 244, 162, 97), // #F4A261
        Stop(90.0, 42, 157, 143), // #2A9D8F
        Stop(100.0, 11, 95, 165), // #0B5FA5
    )

    /** `color-relief-color`: a linear interpolation over `["elevation"]` through the legend stops at [opacity] alpha. */
    fun colorExpression(view: CoverageSelection.View, opacity: Float = DEFAULT_OPACITY): Expression {
        val stops = when (view) {
            CoverageSelection.View.RSRP -> RSRP_STOPS
            CoverageSelection.View.PROBABILITY -> PROBABILITY_STOPS
        }
        return interpolate(
            linear(), Expression("elevation"),
            *stops.map { stop(it.elevation, rgba(it.r, it.g, it.b, if (it.alpha) opacity else 0f)) }.toTypedArray(),
        )
    }

    /** The source as it appears in style JSON. */
    fun sourceJson(urlTemplate: String): String = JsonObject(
        mapOf(
            "type" to JsonPrimitive("raster-dem"),
            "tiles" to JsonArray(listOf(JsonPrimitive(urlTemplate))),
            "tileSize" to JsonPrimitive(TILE_SIZE),
            "encoding" to JsonPrimitive("terrarium"),
            "minzoom" to JsonPrimitive(MIN_ZOOM),
            "maxzoom" to JsonPrimitive(MAX_ZOOM),
        ),
    ).toString()

    /** [sourceJson] as the API object (the Android API has no JSON entry point for a single source). */
    internal fun source(urlTemplate: String): RasterDemSource {
        val tiles = TileSet("2.1.0", urlTemplate).apply {
            encoding = "terrarium"
            minZoom = MIN_ZOOM.toFloat()
            maxZoom = MAX_ZOOM.toFloat()
        }
        return RasterDemSource(SOURCE_ID, tiles, TILE_SIZE)
    }

    /**
     * Adds (replacing any earlier) the source and layer, below the first label layer so roads and water show
     * underneath and place names on top; with no basemap it goes below [fallbackBelow] (the first tower layer), or on top if that is not there yet.
     */
    fun install(style: Style, urlTemplate: String, view: CoverageSelection.View, opacity: Float, fallbackBelow: String) {
        remove(style)
        style.addSource(source(urlTemplate))
        val layer = ColorReliefLayer(LAYER_ID, SOURCE_ID).withProperties(colorReliefColor(colorExpression(view, opacity)))
        layer.minZoom = MIN_ZOOM.toFloat()
        val below = LABEL_ANCHORS.firstOrNull { style.getLayer(it) != null } ?: fallbackBelow
        if (style.getLayer(below) != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
    }

    fun remove(style: Style) {
        style.removeLayer(LAYER_ID)
        style.removeSource(SOURCE_ID)
    }

    /** Colour changes only: no source reload. */
    fun restyle(style: Style, view: CoverageSelection.View, opacity: Float) {
        style.getLayerAs<ColorReliefLayer>(LAYER_ID)?.setProperties(colorReliefColor(colorExpression(view, opacity)))
    }

    private val LABEL_ANCHORS = listOf("water_label", "roads_labels_minor", "places_subplace")

    /**
     * Tiles the service must be allowed to render for [bounds] at camera zoom [cameraZoom]. Measured on MapLibre
     * Android 13.7.1 (256 px tiles): it requests z = floor(zoom) + 1 (zoom 10.3 asks for z11) plus a z8 ancestor
     * tile, so the set is the visible tiles at both clamp(round(zoom)) and clamp(floor(zoom) + 1), each with all its
     * ancestors down to z8 (this covers the contract's z-1 and z-2 parents). Extra permitted tiles cost nothing; only
     * requested ones are rendered.
     */
    fun visibleTiles(bounds: GeoBounds, cameraZoom: Double): Set<Triple<Int, Int, Int>> {
        // The layer is hidden below z8 (minzoom), so nothing is requested there.
        if (cameraZoom.isNaN() || cameraZoom < MIN_ZOOM - 0.5) return emptySet()
        val out = HashSet<Triple<Int, Int, Int>>()
        val zooms = setOf(
            cameraZoom.roundToInt().coerceIn(MIN_ZOOM, MAX_ZOOM),
            (floor(cameraZoom).toInt() + 1).coerceIn(MIN_ZOOM, MAX_ZOOM),
        )
        for (z in zooms) {
            val n = 1 shl z
            val x0 = tileX(bounds.west, n)
            val x1 = tileX(bounds.east, n)
            val y0 = tileY(bounds.north, n)
            val y1 = tileY(bounds.south, n)
            for (dz in 0..(z - MIN_ZOOM)) {
                for (x in (x0 shr dz)..(x1 shr dz)) for (y in (y0 shr dz)..(y1 shr dz)) out += Triple(z - dz, x, y)
            }
        }
        return out
    }

    private fun tileX(lon: Double, n: Int): Int = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)

    private fun tileY(lat: Double, n: Int): Int {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val y = (1.0 - asinh(tan(Math.toRadians(clamped))) / PI) / 2.0 * n
        return floor(y).toInt().coerceIn(0, n - 1)
    }
}
