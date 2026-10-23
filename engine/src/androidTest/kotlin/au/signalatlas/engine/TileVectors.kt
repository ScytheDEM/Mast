package au.signalatlas.engine

import androidx.test.platform.app.InstrumentationRegistry
import au.signalatlas.core.data.CarrierBatch
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.json.JSONObject

/** A tile vector (Python reference output) and the carriers it was rendered from. */
internal class TileVector(val json: JSONObject, val batch: CarrierBatch) {
    val z = json.getInt("z")
    val x = json.getInt("x")
    val y = json.getInt("y")
    val area = AreaClass.valueOf(json.getString("area"))
    val indoor = json.getBoolean("indoor")
    val thr = json.getDouble("thrDbm").toFloat()
    val carriers: JSONObject = json.getJSONObject("carriers")
    fun codes(key: String): IntArray = json.getJSONArray(key).let { a -> IntArray(a.length()) { a.getInt(it) } }
}

internal object TileVectors {
    fun load(name: String): TileVector {
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
        return TileVector(json, batch)
    }

    /** Every code within +-1 of the reference and at most 0.1% not exactly equal (the M2 tile acceptance). */
    fun assertCodes(what: String, got: ByteArray, ref: IntArray) {
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
}
