package au.signalatlas.car

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/** A north-up Web Mercator camera in MapLibre's logical pixels (512 px world at zoom 0). Pure. */
class CarCamera(var lat: Double, var lon: Double, var zoom: Double) {
    private fun world(): Double = TILE * 2.0.pow(zoom)

    private fun mx(lon: Double) = (lon + 180.0) / 360.0 * world()
    private fun my(lat: Double): Double {
        val s = tan(PI / 4 + Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)) / 2)
        return (0.5 - ln(s) / (2 * PI)) * world()
    }

    /** Pixel position of a point in a [width] x [height] view centred on the camera. */
    fun project(pLat: Double, pLon: Double, width: Float, height: Float): Pair<Float, Float> =
        Pair((width / 2 + (mx(pLon) - mx(lon))).toFloat(), (height / 2 + (my(pLat) - my(lat))).toFloat())

    /** Drags the map by a finger movement of ([dx], [dy]) pixels. */
    fun pan(dx: Float, dy: Float) {
        val x = mx(lon) + dx
        val y = my(lat) + dy
        lon = (x / world() * 360.0 - 180.0).let { ((it + 180.0) % 360.0 + 360.0) % 360.0 - 180.0 }
        val n = PI * (1 - 2 * y / world())
        lat = Math.toDegrees(atan((exp(n) - exp(-n)) / 2)).coerceIn(-MAX_LAT, MAX_LAT)
    }

    fun zoomBy(delta: Double) {
        zoom = (zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    companion object {
        const val TILE = 512.0
        const val MAX_LAT = 85.0
        const val MIN_ZOOM = 3.0
        const val MAX_ZOOM = 18.0
        const val DEFAULT_ZOOM = 14.5
    }
}
