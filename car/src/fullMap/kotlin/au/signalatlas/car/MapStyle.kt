package au.signalatlas.car

import au.signalatlas.core.map.CoverageLayer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Style JSON for the car's snapshot map. Pure. */
internal object MapStyle {
    /** A calm background when no basemap is available. */
    fun blank(dark: Boolean): String =
        """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"${if (dark) "#1B2733" else "#E4EEF2"}"}}]}"""

    // The legend's RSRP stops (lower bound, rgb): weak, fair, good, excellent. Below -118 dBm is transparent.
    private val STOPS = listOf(
        -119.0 to "244,162,97,0", -118.0 to "244,162,97,A", -110.0 to "233,196,106,A", -100.0 to "42,157,143,A", -85.0 to "11,95,165,A",
    )

    /** [style] with the coverage raster-dem source and a colour-relief layer on top of the basemap. */
    fun withCoverage(style: String, urlTemplate: String, opacity: Double = 0.5): String {
        val root = Json.parseToJsonElement(style).jsonObject
        val source = Json.parseToJsonElement(CoverageLayer.sourceJson(urlTemplate)).jsonObject
        val ramp = ArrayList<kotlinx.serialization.json.JsonElement>().apply {
            add(JsonPrimitive("interpolate")); add(JsonArray(listOf(JsonPrimitive("linear"))))
            add(JsonArray(listOf(JsonPrimitive("elevation"))))
            add(JsonPrimitive(-200.0)); add(JsonPrimitive("rgba(0,0,0,0)"))
            for ((elevation, rgba) in STOPS) {
                add(JsonPrimitive(elevation)); add(JsonPrimitive("rgba(" + rgba.replace("A", opacity.toString()) + ")"))
            }
        }
        val layer = buildJsonObject {
            put("id", CoverageLayer.LAYER_ID)
            put("type", "color-relief")
            put("source", CoverageLayer.SOURCE_ID)
            put("paint", buildJsonObject { put("color-relief-color", JsonArray(ramp)) })
        }
        val sources = JsonObject(root.getValue("sources").jsonObject + (CoverageLayer.SOURCE_ID to source))
        val layers = JsonArray(root.getValue("layers").jsonArray + layer)
        return JsonObject(root + mapOf("sources" to sources, "layers" to layers)).toString()
    }
}
