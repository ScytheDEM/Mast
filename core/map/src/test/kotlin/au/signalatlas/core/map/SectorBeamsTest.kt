package au.signalatlas.core.map

import au.signalatlas.core.model.Network
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.math.abs

class SectorBeamsTest {
    private fun features(json: String) = Json.parseToJsonElement(json).jsonObject.getValue("features").jsonArray

    @Test fun lengthIsAboutTwoKmAtZ13AndScalesWithZoom() {
        assertThat(SectorBeams.lengthMetersForZoom(13.0)).isWithin(1.0).of(2000.0)
        assertThat(SectorBeams.lengthMetersForZoom(null)).isWithin(1.0).of(2000.0)
        assertThat(SectorBeams.lengthMetersForZoom(12.0)).isWithin(1.0).of(4000.0)
        assertThat(SectorBeams.lengthMetersForZoom(20.0)).isEqualTo(150.0)
        assertThat(SectorBeams.lengthMetersForZoom(3.0)).isEqualTo(8000.0)
    }

    private fun props(f: kotlinx.serialization.json.JsonElement) = f.jsonObject.getValue("properties").jsonObject

    private fun ring(f: kotlinx.serialization.json.JsonElement) =
        f.jsonObject.getValue("geometry").jsonObject.getValue("coordinates").jsonArray[0].jsonArray.map {
            val p = it.jsonArray
            p[1].jsonPrimitive.double to p[0].jsonPrimitive.double // (lat, lon)
        }

    /** A 65° panel at [az] with 25 dB front-to-back. */
    private fun panel(az: Double) = FloatArray(360) { b ->
        val d = Math.abs(((b - az) % 360 + 540) % 360 - 180)
        minOf(12.0 * (d / 65.0) * (d / 65.0), 25.0).toFloat()
    }

    @Test fun radiusFollowsFieldStrength() {
        assertThat(SectorBeams.radiusFraction(0f)).isWithin(1e-9).of(1.0)
        assertThat(SectorBeams.radiusFraction(6.0206f)).isWithin(1e-4).of(0.5)
        assertThat(SectorBeams.radiusFraction(20f)).isWithin(1e-9).of(0.1)
        assertThat(SectorBeams.radiusFraction(60f)).isEqualTo(SectorBeams.MIN_RADIUS)
    }

    @Test fun aDirectionalBeamIsOneLobeWhoseFarthestPointIsItsAzimuth() {
        for (az in listOf(10.0, 130.0, 240.0, 315.0)) {
            val beams = SiteBeams(-35.3, 149.13, listOf(BeamSpec(Network.TELSTRA, az, panel(az), label = "x")))
            val f = features(SectorBeams.toGeoJson(beams, 2000.0))
            val lobes = f.filter { props(it).getValue("kind").jsonPrimitive.content == "lobe" }
            assertThat(lobes).hasSize(1)
            assertThat(props(lobes[0]).getValue("net").jsonPrimitive.content).isEqualTo("TELSTRA")
            val pts = ring(lobes[0])
            assertThat(pts).hasSize(360 / SectorBeams.STEP_DEG + 1) // closed
            assertThat(pts.first()).isEqualTo(pts.last())
            val far = pts.maxBy { (lat, lon) -> dist(-35.3, 149.13, lat, lon) }
            assertThat(angle(bearing(-35.3, 149.13, far.first, far.second), az)).isLessThan(2.5)
            assertThat(dist(-35.3, 149.13, far.first, far.second)).isWithin(5.0).of(2000.0)
            // Front-to-back: the back of the lobe is 25 dB down, about 6% of the length.
            val back = pts.minBy { (lat, lon) -> angle(bearing(-35.3, 149.13, lat, lon), az + 180) }
            assertThat(dist(-35.3, 149.13, back.first, back.second)).isWithin(5.0).of(2000.0 * 0.0562)
        }
    }

    @Test fun labelSitsJustPastTheTip() {
        val beams = SiteBeams(-35.0, 149.0, listOf(BeamSpec(Network.OPTUS, 90.0, panel(90.0), label = "90° B28 \"x\"")))
        val label = features(SectorBeams.toGeoJson(beams, 1000.0)).single { props(it).getValue("kind").jsonPrimitive.content == "label" }
        assertThat(props(label).getValue("text").jsonPrimitive.content).isEqualTo("90° B28 \"x\"")
        val c = label.jsonObject.getValue("geometry").jsonObject.getValue("coordinates").jsonArray
        val lat = c[1].jsonPrimitive.double
        val lon = c[0].jsonPrimitive.double
        assertThat(angle(bearing(-35.0, 149.0, lat, lon), 90.0)).isLessThan(0.5)
        assertThat(dist(-35.0, 149.0, lat, lon)).isWithin(5.0).of(1000.0 * SectorBeams.LABEL_AT)
    }

    @Test fun anOmniBeamIsACircle() {
        val f = features(SectorBeams.toGeoJson(SiteBeams(-35.0, 149.0, listOf(BeamSpec(Network.TPG, null, FloatArray(360)))), 1000.0))
        assertThat(f).hasSize(1) // no label without one
        ring(f[0]).forEach { (lat, lon) -> assertThat(dist(-35.0, 149.0, lat, lon)).isWithin(1.0).of(1000.0) }
    }

    private fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1) * kotlin.math.cos(Math.toRadians((lat1 + lat2) / 2))
        return 6_371_000.0 * kotlin.math.sqrt(dLat * dLat + dLon * dLon)
    }

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = lat2 - lat1
        val dLon = (lon2 - lon1) * kotlin.math.cos(Math.toRadians(lat1))
        return (Math.toDegrees(kotlin.math.atan2(dLon, dLat)) + 360) % 360
    }

    private fun angle(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    @Test fun peakBearingPointsTheRightWayAtTheRightDistance() {
        val (lat, lon) = SectorBeams.destination(-35.0, 149.0, 90.0, 2000.0)
        assertThat(lat).isWithin(0.001).of(-35.0)
        assertThat(lon - 149.0).isWithin(0.0005).of(2000.0 / (111_320.0 * kotlin.math.cos(Math.toRadians(35.0))))
        val (nLat, _) = SectorBeams.destination(-35.0, 149.0, 0.0, 2000.0)
        assertThat(abs(nLat - -35.0)).isWithin(0.0005).of(2000.0 / 111_195.0)
        assertThat(nLat).isGreaterThan(-35.0)
    }

    @Test fun trackerSaysLoadingWhileCoverageTilesAreOutstanding() {
        val t = CoverageLoadTracker()
        assertThat(t.onTile(CoverageLayer.TileEvent.NETWORK_REQUESTED, 12, 1, 2, "sites")).isFalse() // not a coverage source
        assertThat(t.onTile(CoverageLayer.TileEvent.NETWORK_REQUESTED, 12, 1, 2, "coverage")).isTrue()
        assertThat(t.onTile(CoverageLayer.TileEvent.NETWORK_REQUESTED, 12, 1, 3, "coverage-b")).isTrue()
        assertThat(t.onTile(CoverageLayer.TileEvent.NETWORK_LOADED, 12, 1, 2, "coverage")).isTrue()
        assertThat(t.onTile(CoverageLayer.TileEvent.PARSED, 12, 1, 2, "coverage")).isTrue()
        assertThat(t.onTile(CoverageLayer.TileEvent.FAILED, 12, 1, 3, "coverage-b")).isFalse()
    }

    @Test fun idleClearsAStuckTile() {
        val t = CoverageLoadTracker()
        t.onTile(CoverageLayer.TileEvent.NETWORK_REQUESTED, 9, 1, 1, "coverage")
        assertThat(t.loading).isTrue()
        assertThat(t.onIdle()).isFalse()
        assertThat(t.loading).isFalse()
    }
}
