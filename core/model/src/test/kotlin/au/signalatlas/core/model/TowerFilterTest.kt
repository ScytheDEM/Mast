package au.signalatlas.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TowerFilterTest {
    private val site = SiteSummary(1, -33.0, 151.0, "S", setOf(Network.TELSTRA), setOf(Tech.NR), setOf("n78"))

    @Test fun defaultMatches() = assertThat(TowerFilter().matches(site)).isTrue()
    @Test fun networkMismatch() = assertThat(TowerFilter(networks = setOf(Network.OPTUS)).matches(site)).isFalse()
    @Test fun techMismatch() = assertThat(TowerFilter(techs = setOf(Tech.LTE)).matches(site)).isFalse()
    @Test fun bandMismatch() = assertThat(TowerFilter(bands = setOf("B28")).matches(site)).isFalse()
    @Test fun bandOverlap() = assertThat(TowerFilter(bands = setOf("n78", "B28")).matches(site)).isTrue()
}
