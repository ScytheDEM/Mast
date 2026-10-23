package au.signalatlas.core.radio

import au.signalatlas.core.model.Network
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SimMappingTest {
    @Test fun `Telstra codes`() {
        for (c in listOf("50501", "50511", "50571", "50572")) {
            assertThat(SimMapping.networkFor(c)).isEqualTo(Network.TELSTRA)
        }
    }

    @Test fun `Optus codes`() {
        for (c in listOf("50502", "50590")) assertThat(SimMapping.networkFor(c)).isEqualTo(Network.OPTUS)
    }

    @Test fun `TPG codes`() {
        for (c in listOf("50503", "50506", "50512")) assertThat(SimMapping.networkFor(c)).isEqualTo(Network.TPG)
    }

    @Test fun `separators are tolerated`() {
        assertThat(SimMapping.networkFor("505-01")).isEqualTo(Network.TELSTRA)
        assertThat(SimMapping.networkFor("505 02")).isEqualTo(Network.OPTUS)
    }

    @Test fun `unknown null and malformed codes map to null`() {
        assertThat(SimMapping.networkFor("50599")).isNull()
        assertThat(SimMapping.networkFor("23410")).isNull() // UK roaming SIM
        assertThat(SimMapping.networkFor("")).isNull()
        assertThat(SimMapping.networkFor(null)).isNull()
        assertThat(SimMapping.networkFor("5050")).isNull()
    }
}
