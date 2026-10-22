package au.signalatlas.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NetworkMappingTest {
    @Test fun mapsAllKnownLicensees() {
        val expected = mapOf(
            "TELSTRA LIMITED" to Network.TELSTRA,
            "Telstra 3G Spectrum Holdings Pty Ltd" to Network.TELSTRA,
            "Optus Mobile Pty Limited" to Network.OPTUS,
            "Vodafone Hutchison Australia Pty Limited" to Network.TPG,
            "Vodafone Australia Pty Limited" to Network.TPG,
            "TPG INTERNET PTY LTD" to Network.TPG,
            "MOBILE JV PTY LIMITED" to Network.OPTUS_TPG_JV,
            "NBN CO LIMITED" to Network.NBN,
        )
        for ((name, network) in expected) assertThat(NetworkMapping.fromLicensee(name)).isEqualTo(network)
    }

    @Test fun trimsAndIgnoresCase() {
        assertThat(NetworkMapping.fromLicensee(" telstra limited ")).isEqualTo(Network.TELSTRA)
    }

    @Test fun unknownIsNull() {
        assertThat(NetworkMapping.fromLicensee("Optus Networks Pty Limited")).isNull()
    }
}
