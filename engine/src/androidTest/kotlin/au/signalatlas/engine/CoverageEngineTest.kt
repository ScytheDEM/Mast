package au.signalatlas.engine

import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import au.signalatlas.core.data.CarrierBatch
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

/** Runs the real `libsaengine.so` through JNI. The vectors are the host GoogleTest vectors (Python reference). */
class CoverageEngineTest {
    private class Vector(val json: JSONObject, val batch: CarrierBatch) {
        val z = json.getInt("z")
        val x = json.getInt("x")
        val y = json.getInt("y")
        val area = AreaClass.valueOf(json.getString("area"))
        val indoor = json.getBoolean("indoor")
        val thr = json.getDouble("thrDbm").toFloat()
        val carriers: JSONObject = json.getJSONObject("carriers")
        fun codes(key: String): IntArray = json.getJSONArray(key).let { a -> IntArray(a.length()) { a.getInt(it) } }
    }

    private fun loadVector(name: String): Vector {
        val text = InstrumentationRegistry.getInstrumentation().context.assets.open(name).bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        val c = json.getJSONObject("carriers")
        val n = c.getInt("n")
        fun f(k: String) = c.getJSONArray(k).let { a -> FloatArray(n) { a.optDouble(it, Double.NaN).toFloat() } }
        fun d(k: String) = c.getJSONArray(k).let { a -> DoubleArray(n) { a.getDouble(it) } }
        fun i(k: String) = c.getJSONArray(k).let { a -> IntArray(n) { a.getInt(it) } }
        fun l(k: String) = c.getJSONArray(k).let { a -> LongArray(n) { a.getLong(it) } }
        val batch = CarrierBatch(
            size = n, siteLat = d("lat"), siteLon = d("lon"), heightM = f("hTx"), azimuthDeg = f("az"),
            tiltDeg = f("tilt"), eirpDbm = f("eirpDbm"), freqMhz = f("freqMhz"), bandwidthHz = i("bwHz"),
            tech = ByteArray(n) { c.getJSONArray("tech").getInt(it).toByte() },
            network = ByteArray(n),  // the vectors are single-network: freqKey classes match the reference's
            hBeamwidthDeg = f("hBw"), frontToBackDb = f("fb"), gainDbi = f("gain"),
            sectorId = i("sectorKey"), sddId = l("sddId"), bandIdx = ByteArray(n), bandLabels = listOf("B"),
        )
        return Vector(json, batch)
    }

    private fun assertCodes(what: String, got: ByteArray, ref: IntArray) {
        assertThat(got.size).isEqualTo(ref.size)
        var diff = 0
        var worst = 0
        for (k in ref.indices) {
            val d = abs((got[k].toInt() and 0xFF) - ref[k])
            if (d != 0) diff++
            worst = maxOf(worst, d)
        }
        assertThat(worst).isAtMost(1)
        assertThat(diff.toDouble()).isAtMost(0.001 * ref.size)
    }

    private fun checkAgainstReference(file: String) {
        val v = loadVector(file)
        val engine = CoverageEngine()
        val prepared = engine.prepare(v.batch, null, v.indoor)
        assertThat(prepared.size).isEqualTo(v.batch.size)  // no carrier of the vector is unusable
        // The natively derived per-carrier values match the reference's.
        val eirpRe = v.carriers.getJSONArray("eirpRe")
        val rMax = v.carriers.getJSONArray("rMaxKm")
        for (k in 0 until prepared.size) {
            assertThat(prepared.batchIndex(k)).isEqualTo(k)
            assertThat(prepared.eirpReAt(k).toDouble()).isWithin(1e-3).of(eirpRe.getDouble(k))
            assertThat(prepared.rMaxKmAt(k).toDouble()).isWithin(0.02).of(rMax.getDouble(k))
        }
        val tile = engine.render(prepared, v.z, v.x, v.y, v.area, v.thr, CancelToken())!!
        assertThat(tile.z).isEqualTo(v.z)
        assertCodes("rsrp", tile.rsrp, v.codes("rsrpCodes"))
        assertCodes("sinr", tile.sinr, v.codes("sinrCodes"))
        assertCodes("prob", tile.prob, v.codes("probCodes"))
        // best: the same server wherever both cover (at most 0.1% of pixels apart), none exactly where RSRP code = 0.
        val refRsrp = v.codes("rsrpCodes")
        val refBest = v.codes("best")
        var covered = 0
        var apart = 0
        for (k in refRsrp.indices) {
            val got = tile.bestCarrier(k)
            if (tile.rsrp[k].toInt() == 0) {
                assertThat(got).isEqualTo(-1)
            } else {
                assertThat(got).isAtLeast(0)
                assertThat(got).isLessThan(prepared.size)
                if (refRsrp[k] != 0) {
                    covered++
                    if (got != refBest[k]) apart++
                }
            }
        }
        assertThat(covered).isGreaterThan(1000)
        assertThat(apart.toDouble()).isAtMost(0.001 * refRsrp.size)
    }

    @Test fun canberraZ13TileMatchesTheReference() = checkAgainstReference("tile_canberra_z13.json")

    @Test fun urbanIndoorTileMatchesTheReference() = checkAgainstReference("tile_canberra_z13_urban_indoor_mid.json")

    /** The Canberra carriers repeated to [n], as a cost-heavy region for the cancel test. */
    private fun heavy(v: Vector, n: Int): CarrierBatch {
        val b = v.batch
        return CarrierBatch(
            size = n, siteLat = DoubleArray(n) { b.siteLat[it % b.size] }, siteLon = DoubleArray(n) { b.siteLon[it % b.size] },
            heightM = FloatArray(n) { b.heightM[it % b.size] }, azimuthDeg = FloatArray(n) { b.azimuthDeg[it % b.size] },
            tiltDeg = FloatArray(n) { b.tiltDeg[it % b.size] }, eirpDbm = FloatArray(n) { b.eirpDbm[it % b.size] },
            freqMhz = FloatArray(n) { b.freqMhz[it % b.size] }, bandwidthHz = IntArray(n) { b.bandwidthHz[it % b.size] },
            tech = ByteArray(n) { b.tech[it % b.size] }, network = ByteArray(n),
            hBeamwidthDeg = FloatArray(n) { b.hBeamwidthDeg[it % b.size] },
            frontToBackDb = FloatArray(n) { b.frontToBackDb[it % b.size] }, gainDbi = FloatArray(n) { b.gainDbi[it % b.size] },
            sectorId = IntArray(n) { it }, sddId = LongArray(n) { it.toLong() }, bandIdx = ByteArray(n),
            bandLabels = b.bandLabels,
        )
    }

    @Test fun cancelTokenStopsAPreCancelledRenderImmediately() {
        val v = loadVector("tile_canberra_z13.json")
        val engine = CoverageEngine()
        val prepared = engine.prepare(v.batch, null, false)
        val token = CancelToken()
        token.cancel()
        assertThat(engine.render(prepared, v.z, v.x, v.y, v.area, v.thr, token)).isNull()
        // A fresh token renders normally on the same prepared carriers.
        assertThat(engine.render(prepared, v.z, v.x, v.y, v.area, v.thr, CancelToken())).isNotNull()
    }

    @Test fun cancelTokenCancelsALongRenderInFlight() {
        val v = loadVector("tile_canberra_z13.json")
        val engine = CoverageEngine()
        val prepared = engine.prepare(heavy(v, 60_000), null, false)
        assertThat(prepared.size).isEqualTo(60_000)
        val token = CancelToken()
        val started = CountDownLatch(1)
        val result = AtomicReference<RenderedTile?>()
        val finishedAt = AtomicReference(0L)
        val t = Thread {
            started.countDown()
            result.set(engine.render(prepared, v.z, v.x, v.y, v.area, v.thr, token))
            finishedAt.set(System.nanoTime())
        }
        t.start()
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
        Thread.sleep(100)
        val cancelledAt = System.nanoTime()
        token.cancel()
        t.join(20_000)
        assertThat(t.isAlive).isFalse()
        assertThat(result.get()).isNull()  // it was still rendering after 100 ms
        val latencyMs = (finishedAt.get() - cancelledAt) / 1_000_000
        assertThat(latencyMs).isLessThan(3_000)
    }

    @Test fun invalidArgumentsAndClosedCarriersAreRejected() {
        val v = loadVector("tile_canberra_z13.json")
        val engine = CoverageEngine()
        val prepared = engine.prepare(v.batch, null, false)
        assertThrows(IllegalArgumentException::class.java) {
            engine.render(prepared, v.z, 1 shl v.z, v.y, v.area, v.thr, CancelToken())
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.render(prepared, -1, 0, 0, v.area, v.thr, CancelToken())
        }
        prepared.close()
        prepared.close()
        assertThrows(IllegalStateException::class.java) {
            engine.render(prepared, v.z, v.x, v.y, v.area, v.thr, CancelToken())
        }
    }

    @Test fun anEmptySelectionRendersAnEmptyTile() {
        val v = loadVector("tile_canberra_z13.json")
        val b = v.batch
        val none = CarrierBatch(
            0, DoubleArray(0), DoubleArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0),
            IntArray(0), ByteArray(0), ByteArray(0), FloatArray(0), FloatArray(0), FloatArray(0), IntArray(0),
            LongArray(0), ByteArray(0), b.bandLabels,
        )
        val engine = CoverageEngine()
        val tile = engine.render(engine.prepare(none, null, false), v.z, v.x, v.y, v.area, v.thr, CancelToken())!!
        assertThat(tile.rsrp.all { it.toInt() == 0 }).isTrue()
        assertThat(tile.best.all { it == 0xFFFF.toShort() }).isTrue()
    }

    @Test fun concurrentRendersOfTheSamePreparedCarriersAgree() {
        val v = loadVector("tile_canberra_z13.json")
        val engine = CoverageEngine()
        val prepared = engine.prepare(v.batch, null, false)
        val expected = engine.render(prepared, v.z, v.x, v.y, v.area, v.thr, CancelToken())!!
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = List(16) {
                pool.submit<RenderedTile> { engine.render(prepared, v.z, v.x, v.y, v.area, v.thr, CancelToken())!! }
            }
            for (f in futures) {
                val t = f.get(60, TimeUnit.SECONDS)
                assertThat(t.rsrp).isEqualTo(expected.rsrp)
                assertThat(t.sinr).isEqualTo(expected.sinr)
                assertThat(t.prob).isEqualTo(expected.prob)
                assertThat(t.best).isEqualTo(expected.best)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun twoHundredRendersDoNotGrowTheNativeHeap() {
        val v = loadVector("tile_canberra_z13.json")
        val engine = CoverageEngine()
        val prepared = engine.prepare(v.batch, null, false)
        repeat(10) { engine.render(prepared, v.z, v.x, v.y, v.area, v.thr, CancelToken()) }  // warm-up: thread scratch
        System.gc()
        val before = Debug.getNativeHeapAllocatedSize()
        repeat(200) { assertThat(engine.render(prepared, v.z, v.x, v.y, v.area, v.thr, CancelToken())).isNotNull() }
        System.gc()
        val delta = Debug.getNativeHeapAllocatedSize() - before
        android.util.Log.i("CoverageEngineTest", "native heap delta over 200 renders: $delta bytes")
        assertThat(delta).isLessThan(2L * 1024 * 1024)
    }
}
