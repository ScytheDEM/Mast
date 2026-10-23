package au.signalatlas.engine

import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.max

/**
 * The polar terrain-loss table of one (site, frequency key, height key), Terrain rule 6: [table] holds 360 radials x
 * 256 range bins of `L_terr` as uint8 at 0.25 dB (`table[bearing * 256 + bin]`) and [mask] one bit per cell
 * (byte `k shr 3`, bit `k and 7`, LSB first), set when the profile to that cell lay entirely inside the DEM. Both are
 * direct buffers (garbage-collected, nothing native to free) and read-only after [CoverageEngine.buildTerrainTable]
 * returns; the renderer reads them concurrently from any thread. Keep the object reachable while a
 * [PreparedCarriers] built with it renders: that class does so itself.
 */
class TerrainTable(val table: ByteBuffer, val mask: ByteBuffer) {
    init {
        require(table.isDirect && table.capacity() >= CELLS) { "table must be a direct buffer of at least $CELLS bytes" }
        require(mask.isDirect && mask.capacity() >= MASK_BYTES) { "mask must be a direct buffer of at least $MASK_BYTES bytes" }
    }

    /** `L_terr` (dB) at the cell of radial [bearing] (0 until 360) and range bin [bin] (0 until 256), or null if masked. */
    fun cellDb(bearing: Int, bin: Int): Float? {
        require(bearing in 0 until RADIALS && bin in 0 until BINS) { "cell ($bearing, $bin) out of range" }
        val k = bearing * BINS + bin
        if ((mask.get(k shr 3).toInt() shr (k and 7)) and 1 == 0) return null
        return (table.get(k).toInt() and 0xFF) * CODE_STEP_DB
    }

    companion object {
        const val RADIALS = 360
        const val BINS = 256
        const val CELLS = RADIALS * BINS
        const val MASK_BYTES = CELLS / 8
        const val CODE_STEP_DB = 0.25f

        internal fun allocate(): TerrainTable = TerrainTable(
            ByteBuffer.allocateDirect(CELLS), ByteBuffer.allocateDirect(MASK_BYTES),
        )

        /** Rule 6 table key: the carrier frequency rounded half up to 10 MHz (lambda uses this frequency). */
        fun frequencyKeyMhz(freqMhz: Float): Int = floor(freqMhz.toDouble() / 10.0 + 0.5).toInt() * 10

        /** Rule 6 table key: the antenna height AGL, clamped to >= 3 m (M2 rule 1), rounded half up to 1 m (NaN counts as 3 m, as in the native sanitiser). */
        fun heightKeyM(hTxAgl: Float): Int =
            if (hTxAgl.isNaN()) 3 else floor(max(hTxAgl.toDouble(), 3.0) + 0.5).toInt()
    }
}
