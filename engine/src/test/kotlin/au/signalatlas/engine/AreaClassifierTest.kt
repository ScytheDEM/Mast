package au.signalatlas.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AreaClassifierTest {
    @Test fun thresholds() {
        assertThat(AreaClassifier.classify(0)).isEqualTo(AreaClass.RURAL)
        assertThat(AreaClassifier.classify(2)).isEqualTo(AreaClass.RURAL)
        assertThat(AreaClassifier.classify(3)).isEqualTo(AreaClass.SUBURBAN)
        assertThat(AreaClassifier.classify(11)).isEqualTo(AreaClass.SUBURBAN)
        assertThat(AreaClassifier.classify(12)).isEqualTo(AreaClass.URBAN)
        assertThat(AreaClassifier.classify(39)).isEqualTo(AreaClass.URBAN)
        assertThat(AreaClassifier.classify(40)).isEqualTo(AreaClass.DENSE_URBAN)
        assertThat(AreaClassifier.classify(500)).isEqualTo(AreaClass.DENSE_URBAN)
    }

    @Test fun perSiteCountsAroundEachSiteOncePerPosition() {
        val n = 4
        val lat = doubleArrayOf(-33.87, -33.87, -35.0, Double.NaN)
        val lon = doubleArrayOf(151.21, 151.21, 149.0, 149.0)
        val b = au.signalatlas.core.data.CarrierBatch(
            n, lat, lon, FloatArray(n) { 30f }, FloatArray(n), FloatArray(n), FloatArray(n) { 60f }, FloatArray(n) { 700f },
            IntArray(n) { 10_000_000 }, ByteArray(n), ByteArray(n), FloatArray(n) { 65f }, FloatArray(n) { 25f },
            FloatArray(n) { 15f }, IntArray(n) { it }, LongArray(n) { it.toLong() }, ByteArray(n), listOf("B28"),
        )
        val asked = ArrayList<Triple<Double, Double, Double>>()
        val areas = AreaClassifier.perSite(b) { la, lo, r -> asked += Triple(la, lo, r); if (la < -34.0) 2 else 45 }
        assertThat(areas.toList()).containsExactly(AreaClass.DENSE_URBAN, AreaClass.DENSE_URBAN, AreaClass.RURAL, null).inOrder()
        assertThat(asked).containsExactly(Triple(-33.87, 151.21, 3000.0), Triple(-35.0, 149.0, 3000.0)).inOrder()
    }
}
