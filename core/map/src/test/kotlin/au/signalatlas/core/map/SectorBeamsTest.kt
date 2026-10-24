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

    @Test fun aDirectionalSectorIsSlicedWithOpacityFollowingThePattern() {
        // 0 dB at bearing 90, 30 dB elsewhere.
        val att = FloatArray(360) { if (it in 88..92) 0f else 30f }
        val f = features(SectorBeams.toGeoJson(SiteBeams(-35.0, 149.0, listOf(BeamSpec(Network.OPTUS, 90.0, att))), 2000.0))
        assertThat(f).hasSize(2 * SectorBeams.HALF_SPAN_DEG / SectorBeams.SLICE_DEG)
        val ops = f.map { it.jsonObject.getValue("properties").jsonObject.getValue("op").jsonPrimitive.double }
        assertThat(ops.max()).isWithin(1e-6).of(0.5)
        assertThat(ops.min()).isLessThan(0.1)
        assertThat(f[0].jsonObject.getValue("properties").jsonObject.getValue("net").jsonPrimitive.content).isEqualTo("OPTUS")
    }

    @Test fun anOmniSectorCoversTheWholeCircle() {
        val f = features(SectorBeams.toGeoJson(SiteBeams(-35.0, 149.0, listOf(BeamSpec(Network.TPG, null, FloatArray(360)))), 1000.0))
        assertThat(f).hasSize(360 / SectorBeams.SLICE_DEG)
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
