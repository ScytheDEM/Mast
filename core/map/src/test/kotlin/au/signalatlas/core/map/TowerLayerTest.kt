package au.signalatlas.core.map

import au.signalatlas.core.model.Network
import au.signalatlas.core.model.SiteSummary
import au.signalatlas.core.model.Tech
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class TowerLayerTest {
    private val sites = listOf(
        SiteSummary(10, -33.8688, 151.2093, "Sydney \"CBD\"", setOf(Network.TPG, Network.OPTUS), setOf(Tech.LTE, Tech.NR), setOf("B3", "n78")),
        SiteSummary(11, -37.8136, 144.9631, "Melbourne", setOf(Network.TELSTRA), setOf(Tech.LTE), setOf("B28")),
    )

    private fun features(s: List<SiteSummary>): List<JsonObject> {
        val fc = Json.parseToJsonElement(TowerLayer.featureCollection(s)).jsonObject
        assertThat(fc.getValue("type").jsonPrimitive.content).isEqualTo("FeatureCollection")
        return fc.getValue("features").jsonArray.map { it.jsonObject }
    }

    private val JsonObject.props get() = getValue("properties").jsonObject

    @Test
    fun twoSitesBecomeTwoPointsAtLonLat() {
        val f = features(sites)
        assertThat(f).hasSize(2)
        val g = f[0].getValue("geometry").jsonObject
        assertThat(f[0].getValue("type").jsonPrimitive.content).isEqualTo("Feature")
        assertThat(g.getValue("type").jsonPrimitive.content).isEqualTo("Point")
        assertThat(g.getValue("coordinates").jsonArray.map { it.jsonPrimitive.double }).containsExactly(151.2093, -33.8688).inOrder()
        assertThat(f[1].props.getValue("id").jsonPrimitive.int).isEqualTo(11)
    }

    @Test
    fun networkCountAndPrimaryNetworkInEnumOrder() {
        val p = features(sites)[0].props
        assertThat(p.getValue("id").jsonPrimitive.int).isEqualTo(10)
        assertThat(p.getValue("n").jsonPrimitive.int).isEqualTo(2)
        assertThat(p.getValue("net").jsonPrimitive.content).isEqualTo("OPTUS")
        assertThat(features(sites)[1].props.getValue("net").jsonPrimitive.content).isEqualTo("TELSTRA")
    }

    @Test
    fun nrIsTrueIffTechsContainNr() {
        val f = features(sites)
        assertThat(f[0].props.getValue("nr").jsonPrimitive.boolean).isTrue()
        assertThat(f[1].props.getValue("nr").jsonPrimitive.boolean).isFalse()
    }

    @Test
    fun dasPropertyCarriesTheSiteKind() {
        val f = features(sites + sites[1].copy(id = 12, kind = au.signalatlas.core.model.SiteKind.DAS_TUNNEL))
        assertThat(f[0].props.getValue("das").jsonPrimitive.int).isEqualTo(0)
        assertThat(f[2].props.getValue("das").jsonPrimitive.int).isEqualTo(2)
    }

    @Test
    fun emptyListIsAnEmptyCollection() {
        assertThat(features(emptyList())).isEmpty()
    }

    @Test
    fun networkColours() {
        assertThat(NetworkColors.argb(Network.TELSTRA)).isEqualTo(0xFF1E88E5.toInt())
        assertThat(NetworkColors.argb(Network.OPTUS)).isEqualTo(0xFF00A39A.toInt())
        assertThat(NetworkColors.argb(Network.TPG)).isEqualTo(0xFFE53935.toInt())
        assertThat(NetworkColors.argb(Network.OPTUS_TPG_JV)).isEqualTo(0xFF8E6CD8.toInt())
        assertThat(NetworkColors.argb(Network.NBN)).isEqualTo(0xFFFF8F00.toInt())
    }

    @Test
    fun selectedFilterMatchesOnlyTheSelectedId() {
        assertThat(TowerLayer.selectedFilter(42).toString()).isEqualTo("[\"==\", [\"get\", \"id\"], 42.0]")
        assertThat(TowerLayer.selectedFilter(null).toString()).isEqualTo("false")
    }
}
