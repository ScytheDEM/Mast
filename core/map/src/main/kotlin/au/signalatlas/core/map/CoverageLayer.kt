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
import org.maplibre.android.style.layers.PropertyFactory.colorReliefOpacity
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

    /** Layer opacity of a pending layer: near zero so it does not double the overlay, but above zero so its tiles load. */
    private const val PENDING_OPACITY = 0.01f

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

    /** The two alternating source/layer pairs (see [CoverageOverlay]): slot 0 is the original `coverage` / `coverage-relief`. */
    internal fun sourceId(slot: Int) = if (slot == 0) SOURCE_ID else "$SOURCE_ID-b"
    internal fun layerId(slot: Int) = if (slot == 0) LAYER_ID else "$LAYER_ID-b"

    /** [sourceJson] as the API object (the Android API has no JSON entry point for a single source). */
    internal fun source(urlTemplate: String, id: String = SOURCE_ID): RasterDemSource {
        val tiles = TileSet("2.1.0", urlTemplate).apply {
            encoding = "terrarium"
            minZoom = MIN_ZOOM.toFloat()
            maxZoom = MAX_ZOOM.toFloat()
        }
        return RasterDemSource(id, tiles, TILE_SIZE)
    }

    /** What a change of the wanted tile template means for the overlay's source. */
    enum class SourceOp { NONE, ADD, UPDATE, REMOVE }

    /**
     * The one decision behind the overlay: [installed] is the template the source has (or is being switched to), [wanted]
     * the one the map screen asks for. Add and remove happen only when coverage is toggled; every other template change
     * (a view switch, new data, a terrain epoch bump) is an [SourceOp.UPDATE], which [CoverageOverlay] applies without
     * the overlay ever disappearing.
     */
    fun planSource(installed: String?, wanted: String?): SourceOp = when {
        wanted == installed -> SourceOp.NONE
        wanted == null -> SourceOp.REMOVE
        installed == null -> SourceOp.ADD
        else -> SourceOp.UPDATE
    }

    private fun newLayer(slot: Int, view: CoverageSelection.View, opacity: Float): ColorReliefLayer =
        ColorReliefLayer(layerId(slot), sourceId(slot)).withProperties(colorReliefColor(colorExpression(view, opacity))).also {
            it.minZoom = MIN_ZOOM.toFloat()
        }

    private fun addLayer(style: Style, layer: ColorReliefLayer, belowLayer: String?, fallbackBelow: String) {
        val below = belowLayer ?: LABEL_ANCHORS.firstOrNull { style.getLayer(it) != null } ?: fallbackBelow
        if (style.getLayer(below) != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
    }

    /**
     * The overlay on one [Style]: a `raster-dem` source and `color-relief` layer. MapLibre Android 13.7.1 cannot change
     * a source's tiles after it is added (no `setTiles` / `setUrl` on raster sources) and removing the source blanks the
     * overlay until the new tiles are in, so a template change is a cross-over: the new source and layer are added
     * below the old ones, drawn at near-zero opacity so their tiles load, and [promote] (once the caller has seen them
     * rendered) shows them and drops the old pair in the same frame. Only a toggle adds or removes the overlay.
     *
     * Main thread only. A new [Style] (basemap or theme change) resets it.
     */
    class CoverageOverlay {
        private class Slot(val index: Int, val template: String)

        private var style: Style? = null
        private var active: Slot? = null
        private var pending: Slot? = null

        /** The source id waiting to be promoted, or null. */
        val pendingSourceId: String? get() = pending?.let { sourceId(it.index) }

        /**
         * Brings the style in line with [template] (null = no overlay). Returns the id of a source that was just added
         * pending, which the caller must wait for and then [promote]; null when nothing needs to wait.
         */
        fun sync(style: Style, template: String?, view: CoverageSelection.View, opacity: Float, fallbackBelow: String): String? {
            if (this.style !== style) {
                this.style = style
                active = null
                pending = null
            }
            val current = pending?.template ?: active?.template
            when (planSource(current, template)) {
                SourceOp.NONE -> Unit
                SourceOp.REMOVE -> {
                    removeAll(style)
                    active = null
                    pending = null
                }
                SourceOp.ADD -> {
                    val slot = Slot(0, template!!)
                    style.addSource(source(template, sourceId(slot.index)))
                    addLayer(style, newLayer(slot.index, view, opacity), null, fallbackBelow)
                    active = slot
                }
                SourceOp.UPDATE -> {
                    dropPending(style)
                    val old = checkNotNull(active) { "an update needs an installed overlay" }
                    val slot = Slot(1 - old.index, template!!)
                    style.addSource(source(template, sourceId(slot.index)))
                    val layer = newLayer(slot.index, view, opacity)
                    layer.setProperties(colorReliefOpacity(PENDING_OPACITY))
                    addLayer(style, layer, layerId(old.index), fallbackBelow)
                    pending = slot
                    return sourceId(slot.index)
                }
            }
            return null
        }

        /** Shows the pending pair and removes the old one. */
        fun promote(style: Style) {
            val next = pending ?: return
            if (this.style !== style) return
            style.getLayerAs<ColorReliefLayer>(layerId(next.index))?.setProperties(colorReliefOpacity(1f))
            active?.let { removePair(style, it.index) }
            active = next
            pending = null
        }

        /** Colour changes only: no source reload. */
        fun restyle(style: Style, view: CoverageSelection.View, opacity: Float) {
            for (slot in listOfNotNull(active, pending)) {
                style.getLayerAs<ColorReliefLayer>(layerId(slot.index))?.setProperties(colorReliefColor(colorExpression(view, opacity)))
            }
        }

        private fun dropPending(style: Style) {
            pending?.let { removePair(style, it.index) }
            pending = null
        }

        private fun removePair(style: Style, slot: Int) {
            style.removeLayer(layerId(slot))
            style.removeSource(sourceId(slot))
        }

        private fun removeAll(style: Style) {
            removePair(style, 0)
            removePair(style, 1)
        }
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
