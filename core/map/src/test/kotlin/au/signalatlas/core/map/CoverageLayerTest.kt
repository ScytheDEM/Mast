package au.signalatlas.core.map

import au.signalatlas.core.coverage.CoverageSelection.View
import au.signalatlas.core.model.GeoBounds
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class CoverageLayerTest {
    private fun stops(view: View, opacity: Float = 0.45f): List<Pair<Double, List<Double>>> {
        val e = Json.parseToJsonElement(CoverageLayer.colorExpression(view, opacity).toString()).jsonArray
        assertThat(e[0].jsonPrimitive.content).isEqualTo("interpolate")
        assertThat(e[1].jsonArray.map { it.toString() }).containsExactly("\"linear\"")
        assertThat(e[2].jsonArray.map { it.jsonPrimitive.content }).containsExactly("elevation")
        return e.drop(3).chunked(2).map { (input, out) ->
            val rgba = out.jsonArray
            assertThat(rgba[0].jsonPrimitive.content).isEqualTo("rgba")
            input.jsonPrimitive.content.toDouble() to rgba.drop(1).map { it.jsonPrimitive.content.toDouble() }
        }
    }

    @Test
    fun rsrpStopsUseLegendColoursAtAlpha045() {
        val s = stops(View.RSRP)
        // Weak, Fair, Good, Excellent at the lower bound of each band.
        assertThat(s.filter { it.second[3] > 0 }).containsExactly(
            -118.0 to listOf(244.0, 162.0, 97.0, 0.45), // #F4A261
            -110.0 to listOf(233.0, 196.0, 106.0, 0.45), // #E9C46A
            -100.0 to listOf(42.0, 157.0, 143.0, 0.45), // #2A9D8F
            -85.0 to listOf(11.0, 95.0, 165.0, 0.45), // #0B5FA5
        ).inOrder()
    }

    @Test
    fun rsrpIsTransparentBelowMinus118AndForNoCoverage() {
        val s = stops(View.RSRP)
        assertThat(s.first().first).isEqualTo(-200.0)
        assertThat(s.filter { it.first < -118.0 }.map { it.second[3] }).containsExactly(0.0, 0.0)
        assertThat(s.map { it.first }).isInStrictOrder()
    }

    @Test
    fun probabilityStopsUseLegendColours() {
        val s = stops(View.PROBABILITY)
        assertThat(s.map { it.first }).containsExactly(0.0, 50.0, 90.0, 100.0).inOrder()
        assertThat(s[0].second[3]).isEqualTo(0.0)
        assertThat(s.drop(1).map { it.second }).containsExactly(
            listOf(244.0, 162.0, 97.0, 0.45),
            listOf(42.0, 157.0, 143.0, 0.45),
            listOf(11.0, 95.0, 165.0, 0.45),
        ).inOrder()
    }

    @Test
    fun opacityIsTheAlphaOfTheColouredStops() {
        assertThat(stops(View.RSRP, 0.8f).filter { it.second[3] > 0 }.map { it.second[3] }).containsExactly(0.8, 0.8, 0.8, 0.8)
    }

    @Test
    fun sourceJsonIsATerrariumRasterDem() {
        val url = "https://coverage.signalatlas.invalid/v1/x/rsrp/s/e/{z}/{x}/{y}.png"
        val o = Json.parseToJsonElement(CoverageLayer.sourceJson(url)).jsonObject
        assertThat(o.getValue("type")).isEqualTo(JsonPrimitive("raster-dem"))
        assertThat(o.getValue("tiles")).isEqualTo(JsonArray(listOf(JsonPrimitive(url))))
        assertThat(o.getValue("encoding")).isEqualTo(JsonPrimitive("terrarium"))
        assertThat(o.getValue("tileSize").jsonPrimitive.int).isEqualTo(256)
        assertThat(o.getValue("minzoom").jsonPrimitive.int).isEqualTo(8)
        assertThat(o.getValue("maxzoom").jsonPrimitive.int).isEqualTo(16)
    }

    // A small viewport inside Sydney tile (z11, 1884, 1228).
    private val sydney = GeoBounds(-33.87, 151.20, -33.86, 151.21)

    @Test
    fun visibleTilesCoverTheRequestedZoomAndAllAncestors() {
        val tiles = CoverageLayer.visibleTiles(sydney, 10.3)
        // MapLibre asks for floor(zoom) + 1 = 11 at this camera; round(10.3) = 10 is allowed too.
        assertThat(tiles.filter { it.first == 11 }).containsExactly(Triple(11, 1884, 1228))
        assertThat(tiles).containsAtLeast(Triple(10, 942, 614), Triple(9, 471, 307), Triple(8, 235, 153))
        assertThat(tiles.map { it.first }.min()).isEqualTo(8)
    }

    @Test
    fun visibleTilesAreClampedToTheServedZooms() {
        assertThat(CoverageLayer.visibleTiles(sydney, 3.0)).isEmpty()
        assertThat(CoverageLayer.visibleTiles(sydney, 7.4)).isEmpty()
        assertThat(CoverageLayer.visibleTiles(sydney, 7.6).map { it.first }.toSet()).containsExactly(8)
        assertThat(CoverageLayer.visibleTiles(sydney, 19.0).map { it.first }.max()).isEqualTo(16)
        assertThat(CoverageLayer.visibleTiles(sydney, Double.NaN)).isEmpty()
    }

    @Test
    fun visibleTilesSpanTheViewport() {
        val wide = GeoBounds(-34.0, 151.0, -33.7, 151.4)
        val z11 = CoverageLayer.visibleTiles(wide, 10.5).filter { it.first == 11 }
        assertThat(z11.map { it.second }.toSet().size).isGreaterThan(2)
        assertThat(z11.map { it.third }.toSet().size).isGreaterThan(2)
        assertThat(z11.size).isEqualTo(z11.map { it.second }.toSet().size * z11.map { it.third }.toSet().size)
    }
}
