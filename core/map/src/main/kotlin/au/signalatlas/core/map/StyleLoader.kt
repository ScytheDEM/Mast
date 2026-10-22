package au.signalatlas.core.map

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Loads a bundled clean style and points it at a Protomaps build. Call off the main thread. */
object StyleLoader {
    const val SOURCE_ID = "protomaps"
    private const val PLACEHOLDER = "{{PMTILES_URL}}"

    fun load(context: Context, dark: Boolean, basemapKey: String?): String {
        val asset = if (dark) "styles/clean_dark.json" else "styles/clean_light.json"
        val json = context.assets.open(asset).bufferedReader().use { it.readText() }
        return transform(json, basemapKey)
    }

    /**
     * With a key, fills in the `pmtiles://` URL. Without one (never fetched, offline) drops the
     * vector source and its layers, leaving the background painted in the style's water colour so
     * the tower dots still have a calm backdrop.
     */
    internal fun transform(styleJson: String, basemapKey: String?): String {
        if (basemapKey != null) return styleJson.replace(PLACEHOLDER, "pmtiles://https://build.protomaps.com/$basemapKey")
        val style = Json.parseToJsonElement(styleJson).jsonObject
        val layers = style.getValue("layers").jsonArray.map { it.jsonObject }
        val water = layers.firstOrNull { it["id"]?.jsonPrimitive?.content == "water" }
            ?.get("paint")?.jsonObject?.get("fill-color")
        val kept = layers
            .filter { it["source"]?.jsonPrimitive?.content != SOURCE_ID }
            .map { layer ->
                if (water == null || layer["type"]?.jsonPrimitive?.content != "background") layer
                else JsonObject(layer + ("paint" to JsonObject(mapOf("background-color" to water))))
            }
        val sources = style.getValue("sources").jsonObject - SOURCE_ID
        return JsonObject(style + mapOf("sources" to JsonObject(sources), "layers" to JsonArray(kept))).toString()
    }
}
