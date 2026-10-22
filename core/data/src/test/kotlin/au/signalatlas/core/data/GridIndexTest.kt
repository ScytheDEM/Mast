package au.signalatlas.core.data

import au.signalatlas.core.model.GeoBounds
import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

class GridIndexTest {
    private fun points(n: Int, seed: Int): Pair<DoubleArray, DoubleArray> {
        val rnd = Random(seed)
        // Australia's bounding box.
        return DoubleArray(n) { -44.0 + rnd.nextDouble() * 34.0 } to DoubleArray(n) { 113.0 + rnd.nextDouble() * 41.0 }
    }

    @Test fun queryMatchesBruteForce() {
        val (lats, lons) = points(5_000, 1)
        val idx = GridIndex(lats, lons)
        val rnd = Random(2)
        val out = IntArray(lats.size)
        repeat(200) {
            val s = -45.0 + rnd.nextDouble() * 35
            val w = 112.0 + rnd.nextDouble() * 42
            val b = GeoBounds(s, w, s + rnd.nextDouble() * 5, w + rnd.nextDouble() * 8)
            val n = idx.query(b, out)
            val expected = lats.indices.filter { b.contains(lats[it], lons[it]) }
            assertThat(out.take(n).sorted()).isEqualTo(expected)
        }
    }

    @Test fun boundsEdgesAreInclusive() {
        val idx = GridIndex(doubleArrayOf(-35.0, -35.1, -34.9), doubleArrayOf(149.0, 149.1, 149.0))
        val out = IntArray(3)
        val n = idx.query(GeoBounds(-35.1, 149.0, -35.0, 149.1), out)
        assertThat(out.take(n).sorted()).containsExactly(0, 1)
    }

    @Test fun boxOutsideDataAndEmptyIndex() {
        val (lats, lons) = points(100, 3)
        val out = IntArray(100)
        assertThat(GridIndex(lats, lons).query(GeoBounds(10.0, 0.0, 20.0, 10.0), out)).isEqualTo(0)
        assertThat(GridIndex(DoubleArray(0), DoubleArray(0)).query(GeoBounds(-90.0, -180.0, 90.0, 180.0), IntArray(0))).isEqualTo(0)
    }

    @Test fun nearestMatchesBruteForce() {
        val (lats, lons) = points(3_000, 4)
        val idx = GridIndex(lats, lons)
        val rnd = Random(5)
        repeat(100) {
            // Includes query points outside the data extent.
            val lat = -48.0 + rnd.nextDouble() * 42
            val lon = 110.0 + rnd.nextDouble() * 48
            val accept: (Int) -> Boolean = { it % 3 != 0 }
            val got = idx.nearest(lat, lon, 25, accept)
            val expected = lats.indices.filter(accept)
                .sortedBy { haversineM(lat, lon, lats[it], lons[it]) }.take(25)
            assertThat(got.map { haversineM(lat, lon, lats[it], lons[it]) })
                .isEqualTo(expected.map { haversineM(lat, lon, lats[it], lons[it]) })
        }
    }

    @Test fun nearestReturnsFewerWhenNotEnoughAccepted() {
        val (lats, lons) = points(50, 6)
        val got = GridIndex(lats, lons).nearest(-30.0, 135.0, 100) { it < 7 }
        assertThat(got.sorted()).isEqualTo((0 until 7).toList())
    }

    @Test fun speed() {
        val (lats, lons) = points(27_000, 7)
        val idx = GridIndex(lats, lons)
        val rnd = Random(8)
        val out = IntArray(lats.size)
        fun viewport(): GeoBounds {
            val w = 0.05 + rnd.nextDouble() * 1.95
            val s = -44.0 + rnd.nextDouble() * 34
            val west = 113.0 + rnd.nextDouble() * 41
            return GeoBounds(s, west, s + w, west + w)
        }
        repeat(200) { idx.query(viewport(), out) }
        val views = List(1_000) { viewport() }
        var total = 0L
        val t0 = System.nanoTime()
        for (v in views) total += idx.query(v, out)
        val avgMs = (System.nanoTime() - t0) / 1e6 / views.size
        assertThat(total).isGreaterThan(0L)
        assertThat(avgMs).isLessThan(1.0)
    }
}
