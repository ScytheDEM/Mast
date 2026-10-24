package au.signalatlas.core.coverage

import au.signalatlas.core.model.LatLon
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PredictAlongTest {
    @get:Rule val tmp = TemporaryFolder()
    private var rig: Rig? = null
    private fun rig() = Rig(tmp.newFolder()).also { rig = it }

    @After fun tearDown() { rig?.close() }

    private val a = LatLon(-33.87, 151.21)
    private val b = LatLon(-33.87, 151.30)

    @Test fun bestServerRsrpAndProbabilityFollowTheThreshold() {
        val r = rig()
        // Row 0 (Telstra NR n78) is the strong one at -100; row 1 (Optus LTE) is weaker.
        r.renderer.predict = { _, row, _, _ -> if (row == 0) -100f else -120f }
        val p = runBlocking { r.service.predictAlong(listOf(a), r.selection) }.single()
        assertThat(p.rsrpDbm).isEqualTo(-100f)
        assertThat(p.bestNetwork).isEqualTo(Network.TELSTRA)
        assertThat(p.bestTech).isEqualTo(Tech.NR)
        assertThat(p.sigmaDb).isEqualTo(8f)
        // z = (-118 + 100) / 8 = -2.25 -> P = 0.988
        assertThat(p.probability).isWithin(0.002f).of(0.988f)
        val strict = runBlocking { r.service.predictAlong(listOf(a), r.selection, thresholdDbm = -90f) }.single()
        assertThat(strict.probability).isWithin(0.02f).of(0.106f)
    }

    @Test fun probabilityIsAHalfAtTheThresholdAndWeakBeyondIt() {
        val r = rig()
        r.renderer.predict = { _, _, lat, _ -> if (lat > -33.88) -118f else -140f }
        val ps = runBlocking { r.service.predictAlong(listOf(a, LatLon(-33.95, 151.21)), r.selection) }
        assertThat(ps[0].probability).isWithin(0.001f).of(0.5f)
        assertThat(ps[1].probability).isLessThan(0.01f)
    }

    @Test fun noFiniteCarrierMeansNoCoverage() {
        val r = rig()
        r.renderer.predict = { _, _, _, _ -> Float.NaN }
        val p = runBlocking { r.service.predictAlong(listOf(a), r.selection) }.single()
        assertThat(p).isEqualTo(PointPrediction.NONE)
        assertThat(p.rsrpDbm).isNull()
        assertThat(p.probability).isEqualTo(0f)
    }

    @Test fun noTowerDataOrCoverageGivesNone() {
        val r = rig()
        r.towers.stampValue = null
        assertThat(runBlocking { r.service.predictAlong(listOf(a, b), r.selection) }).containsExactly(PointPrediction.NONE, PointPrediction.NONE)
        assertThat(runBlocking { r.service.predictAlong(listOf(LatLon(89.0, 0.0)), r.selection) }).containsExactly(PointPrediction.NONE)
    }

    @Test fun nearbyPointsShareTheGridCacheAndNewData() {
        val r = rig()
        runBlocking { r.service.predictAlong(listOf(a), r.selection) }
        val first = r.renderer.predictCalls.get()
        runBlocking { r.service.predictAlong(listOf(LatLon(a.lat, a.lon + 0.00001)), r.selection) }
        assertThat(r.renderer.predictCalls.get()).isEqualTo(first) // same ~44 m cell
        r.towers.stampValue = "stamp2"
        runBlocking { r.service.predictAlong(listOf(a), r.selection) }
        assertThat(r.renderer.predictCalls.get()).isEqualTo(first + 1) // new tower data: recomputed
    }

    @Test fun regionsArePreparedOncePerTileNotPerCall() {
        val r = rig()
        val pts = (0 until 40).map { LatLon(-33.87, 151.21 + it * 0.0005) }
        val out = runBlocking { r.service.predictAlong(pts, r.selection) }
        assertThat(out).hasSize(40)
        assertThat(out.all { it.rsrpDbm == -100f }).isTrue()
        // One region (shared with the culled sub-batch prepare): the region plus one corridor sub-batch per tile.
        assertThat(r.renderer.prepares.get()).isAtMost(2 + 2)
    }
}
