package au.signalatlas.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SiteClassifierTest {
    private fun kind(name: String, h: Double = 30.0, eirp: Double = 55.0, wide: Boolean = false) =
        SiteClassifier.classify(name, h, eirp, wide)

    @Test fun tunnelNames() {
        assertThat(kind("Optus IBC Harbour Tunnel North")).isEqualTo(SiteKind.DAS_TUNNEL)
        assertThat(kind("M5 TUNL portal")).isEqualTo(SiteKind.DAS_TUNNEL)
        assertThat(kind("Tunnelling Rd")).isEqualTo(SiteKind.MACRO) // word boundary
    }

    @Test fun buildingKeywords() {
        for (n in listOf(
            "Optus IBC 695 Collins St", "In-Building Level 3", "Westfield Parramatta", "Suncorp Stadium",
            "Central Railway Station", "Dubbo Hospital", "Penrith Plaza", "Tower 2, 100 Barangaroo", "Level 12 Office",
            "Shopping Center Rooftop", "Distributed Antenna System",
        )) assertThat(kind(n)).isEqualTo(SiteKind.DAS_BUILDING)
    }

    @Test fun macroLookalikesStayMacro() {
        for (n in listOf(
            "Optus Lattice Tower 21 Tambo Spur Rd", "NBN Co 50m tower 39 Sydney Rd", "Station Pier", "Repeater Station Road",
            "Optus Tower 4.4 km WSW of Cooma", "Pump Station Rd", "Mount Hill Rd",
        )) assertThat(kind(n)).isEqualTo(SiteKind.MACRO)
    }

    @Test fun geometryRule() {
        assertThat(kind("Anon site", h = 6.0, eirp = 40.0, wide = true)).isEqualTo(SiteKind.DAS_BUILDING)
        assertThat(kind("Anon site", h = 6.1, eirp = 40.0, wide = true)).isEqualTo(SiteKind.MACRO)
        assertThat(kind("Anon site", h = 6.0, eirp = 40.1, wide = true)).isEqualTo(SiteKind.MACRO)
        assertThat(kind("Anon site", h = 6.0, eirp = 40.0, wide = false)).isEqualTo(SiteKind.MACRO)
    }
}
