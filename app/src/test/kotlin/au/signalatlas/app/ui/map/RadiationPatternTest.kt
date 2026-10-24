package au.signalatlas.app.ui.map

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import au.signalatlas.app.data.HrpPattern
import au.signalatlas.app.data.PatternAccess
import au.signalatlas.app.ui.theme.SignalAtlasTheme
import au.signalatlas.core.model.Antenna
import au.signalatlas.core.model.BandInfo
import au.signalatlas.core.model.Carrier
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Sector
import au.signalatlas.core.model.Site
import au.signalatlas.core.model.SiteDetail
import au.signalatlas.core.model.Tech
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RadiationPatternTest {
    @get:Rule val compose = createComposeRule()

    /** Byte k = 4 * k/ (so 1 dB per degree) up to 120 dB... capped at 255 (63.75 dB); k=0 is the peak. */
    private fun ramp() = ByteArray(360) { k -> minOf(255, k * 4).toByte() }

    @Test fun hrpIsReadAtBearingMinusPeakBearing() {
        val hrp = HrpPattern(ramp(), peakBearing = 90)
        val att = hrpAttenuation(hrp)
        assertThat(att[90]).isEqualTo(0f) // the peak is at its peak bearing
        assertThat(att[91]).isEqualTo(1f) // byte 1 = 4 steps of 0.25 dB
        assertThat(att[89]).isEqualTo(63.75f) // byte 359 (clamped to 255)
        assertThat(att[0]).isEqualTo(63.75f) // wraps: (0 - 90) mod 360 = 270
        assertThat(att[100]).isEqualTo(10f)
    }

    @Test fun parametricPatternIs3gppWithDefaults() {
        val att = parametricAttenuation(azimuthDeg = 120.0, hBeamwidthDeg = null, frontToBackDb = null)
        assertThat(att[120]).isEqualTo(0f)
        // 3 dB point at half the beamwidth: 12 * (32.5/65)^2 = 3 dB
        assertThat(att[152]).isWithin(0.15f).of(3f)
        assertThat(att[120 + 180]).isEqualTo(DEFAULT_FRONT_TO_BACK_DB.toFloat())
        assertThat(parametricAttenuation(null, 65.0, 25.0).all { it == 0f }).isTrue()
        // custom beamwidth / front-to-back
        val narrow = parametricAttenuation(0.0, 30.0, 20.0)
        assertThat(narrow[15]).isWithin(0.01f).of(3f)
        assertThat(narrow[180]).isEqualTo(20f)
        assertThat(narrow[350]).isEqualTo(narrow[10]) // symmetric across the wrap
    }

    @Test fun polarPointsPutThePeakAtTheRimOnTheRightBearing() {
        val att = FloatArray(360) { 30f }
        att[90] = 0f
        val pts = polarPoints(att, stepDeg = 1)
        assertThat(pts).hasSize(360)
        val east = pts[90]
        assertThat(east.r).isEqualTo(1f)
        assertThat(east.x).isWithin(1e-5f).of(1f) // 90 deg = east = +x
        assertThat(east.y).isWithin(1e-5f).of(0f)
        assertThat(pts[0].r).isEqualTo(0f)
        val half = polarPoints(FloatArray(360) { 15f }, stepDeg = 90)
        assertThat(half.map { it.r }).containsExactly(0.5f, 0.5f, 0.5f, 0.5f)
        assertThat(half[0].y).isWithin(1e-5f).of(0.5f) // north up
    }

    private fun carrier(sdd: Long) = Carrier(sdd, 3_410_000_000, 100_000_000, 61.8, 4.0, BandInfo("n78", Tech.NR), "L1", "R1")

    private fun sector(id: Int, net: Network, az: Double?, sdd: Long, ant: Antenna? = null) =
        Sector(id, 1, net, ant, az, 25.0, listOf(carrier(sdd)))

    private val detail = SiteDetail(
        Site(1, -35.3, 149.1, "Test site", "ACT", "2600", null),
        listOf(
            sector(1, Network.TELSTRA, 0.0, sdd = 11),
            sector(2, Network.TELSTRA, 120.0, sdd = 22, ant = Antenna(1, 17.0, 30.0, 30.0, null, null)),
            sector(3, Network.OPTUS, null, sdd = 33),
        ),
    )

    @Test fun plotsUseAcmaPatternsWhenInstalledAndTypicalOnesOtherwise() {
        val access = PatternAccess { sdd -> if (sdd == 11L) HrpPattern(ramp(), 0) else null }
        val plots = sectorPlots(detail, access)
        assertThat(plots.map { it.sectorId }).containsExactly(1, 2, 3).inOrder()
        val byId = plots.associateBy { it.sectorId }
        assertThat(byId.getValue(1).source).isEqualTo(PatternSource.ACMA)
        assertThat(byId.getValue(1).attenuationDb[10]).isEqualTo(10f)
        assertThat(byId.getValue(2).source).isEqualTo(PatternSource.TYPICAL)
        assertThat(byId.getValue(2).attenuationDb[120 + 180]).isEqualTo(30f) // the antenna's own front-to-back
        assertThat(byId.getValue(3).attenuationDb.all { it == 0f }).isTrue() // omni
        assertThat(siteBeams(-35.3, 149.1, plots)!!.beams).hasSize(3)
        assertThat(siteBeams(-35.3, 149.1, emptyList())).isNull()
    }

    @Test fun sheetShowsThePatternSectionAndTheCoverageButton() {
        var shown: Network? = null
        val plots = sectorPlots(detail, PatternAccess.None)
        compose.setContent {
            SignalAtlasTheme {
                SiteSheetContent(detail, onClose = {}, plots = plots, onShowCoverage = { shown = it })
            }
        }
        compose.onNodeWithText("Radiation pattern").assertIsDisplayed()
        compose.onNodeWithTag("pattern-2").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("show-coverage-TELSTRA"))
        compose.onNodeWithTag("show-coverage-TELSTRA").performClick()
        assertThat(shown).isEqualTo(Network.TELSTRA)
    }
}
