package au.signalatlas.engine

import android.util.Base64
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Assert.assertThrows
import org.junit.Test

/** M4 through JNI: point prediction, calibration terms and residual rasters against the Python reference vectors. */
class CalibrationEngineTest {
    private fun terms(v: TileVector): (Int) -> SectorTerms? {
        val c = v.carriers
        fun col(k: String, i: Int) = c.getJSONArray(k).optDouble(i, Double.NaN).toFloat()
        return { row -> SectorTerms(col("calOffsetDb", row), col("calSlope", row), col("calDmedKm", row), col("calSigmaScale", row)) }
    }

    @Test fun pointPredictionMatchesTheReference() {
        val v = TileVectors.load("point_canberra.json")
        val engine = CoverageEngine()
        val prepared = engine.prepare(v.batch, null, false, calibration = terms(v))
        val points = v.json.getJSONArray("points")
        assertThat(points.length()).isEqualTo(8)
        var worst = 0.0
        for (p in 0 until points.length()) {
            val pt = points.getJSONObject(p)
            val got = engine.predictPoint(
                prepared, pt.getDouble("lat"), pt.getDouble("lon"), AreaClass.valueOf(pt.getString("area")), pt.getBoolean("indoor"),
            )
            val want = pt.getJSONArray("rsrp")
            assertThat(got.size).isEqualTo(prepared.size)
            for (k in got.indices) worst = maxOf(worst, abs(got[k] - want.getDouble(prepared.batchIndex(k))))
        }
        assertThat(worst).isLessThan(2e-3)
        assertThrows(IllegalArgumentException::class.java) { engine.predictPoint(prepared, Double.NaN, 149.0, AreaClass.RURAL, false) }
    }

    @Test fun calibratedTileWithResidualRastersMatchesTheReference() {
        val v = TileVectors.load("tile_canberra_z13_calibrated.json")
        val net = v.carriers.getJSONArray("network")
        // The rasters select carriers by network, so the batch carries the vector's network column. That also moves
        // the frequency keys (interference classes) away from the reference's single-network ones, so SINR is not
        // compared here (the host suite compares it with the vector's keys).
        val batch = v.batch.let { b ->
            au.signalatlas.core.data.CarrierBatch(
                b.size, b.siteLat, b.siteLon, b.heightM, b.azimuthDeg, b.tiltDeg, b.eirpDbm, b.freqMhz, b.bandwidthHz, b.tech,
                ByteArray(b.size) { net.getInt(it).toByte() }, b.hBeamwidthDeg, b.frontToBackDb, b.gainDbi, b.sectorId, b.sddId,
                b.bandIdx, b.bandLabels,
            )
        }
        val residuals = v.json.getJSONArray("residuals").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                ResidualRaster(it.getInt("mask"), Base64.decode(it.getString("codes"), Base64.DEFAULT))
            }
        }
        val engine = CoverageEngine()
        val prepared = engine.prepare(batch, null, false, calibration = terms(v))
        val tile = engine.render(prepared, v.z, v.x, v.y, v.area, v.thr, CancelToken(), residuals)!!
        TileVectors.assertCodes("rsrp", tile.rsrp, v.codes("rsrpCodes"))
        TileVectors.assertCodes("prob", tile.prob, v.codes("probCodes"))
        val refBest = v.codes("best")
        val apart = refBest.indices.count { tile.bestCarrier(it) != refBest[it] && refBest[it] != RenderedTile.NO_BEST }
        assertThat(apart.toDouble()).isAtMost(0.001 * refBest.size)
        assertThrows(IllegalArgumentException::class.java) {
            engine.render(prepared, v.z, v.x, v.y, v.area, v.thr, CancelToken(), List(33) { residuals[0] })
        }
    }
}
