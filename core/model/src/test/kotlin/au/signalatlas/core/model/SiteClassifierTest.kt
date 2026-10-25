package au.signalatlas.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SiteClassifierTest {
    private fun kind(name: String) = SiteClassifier.classify(name)

    @Test fun tunnelNames() {
        assertThat(kind("Optus IBC Harbour Tunnel North")).isEqualTo(SiteKind.DAS_TUNNEL)
        assertThat(kind("M5 TUNL portal")).isEqualTo(SiteKind.DAS_TUNNEL)
        assertThat(kind("Tunnelling Rd")).isEqualTo(SiteKind.MACRO) // word boundary
    }

    @Test fun buildingKeywords() {
        for (n in listOf(
            "Optus IBC 695 Collins St", "In-Building Level 3", "Westfield Parramatta", "Suncorp Stadium",
            "Central Railway Station", "Shopping Center Rooftop", "Distributed Antenna System", "Wynyard Car Park",
        )) assertThat(kind(n)).isEqualTo(SiteKind.DAS_BUILDING)
    }

    @Test fun macroLookalikesStayMacro() {
        for (n in listOf(
            "Optus Lattice Tower 21 Tambo Spur Rd", "NBN Co 50m tower 39 Sydney Rd", "Station Pier", "Repeater Station Road",
            "Optus Tower 4.4 km WSW of Cooma", "Pump Station Rd", "Mount Hill Rd",
            // Rooftop macros and rural stations, formerly false positives.
            "Dubbo Hospital", "Penrith Plaza", "Tower 2, 100 Barangaroo", "Level 12 Office", "Mount Isa Station",
            "University of Newcastle Rooftop",
            // Outdoor small cells.
            "Light Pole SC38 The Domain St", "Small Cell 12 Smith St",
        )) assertThat(kind(n)).isEqualTo(SiteKind.MACRO)
    }
}
