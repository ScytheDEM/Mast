package au.signalatlas.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GeoBoundsTest {
    private val b = GeoBounds(south = -34.0, west = 150.0, north = -33.0, east = 151.0)

    @Test fun insideAndEdgesInclusive() {
        assertThat(b.contains(-33.5, 150.5)).isTrue()
        assertThat(b.contains(-34.0, 150.0)).isTrue()
        assertThat(b.contains(-33.0, 151.0)).isTrue()
    }

    @Test fun outside() {
        assertThat(b.contains(-32.9, 150.5)).isFalse()
        assertThat(b.contains(-33.5, 151.1)).isFalse()
    }
}
