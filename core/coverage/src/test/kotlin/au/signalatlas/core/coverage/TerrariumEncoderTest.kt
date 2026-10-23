package au.signalatlas.core.coverage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerrariumEncoderTest {
    private fun decode(png: ByteArray): Bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)!!

    /** Terrarium: elevation = R·256 + G + B/256 − 32768. */
    private fun Bitmap.elevation(i: Int): Double {
        val p = getPixel(i % 256, i / 256)
        assertThat((p ushr 24) and 0xFF).isEqualTo(0xFF)
        return ((p shr 16) and 0xFF) * 256.0 + ((p shr 8) and 0xFF) + (p and 0xFF) / 256.0 - 32768.0
    }

    private fun codes(f: (Int) -> Int) = ByteArray(TerrariumEncoder.PIXELS) { f(it).toByte() }

    @Test fun rsrpViewRoundTripsEveryCode() {
        val rsrp = codes { it % 256 }
        val bmp = decode(TerrariumEncoder.encode(rsrp, CoverageSelection.View.RSRP, codes { 0 }))
        assertThat(bmp.width).isEqualTo(256)
        assertThat(bmp.height).isEqualTo(256)
        for (code in 0..255) {
            val expected = if (code == 0) -200.0 else code / 2.0 - 140.0
            assertThat(bmp.elevation(code)).isEqualTo(expected)
        }
    }

    @Test fun probabilityViewIsPercentAndMinus200WhereThereIsNoRsrp() {
        val rsrp = codes { if (it % 2 == 0) 0 else 100 }
        val prob = codes { 255 * (it % 256) / 255 }
        val bmp = decode(TerrariumEncoder.encode(rsrp, CoverageSelection.View.PROBABILITY, prob))
        assertThat(bmp.elevation(0)).isEqualTo(-200.0) // even pixel: rsrp code 0
        for (i in intArrayOf(1, 51, 127, 255)) {
            val expected = (i % 256) / 255.0 * 100.0
            assertThat(bmp.elevation(i)).isWithin(1.0 / 256).of(expected)
        }
    }

    @Test fun pixelsAreRowMajor() {
        val rsrp = codes { if (it == 256 * 3 + 7) 200 else 1 } // row 3, column 7
        val bmp = decode(TerrariumEncoder.encode(rsrp, CoverageSelection.View.RSRP, codes { 0 }))
        assertThat(bmp.elevation(256 * 3 + 7)).isEqualTo(-40.0)
        assertThat(bmp.elevation(256 * 7 + 3)).isEqualTo(-139.5)
    }
}
