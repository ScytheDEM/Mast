package au.signalatlas.core.calibration

import au.signalatlas.core.model.CalibrationRules
import au.signalatlas.core.model.ResidualCell
import au.signalatlas.core.model.SectorCorrection
import kotlin.math.log10
import kotlin.math.max

/**
 * Rules 4-5, incremental (rule 7): each sample updates only its own sector's statistics. Below 30 samples the offset
 * comes from (n, sum r) in O(1); from 30 the sector's samples are refitted (offset and slope; the slope needs a 3x
 * distance spread). Residual cells keep per-sector sums (n, sum r, sum log10 d), so a cell's mean after the sector
 * corrections is exact for the current fits without revisiting samples.
 */
internal object Estimator {
    /** The sector row after adding [s]; [samplesOf] loads the sector's samples (including [s]) for a refit. */
    fun updateSector(prev: CalibrationDb.SectorRow?, s: Sample, samplesOf: () -> Pair<DoubleArray, DoubleArray>): CalibrationDb.SectorRow {
        val n = (prev?.n ?: 0) + 1
        val sumR = (prev?.sumR ?: 0.0) + s.residualDb
        val fit = if (n >= CalibrationMath.SLOPE_MIN_N) {
            val (r, d) = samplesOf()
            CalibrationMath.fitSector(r, d)
        } else {
            SectorFit(n, CalibrationMath.offsetOnly(n, sumR), 0.0, prev?.dMedianKm ?: 1.0, false)
        }
        return CalibrationDb.SectorRow(
            s.sectorId, s.carrierNetwork.ordinal, s.siteLat, s.siteLon, s.sddId, n, sumR, fit.offsetDb, fit.slopeDbPerDecade,
            fit.dMedianKm, s.timeMs,
        )
    }

    fun log10Km(distanceM: Double): Double = log10(max(distanceM / 1000.0, 1e-6))

    fun fitOf(r: CalibrationDb.SectorRow) = SectorFit(r.n, r.offsetDb, r.slope, r.dMedianKm, r.slope != 0.0)

    fun correction(r: CalibrationDb.SectorRow) =
        SectorCorrection(r.sectorId, r.network, r.siteLat, r.siteLon, r.n, r.offsetDb, r.slope, r.dMedianKm)

    /**
     * Rule 5: per (network, cell) n and the mean of r - (applied sector correction) over its samples, from per-sector
     * sums: sum r - n mu - b (sum log10 d - n log10 d_median) for sectors whose correction applies (n >= 3).
     */
    fun residualCells(rows: List<CalibrationDb.ResidualRow>, fits: Map<Int, SectorFit>): List<ResidualCell> {
        data class Key(val network: Int, val lat: Int, val lon: Int)
        val n = HashMap<Key, Int>()
        val sum = HashMap<Key, Double>()
        for (row in rows) {
            val k = Key(row.network, row.latIdx, row.lonIdx)
            val f = fits[row.sectorId]?.takeIf { it.n >= CalibrationRules.MIN_APPLIED_SAMPLES }
            val corrected = if (f == null) row.sumR else
                row.sumR - row.n * f.offsetDb - f.slopeDbPerDecade * (row.sumLogD - row.n * log10(f.dMedianKm))
            n.merge(k, row.n, Int::plus)
            sum.merge(k, corrected, Double::plus)
        }
        return n.keys.sortedWith(compareBy({ it.network }, { it.lat }, { it.lon })).map { k ->
            ResidualCell(k.network, k.lat, k.lon, n.getValue(k), sum.getValue(k) / n.getValue(k))
        }
    }
}
