package au.signalatlas.core.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StyleAssetsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val variants = listOf(false, true)

    private fun style(dark: Boolean, key: String?): JsonObject =
        Json.parseToJsonElement(StyleLoader.load(context, dark, key)).jsonObject

    @Test
    fun bothStylesParseAndPointAtTheBuildForTheKey() {
        for (dark in variants) {
            val s = style(dark, "20261022.pmtiles")
            val sources = s.getValue("sources").jsonObject
            val vector = sources.values.map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.content == "vector" }
            assertThat(vector).hasSize(1)
            assertThat(vector.single().getValue("url").jsonPrimitive.content)
                .isEqualTo("pmtiles://https://build.protomaps.com/20261022.pmtiles")
            assertThat(s.getValue("layers").jsonArray.size).isGreaterThan(10)
        }
    }

    @Test
    fun withoutAKeyOnlyTheBackgroundRemains() {
        for (dark in variants) {
            val s = style(dark, null)
            val sources = s.getValue("sources").jsonObject.values.map { it.jsonObject }
            assertThat(sources.none { it["type"]?.jsonPrimitive?.content == "vector" }).isTrue()
            val layers = s.getValue("layers").jsonArray.map { it.jsonObject }
            assertThat(layers).isNotEmpty()
            assertThat(layers.map { it.getValue("type").jsonPrimitive.content }.toSet()).containsExactly("background")
        }
    }

    @Test
    fun withoutAKeyTheBackgroundTakesTheWaterColour() {
        assertThat(backgroundColour(style(dark = false, key = null))).isEqualTo("#B9D8F2")
        assertThat(backgroundColour(style(dark = true, key = null))).isEqualTo("#20354A")
        assertThat(backgroundColour(style(dark = false, key = "20261022.pmtiles"))).isEqualTo("#F6F5F2")
        assertThat(backgroundColour(style(dark = true, key = "20261022.pmtiles"))).isEqualTo("#1C1D1F")
    }

    @Test
    fun noRemoteUrlOtherThanThePmtilesSource() {
        for (dark in variants) {
            val urls = strings(style(dark, "20261022.pmtiles")).filter { "://" in it }
            assertThat(urls.filter { it.startsWith("http") }).isEmpty()
            assertThat(urls.filterNot { it.startsWith("asset://") || it.startsWith("pmtiles://https://build.protomaps.com/") })
                .isEmpty()
            assertThat(style(dark, "20261022.pmtiles")).doesNotContainKey("sprite")
        }
    }

    @Test
    fun everyTextFontHasItsGlyphsBundled() {
        for (dark in variants) {
            val s = style(dark, "20261022.pmtiles")
            assertThat(s.getValue("glyphs").jsonPrimitive.content).isEqualTo("asset://glyphs/{fontstack}/{range}.pbf")
            val fonts = s.getValue("layers").jsonArray
                .mapNotNull { it.jsonObject["layout"]?.jsonObject?.get("text-font") }
                .flatMap { fontNames(it) }
                .toSet()
            assertThat(fonts).containsExactly("Noto Sans Regular", "Noto Sans Medium")
            for (font in fonts) {
                // MapLibre percent-encodes the fontstack and its asset loader decodes it again,
                // so the folder name is the font name, spaces included.
                for (range in listOf("0-255", "256-511", "8192-8447")) {
                    val bytes = context.assets.open("glyphs/$font/$range.pbf").use { it.readBytes() }
                    assertThat(bytes.size).isGreaterThan(1000)
                }
            }
        }
        assertThat(context.assets.open("glyphs/OFL.txt").bufferedReader().use { it.readText() })
            .contains("SIL Open Font License")
    }

    private fun backgroundColour(s: JsonObject): String =
        s.getValue("layers").jsonArray.map { it.jsonObject }
            .first { it.getValue("type").jsonPrimitive.content == "background" }
            .getValue("paint").jsonObject.getValue("background-color").jsonPrimitive.content

    /** The styles use plain font arrays (no expressions), so every font is checkable. */
    private fun fontNames(e: JsonElement): List<String> = e.jsonArray.map { it.jsonPrimitive.content }

    private fun strings(e: JsonElement): List<String> = when (e) {
        is JsonObject -> e.values.flatMap { strings(it) }
        is JsonArray -> e.flatMap { strings(it) }
        is JsonPrimitive -> if (e.isString) listOf(e.content) else emptyList()
        JsonNull -> emptyList()
    }
}
