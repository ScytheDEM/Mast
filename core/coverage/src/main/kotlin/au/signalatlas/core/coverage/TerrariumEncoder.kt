package au.signalatlas.core.coverage

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.floor

/**
 * Rule 10: packs a tile's codes into a Terrarium raster-dem PNG (`R = floor((e+32768)/256)`,
 * `G = floor(e+32768) mod 256`, `B = floor(frac(e+32768)·256)`).
 *
 * RSRP view: `e` = the RSRP in dBm (`code/2 − 140`), −200 for code 0 (no coverage). Probability view: `e` = P·100
 * (`code/255·100`), −200 where the RSRP code is 0. Per-code colours come from 256-entry tables, so the cost is the
 * PNG deflate (target on device: <= 5 ms per tile; measured by hand, not unit-tested).
 */
object TerrariumEncoder {
    const val SIZE = 256
    const val PIXELS = SIZE * SIZE
    private const val NO_COVERAGE = -200.0

    private val rsrpColours = IntArray(256) { code -> colour(if (code == 0) NO_COVERAGE else code / 2.0 - 140.0) }
    private val probColours = IntArray(256) { code -> colour(code / 255.0 * 100.0) }
    private val noCoverage = colour(NO_COVERAGE)

    fun encode(rsrpCodes: ByteArray, view: CoverageSelection.View, probCodes: ByteArray): ByteArray {
        require(rsrpCodes.size == PIXELS && probCodes.size == PIXELS) { "a tile is $PIXELS pixels" }
        val pixels = IntArray(PIXELS)
        when (view) {
            CoverageSelection.View.RSRP ->
                for (i in 0 until PIXELS) pixels[i] = rsrpColours[rsrpCodes[i].toInt() and 0xFF]
            CoverageSelection.View.PROBABILITY ->
                for (i in 0 until PIXELS) {
                    pixels[i] = if (rsrpCodes[i].toInt() == 0) noCoverage else probColours[probCodes[i].toInt() and 0xFF]
                }
        }
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            val out = ByteArrayOutputStream(32 * 1024)
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG compression failed" }
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** Opaque ARGB pixel for elevation [e] metres. */
    private fun colour(e: Double): Int {
        val v = e + 32768.0
        val whole = floor(v)
        val r = (whole / 256).toInt()
        val g = whole.toInt() and 0xFF
        val b = floor((v - whole) * 256).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
