package au.signalatlas.core.map

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ConnectedHighlightTest {
    private fun kinds(h: ConnectedHighlight) =
        Json.parseToJsonElement(h.toGeoJson()).jsonObject.getValue("features").jsonArray.map {
            it.jsonObject.getValue("properties").jsonObject.getValue("kind").jsonPrimitive.content
        }

    @Test fun servingNeighboursAndLineAreSeparateFeaturesInLonLatOrder() {
        val h = ConnectedHighlight(-35.3 to 149.1, -35.28 to 149.13, listOf(-35.31 to 149.2, -35.4 to 149.0))
        assertThat(kinds(h)).containsExactly("serving", "neighbour", "neighbour", "line").inOrder()
        val serving = Json.parseToJsonElement(h.toGeoJson()).jsonObject.getValue("features").jsonArray[0].jsonObject
            .getValue("geometry").jsonObject.getValue("coordinates").jsonArray
        assertThat(serving[0].jsonPrimitive.double).isEqualTo(149.1)
        assertThat(serving[1].jsonPrimitive.double).isEqualTo(-35.3)
    }

    @Test fun withoutALocationThereIsNoLine() {
        assertThat(kinds(ConnectedHighlight(-35.3 to 149.1, null))).containsExactly("serving")
    }
}
