package au.signalatlas.app.ui.map

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
        assertThat(plots.map { it.key }).containsExactly("TELSTRA-0", "TELSTRA-120", "OPTUS-omni").inOrder()
        val byKey = plots.associateBy { it.key }
        assertThat(byKey.getValue("TELSTRA-0").source).isEqualTo(PatternSource.ACMA)
        assertThat(byKey.getValue("TELSTRA-0").attenuationDb[10]).isEqualTo(10f)
        assertThat(byKey.getValue("TELSTRA-120").source).isEqualTo(PatternSource.TYPICAL)
        assertThat(byKey.getValue("TELSTRA-120").attenuationDb[120 + 180]).isEqualTo(30f) // the antenna's own front-to-back
        assertThat(byKey.getValue("OPTUS-omni").attenuationDb.all { it == 0f }).isTrue() // omni
        assertThat(siteBeams(-35.3, 149.1, plots, "Omni")!!.beams.map { it.label })
            .containsExactly("0° n78", "120° n78", "Omni n78").inOrder()
        assertThat(siteBeams(-35.3, 149.1, emptyList(), "Omni")).isNull()
    }

    private fun carrier(sdd: Long, band: String, tech: Tech, mhz: Long) =
        Carrier(sdd, mhz * 1_000_000, 10_000_000, 60.0, 0.0, BandInfo(band, tech), "L", "R")

    @Test fun sectorsPointingTheSameWayAreOneLobeWithTheirBandsIn4gThen5gFrequencyOrder() {
        val d = SiteDetail(
            Site(2, -35.0, 149.0, "Grouped", null, null, null),
            listOf(
                Sector(10, 2, Network.TELSTRA, null, 45.0, 30.0, listOf(carrier(1, "n78", Tech.NR, 3600), carrier(2, "B7", Tech.LTE, 2600))),
                Sector(11, 2, Network.TELSTRA, null, 45.2, 25.0, listOf(carrier(3, "B3", Tech.LTE, 1800), carrier(4, "B1", Tech.LTE, 2100))),
                Sector(12, 2, Network.TELSTRA, null, 165.0, 30.0, listOf(carrier(5, "B28", Tech.LTE, 760))),
                Sector(13, 2, Network.OPTUS, null, 45.0, 30.0, listOf(carrier(6, "B28", Tech.LTE, 770))),
            ),
        )
        val plots = sectorPlots(d, PatternAccess.None)
        assertThat(plots.map { it.key }).containsExactly("TELSTRA-45", "TELSTRA-165", "OPTUS-45").inOrder()
        assertThat(plots[0].sectorIds).containsExactly(10, 11)
        assertThat(plots[0].bands).containsExactly("B3", "B1", "B7", "n78").inOrder()
        assertThat(sectorBandsLine("Telstra", plots[0], "Omni")).isEqualTo("Telstra · 45° · B3 · B1 · B7 · n78")
        assertThat(mapLabel(plots[0], "Omni")).isEqualTo("45° B3 B1 B7 n78")
        val many = SectorPlot("k", Network.TPG, 10.0, listOf("B28", "B3", "B1", "B7", "n78", "n258"), PatternSource.TYPICAL, FloatArray(360), listOf(1))
        assertThat(mapLabel(many, "Omni")).isEqualTo("10° B28 B3 B1 B7 +2")
    }

    @Test fun aFlatOrMisdirectedMeasuredPatternIsIgnoredForADirectionalSector() {
        val flat = HrpPattern(ByteArray(360), peakBearing = 0) // ACMA's one-row "0–360°, one POWER" devices
        val misdirected = HrpPattern(ramp(), peakBearing = 200) // peaks 155° away from the azimuth
        val good = HrpPattern(ramp(), peakBearing = 50)
        assertThat(usableFor(flat, 45.0)).isFalse()
        assertThat(usableFor(flat, null)).isTrue()
        assertThat(usableFor(misdirected, 45.0)).isFalse()
        assertThat(usableFor(good, 45.0)).isTrue()
        val d = SiteDetail(
            Site(3, -35.0, 149.0, "Flat", null, null, null),
            listOf(Sector(1, 3, Network.TELSTRA, null, 45.0, 30.0, listOf(carrier(1, "B3", Tech.LTE, 1800), carrier(2, "B1", Tech.LTE, 2100)))),
        )
        val onlyFlat = sectorPlots(d, PatternAccess { flat }).single()
        assertThat(onlyFlat.source).isEqualTo(PatternSource.TYPICAL) // a typical panel, not a circle
        assertThat(onlyFlat.attenuationDb[225]).isEqualTo(DEFAULT_FRONT_TO_BACK_DB.toFloat())
        val mixed = sectorPlots(d, PatternAccess { if (it == 1L) flat else good }).single()
        assertThat(mixed.source).isEqualTo(PatternSource.ACMA)
        assertThat(mixed.attenuationDb[50]).isEqualTo(0f)
        assertThat(mixed.attenuationDb[60]).isEqualTo(10f) // the good pattern, not the flat one's 0 dB
    }

    @Test fun theEnvelopeTakesTheSmallestAttenuationPerBearing() {
        val a = FloatArray(360) { 10f }.also { it[0] = 0f }
        val b = FloatArray(360) { 20f }.also { it[90] = 1f }
        val e = envelope(listOf(a, b))
        assertThat(e[0]).isEqualTo(0f)
        assertThat(e[90]).isEqualTo(1f)
        assertThat(e[180]).isEqualTo(10f)
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
        compose.onNodeWithTag("pattern-TELSTRA-120").assertExists()
        compose.onNodeWithContentDescription("Telstra · 120° · n78").assertExists()
        compose.onNodeWithText("Pattern: estimated from beamwidth").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("show-coverage-TELSTRA"))
        compose.onNodeWithTag("show-coverage-TELSTRA").performClick()
        assertThat(shown).isEqualTo(Network.TELSTRA)
    }

    @Test fun thePatternSectionCollapsesAndRemembersIt() {
        val plots = sectorPlots(detail, PatternAccess.None)
        var expanded by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            SignalAtlasTheme {
                RadiationPatternSection(plots, expanded = expanded, onExpandedChange = { expanded = it })
            }
        }
        compose.onNodeWithTag("pattern-TELSTRA-120").assertExists()
        compose.onNodeWithTag("pattern-toggle").performClick()
        assertThat(expanded).isFalse()
        compose.onNodeWithTag("pattern-TELSTRA-120").assertDoesNotExist()
        compose.onNodeWithContentDescription("Telstra · 120° · n78").assertIsDisplayed() // the per-sector list stays
    }
}
