package au.signalatlas.core.coverage

import au.signalatlas.core.model.BandPlan
import au.signalatlas.engine.TerrainTable
import kotlin.math.floor
import kotlin.math.max

/**
 * Terrain rule 6 table keys. Coarse on purpose: a dense city centre has hundreds of (site, carrier frequency, height)
 * combinations in reach of one region, far over [TerrainCoordinator.MAX_TABLES_PER_REGION], so tables are shared by
 * every carrier of a site in the same 3GPP band at about the same height. The table is built at the key's values, so the
 * wavelength is off by at most half a band width (<= ~1 dB diffraction error, Ruling UU).
 */
internal object TerrainKeys {
    /** Heights are rounded to a multiple of this (m). */
    const val HEIGHT_STEP_M = 2

    /** The 3GPP band centre of [freqMhz] in kHz (B28 -> 780,500, n78 -> 3,550,000); outside every band: rounded half up to 10 MHz. */
    fun frequencyKhz(freqMhz: Float): Int {
        BandPlan.centreMhz(freqMhz.toDouble())?.let { return Math.round(it * 1000).toInt() }
        return TerrainTable.frequencyKeyMhz(freqMhz) * 1000
    }

    /** Antenna height AGL clamped to >= 3 m (M2 rule 1) then rounded half up to [HEIGHT_STEP_M] (NaN counts as 3 m). */
    fun heightM(hTxAgl: Float): Int =
        if (hTxAgl.isNaN()) 4 else floor(max(hTxAgl.toDouble(), 3.0) / HEIGHT_STEP_M + 0.5).toInt() * HEIGHT_STEP_M
}
