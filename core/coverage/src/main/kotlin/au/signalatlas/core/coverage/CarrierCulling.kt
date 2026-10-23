package au.signalatlas.core.coverage

import au.signalatlas.core.data.CarrierBatch
import au.signalatlas.core.model.GeoBounds
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow

/** Row selection for batches over the engine's carrier cap (ruling JJ, levels 2 and 3). */
internal object CarrierCulling {
    private const val MAX_RANGE_KM = 80.0
    private const val KM_PER_DEG = 111.195

    /**
     * Conservative reach in km: the distance at which free-space loss alone brings the most favourable RSRP
     * (EIRP spread over the fewest resource blocks, 6, and no antenna attenuation) down to -140 dBm, plus a 10 dB
     * margin, capped at 80 km. Every modelled path loss is at least free-space loss, so a carrier beyond this
     * distance cannot reach -140 dBm and the engine's own (tighter) culling would drop it anyway.
     */
    fun reachKm(eirpDbm: Float, freqMhz: Float): Double {
        val eirpRe = eirpDbm - 10 * log10(72.0)
        val budget = eirpRe + 140.0 + 10.0 - 32.44 - 20 * log10(freqMhz.toDouble())
        return 10.0.pow(budget / 20).coerceAtMost(MAX_RANGE_KM)
    }

    /** Rows whose reach touches [area]. */
    fun reaching(b: CarrierBatch, area: GeoBounds): IntArray {
        val out = IntArray(b.size)
        var n = 0
        for (i in 0 until b.size) {
            val lat = b.siteLat[i].coerceIn(area.south, area.north)
            val lon = b.siteLon[i].coerceIn(area.west, area.east)
            val km = TileMath.distanceM(b.siteLat[i], b.siteLon[i], lat, lon) / 1000
            if (!b.eirpDbm[i].isFinite() || !b.freqMhz[i].isFinite() || km <= reachKm(b.eirpDbm[i], b.freqMhz[i])) out[n++] = i
        }
        return out.copyOf(n)
    }

    /** The [limit] rows nearest the point (original order kept). */
    fun nearest(b: CarrierBatch, lat: Double, lon: Double, limit: Int): IntArray {
        val cosLat = cos(Math.toRadians(lat))
        val d = DoubleArray(b.size) { val dy = b.siteLat[it] - lat; val dx = (b.siteLon[it] - lon) * cosLat; dx * dx + dy * dy }
        return (0 until b.size).sortedBy { d[it] }.take(limit).sorted().toIntArray()
    }
}

internal fun CarrierBatch.empty(): CarrierBatch = subset(IntArray(0))

internal fun CarrierBatch.subset(rows: IntArray): CarrierBatch = CarrierBatch(
    rows.size,
    DoubleArray(rows.size) { siteLat[rows[it]] }, DoubleArray(rows.size) { siteLon[rows[it]] },
    FloatArray(rows.size) { heightM[rows[it]] }, FloatArray(rows.size) { azimuthDeg[rows[it]] },
    FloatArray(rows.size) { tiltDeg[rows[it]] }, FloatArray(rows.size) { eirpDbm[rows[it]] },
    FloatArray(rows.size) { freqMhz[rows[it]] }, IntArray(rows.size) { bandwidthHz[rows[it]] },
    ByteArray(rows.size) { tech[rows[it]] }, ByteArray(rows.size) { network[rows[it]] },
    FloatArray(rows.size) { hBeamwidthDeg[rows[it]] }, FloatArray(rows.size) { frontToBackDb[rows[it]] },
    FloatArray(rows.size) { gainDbi[rows[it]] }, IntArray(rows.size) { sectorId[rows[it]] },
    LongArray(rows.size) { sddId[rows[it]] }, ByteArray(rows.size) { bandIdx[rows[it]] }, bandLabels,
)
