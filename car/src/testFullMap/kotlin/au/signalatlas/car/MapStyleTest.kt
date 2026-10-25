package au.signalatlas.car

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class MapStyleTest {
    @Test fun coverageAddsSourceAndTopLayer() {
        val out = Json.parseToJsonElement(MapStyle.withCoverage(MapStyle.blank(true), "http://t/{z}/{x}/{y}.png")).jsonObject
        assertThat(out.getValue("sources").jsonObject.keys).containsExactly("coverage")
        val layers = out.getValue("layers").jsonArray.map { it.jsonObject }
        assertThat(layers.map { it.getValue("id").jsonPrimitive.content }).containsExactly("bg", "coverage-relief").inOrder()
        assertThat(layers.last().getValue("type").jsonPrimitive.content).isEqualTo("color-relief")
    }

    @Test fun blankStyleIsValidJsonForBothThemes() {
        for (dark in listOf(true, false)) assertThat(Json.parseToJsonElement(MapStyle.blank(dark)).jsonObject["version"]).isNotNull()
    }
}
