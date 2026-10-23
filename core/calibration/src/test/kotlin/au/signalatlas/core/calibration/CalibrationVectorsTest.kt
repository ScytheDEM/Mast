package au.signalatlas.core.calibration

import au.signalatlas.core.model.CalibrationRules
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test

/** Rules 1-6 against tools/calibration/reference.py (vectors.json, written by tools/calibration/make_vectors.py). */
class CalibrationVectorsTest {
    private val doc: JsonObject = Json.parseToJsonElement(
        javaClass.getResource("/calibration/vectors.json")!!.readText(),
    ).jsonObject

    private fun JsonElement.d() = jsonPrimitive.double
    private fun JsonElement.dOrNull() = if (this is JsonNull) null else jsonPrimitive.double
    private fun JsonElement.doubles() = jsonArray.map { it.d() }.toDoubleArray()
    private fun arr(k: String): JsonArray = doc[k]!!.jsonArray

    @Test fun scores() {
        assertThat(arr("scores")).hasSize(40)
        for (e in arr("scores")) {
            val o = e.jsonObject
            val got = CalibrationMath.matchScore(o["predicted"]!!.d(), o["measured"]!!.d(), o["distanceM"]!!.d(), o["taM"]!!.dOrNull(), o["rMaxKm"]!!.d())
            assertThat(got).isWithin(1e-9).of(o["score"]!!.d())
        }
    }

    @Test fun confidence() {
        for (e in arr("confidence")) {
            val o = e.jsonObject
            val want = if (o["result"] is JsonNull) null else Confidence.valueOf(o["result"]!!.jsonPrimitive.content)
            assertThat(CalibrationMath.confidence(o["scores"]!!.doubles().toList())).isEqualTo(want)
        }
    }

    @Test fun throttle() {
        for (e in arr("throttle")) {
            val o = e.jsonObject
            val last = (o["last"] as? JsonObject)?.let { Fix(it["lat"]!!.d(), it["lon"]!!.d(), 5.0, it["ms"]!!.jsonPrimitive.long) }
            val now = Fix(o["lat"]!!.d(), o["lon"]!!.d(), 5.0, o["nowMs"]!!.jsonPrimitive.long)
            assertThat(CalibrationMath.throttleOk(last, now)).isEqualTo(o["ok"]!!.jsonPrimitive.boolean)
        }
    }

    @Test fun sectorFits() {
        val fits = arr("fits")
        assertThat(fits.map { it.jsonObject["slopeFitted"]!!.jsonPrimitive.boolean }).containsAtLeast(true, false)
        for (e in fits) {
            val o = e.jsonObject
            val f = CalibrationMath.fitSector(o["residuals"]!!.doubles(), o["distancesKm"]!!.doubles())
            assertThat(f.n).isEqualTo(o["n"]!!.jsonPrimitive.int)
            assertThat(f.offsetDb).isWithin(1e-9).of(o["offset"]!!.d())
            assertThat(f.slopeDbPerDecade).isWithin(1e-9).of(o["slope"]!!.d())
            assertThat(f.dMedianKm).isWithin(1e-12).of(o["dMedianKm"]!!.d())
            assertThat(f.slopeFitted).isEqualTo(o["slopeFitted"]!!.jsonPrimitive.boolean)
            assertThat(f.offsetDb).isIn(com.google.common.collect.Range.closed(-20.0, 20.0))
        }
    }

    @Test fun sigma() {
        for (e in arr("sigma")) {
            val o = e.jsonObject
            val n = o["n"]!!.jsonPrimitive.int
            assertThat(CalibrationMath.sigmaEff(o["sigma"]!!.d(), n)).isWithin(1e-12).of(o["sigmaEff"]!!.d())
            assertThat(CalibrationRules.sigmaScale(n)).isWithin(1e-12).of(o["scale"]!!.d())
        }
    }

    @Test fun residualCellsFromPerSectorSums() {
        val res = doc["residual"]!!.jsonObject
        val samples = res["samples"]!!.jsonArray.map { it.jsonObject }
        // The per-(network, cell, sector) sums the store keeps.
        data class K(val net: Int, val lat: Int, val lon: Int, val sector: String)
        val n = HashMap<K, Int>()
        val sumR = HashMap<K, Double>()
        val sumLog = HashMap<K, Double>()
        for (s in samples) {
            val k = K(s["network"]!!.jsonPrimitive.int, CalibrationRules.cellIndex(s["lat"]!!.d()), CalibrationRules.cellIndex(s["lon"]!!.d()), "s${s["sector"]!!.jsonPrimitive.int}")
            n.merge(k, 1, Int::plus)
            sumR.merge(k, s["r"]!!.d(), Double::plus)
            sumLog.merge(k, Estimator.log10Km(s["dKm"]!!.d() * 1000.0), Double::plus)
        }
        val rows = n.keys.map { CalibrationDb.ResidualRow(it.net, it.lat, it.lon, it.sector, n[it]!!, sumR[it]!!, sumLog[it]!!) }
        val fits = res["fits"]!!.jsonObject.entries.associate { (sid, f) ->
            val o = f.jsonObject
            "s$sid" to SectorFit(o["n"]!!.jsonPrimitive.int, o["offset"]!!.d(), o["slope"]!!.d(), o["dMedianKm"]!!.d(), o["slopeFitted"]!!.jsonPrimitive.boolean)
        }
        val got = Estimator.residualCells(rows, fits)
        val want = res["cells"]!!.jsonArray.map { it.jsonObject }
        assertThat(got).hasSize(want.size)
        assertThat(want.count { it["appliedDb"]!!.d() != 0.0 }).isGreaterThan(0)
        for ((g, w) in got.zip(want)) {
            assertThat(g.network).isEqualTo(w["network"]!!.jsonPrimitive.int)
            assertThat(g.latIdx).isEqualTo(w["latIdx"]!!.jsonPrimitive.int)
            assertThat(g.lonIdx).isEqualTo(w["lonIdx"]!!.jsonPrimitive.int)
            assertThat(g.n).isEqualTo(w["n"]!!.jsonPrimitive.int)
            assertThat(g.meanDb).isWithin(1e-9).of(w["meanDb"]!!.d())
            assertThat(g.appliedDb).isWithin(1e-9).of(w["appliedDb"]!!.d())
        }
    }
}
