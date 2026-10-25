package au.signalatlas.car

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CarCameraTest {
    @Test fun cameraCentreProjectsToViewCentre() {
        val c = CarCamera(-33.87, 151.21, 14.0)
        val (x, y) = c.project(-33.87, 151.21, 800f, 400f)
        assertThat(x).isWithin(0.01f).of(400f)
        assertThat(y).isWithin(0.01f).of(200f)
    }

    @Test fun eastIsRightAndNorthIsUp() {
        val c = CarCamera(-33.87, 151.21, 14.0)
        val (x, y) = c.project(-33.86, 151.22, 800f, 400f)
        assertThat(x).isGreaterThan(400f)
        assertThat(y).isLessThan(200f)
    }

    @Test fun metresPerPixelMatchesWebMercator() {
        val c = CarCamera(0.0, 0.0, 10.0)
        // At the equator one logical pixel is 40075016.7 / (512 * 2^10) m: 1 km east is about 13 px.
        val (x, _) = c.project(0.0, 1000.0 / 111_319.49, 100f, 100f)
        assertThat(x - 50f).isWithin(0.2f).of((1000.0 / (40_075_016.7 / (512 * 1024))).toFloat())
    }

    @Test fun panMovesTheCentreAndRoundTrips() {
        val c = CarCamera(-33.87, 151.21, 14.0)
        c.pan(100f, 0f)
        val (x, _) = c.project(-33.87, 151.21, 800f, 400f)
        assertThat(x).isWithin(0.1f).of(300f)
        c.pan(-100f, 0f)
        assertThat(c.lon).isWithin(1e-6).of(151.21)
        assertThat(c.lat).isWithin(1e-6).of(-33.87)
    }

    @Test fun zoomIsClamped() {
        val c = CarCamera(0.0, 0.0, 17.5)
        c.zoomBy(5.0)
        assertThat(c.zoom).isEqualTo(CarCamera.MAX_ZOOM)
        c.zoomBy(-50.0)
        assertThat(c.zoom).isEqualTo(CarCamera.MIN_ZOOM)
    }
}
