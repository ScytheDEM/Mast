package au.signalatlas.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Calibration rules 4 and 6 for one carrier (its sector's estimate): RSRP gains
 * `offsetDb + slopeDbPerDecade * log10(clamp(d / dMedianKm, 0.1, 10))` at horizontal distance d, and the shadowing
 * sigma becomes `max(4, sigma * sigmaScale)` when [sigmaScale] < 1. NaN [slopeDbPerDecade] or a [dMedianKm] that is
 * not > 0 means no slope term.
 */
data class SectorTerms(
    val offsetDb: Float,
    val slopeDbPerDecade: Float = 0f,
    val dMedianKm: Float = 1f,
    val sigmaScale: Float = 1f,
) {
    companion object {
        /** No correction (the engine's result is the uncalibrated model's). */
        val NONE = SectorTerms(0f, 0f, 1f, 1f)
    }
}

/**
 * Calibration rule 5: one tile's residual raster, 256 x 256 int8 codes in 0.25 dB steps (row-major, row 0 = north),
 * added to the RSRP of every carrier whose network ordinal's bit is set in [networkMask]. The codes are copied into a
 * direct buffer once, so a raster can be used by any number of renders.
 */
class ResidualRaster(val networkMask: Int, codes: ByteArray) {
    init {
        require(codes.size == RenderedTile.PIXELS) { "a residual raster has ${RenderedTile.PIXELS} codes, got ${codes.size}" }
    }

    internal val buffer: ByteBuffer = ByteBuffer.allocateDirect(RenderedTile.PIXELS).order(ByteOrder.nativeOrder()).also {
        it.put(codes)
        it.rewind()
    }

    companion object {
        /** The engine accepts at most this many rasters per render. */
        const val MAX_PER_TILE = 32

        /** dB -> code, rounded and clamped to int8 (+-31.75 dB). */
        fun code(db: Double): Byte = Math.round(db * 4.0).coerceIn(-128L, 127L).toByte()
    }
}
