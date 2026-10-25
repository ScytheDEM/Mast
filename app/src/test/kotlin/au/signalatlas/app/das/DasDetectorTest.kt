package au.signalatlas.app.das

import au.signalatlas.app.connected.ConnectedUi
import au.signalatlas.app.data.TowerSnapshot
import au.signalatlas.core.data.DataMeta
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.SiteDetail
import au.signalatlas.core.model.SiteKind
import au.signalatlas.core.model.SiteSummary
import au.signalatlas.core.model.Tech
import au.signalatlas.core.model.TowerFilter
import au.signalatlas.core.radio.RadioState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

private class SitesSnapshot(sites: List<SiteSummary>) : TowerSnapshot {
    private val byId = sites.associateBy { it.id }
    override val meta: DataMeta get() = error("unused")
    override val allBands: List<String> = emptyList()
    override fun allSites(f: TowerFilter) = byId.values.toList()
    override fun nearest(lat: Double, lon: Double, f: TowerFilter, limit: Int) = emptyList<Pair<SiteSummary, Double>>()
    override fun search(query: String, limit: Int) = emptyList<SiteSummary>()
    override fun siteDetail(siteId: Int): SiteDetail? = null
    override fun site(siteId: Int) = byId[siteId]
}

class DasDetectorTest {
    private val mall = SiteSummary(
        1, -33.80, 151.00, "Westfield Test", setOf(Network.TELSTRA, Network.OPTUS), setOf(Tech.LTE), setOf("B28"), SiteKind.DAS_BUILDING,
    )
    private val tunnelSite = SiteSummary(
        2, -33.8700, 151.2000, "Harbour Tunnel IBC", setOf(Network.TPG), setOf(Tech.LTE), setOf("B3"), SiteKind.DAS_TUNNEL,
    )
    private val macro = SiteSummary(3, -33.9, 151.1, "Hill Rd", setOf(Network.TELSTRA), setOf(Tech.LTE), setOf("B28"))
    private var now = 1_000_000L
    private val detector = DasDetector({ SitesSnapshot(listOf(mall, tunnelSite, macro)) }, { now })

    @Test fun servingDasSiteIsAnnouncedWithNameAndNetworks() {
        val e = detector.evaluate(1, -80, false, null)!!
        assertThat(e.text).isEqualTo("DAS detected: Westfield Test (Telstra, Optus)")
        assertThat(e.key).isEqualTo("site:1")
        assertThat(e.kind).isEqualTo(SiteKind.DAS_BUILDING)
    }

    @Test fun macroOrUnknownServingSitesAreNotDas() {
        assertThat(detector.evaluate(3, -80, false, null)).isNull()
        assertThat(detector.evaluate(99, -80, false, null)).isNull()
        assertThat(detector.evaluate(null, -80, false, null)).isNull()
    }

    @Test fun onceOverPerSiteForThirtyMinutes() {
        assertThat(detector.evaluate(1, -80, false, null)).isNotNull()
        now += 29 * 60_000L
        assertThat(detector.evaluate(1, -80, false, null)).isNull()
        now += 2 * 60_000L
        assertThat(detector.evaluate(1, -80, false, null)).isNotNull()
    }

    @Test fun tunnelWithGoodSignalIsAnnouncedWithoutASiteWhenNoneMatches() {
        val e = detector.evaluate(3, -100, true, -33.87 to 151.2)!!
        assertThat(e.text).isEqualTo("DAS detected: tunnel coverage")
        assertThat(e.key).isEqualTo("tunnel")
        assertThat(detector.evaluate(null, -100, true, -33.87 to 151.2)).isNull() // same key, suppressed
    }

    @Test fun tunnelNamesTheServingTunnelSiteWithin1km() {
        val e = detector.evaluate(2, -95, true, -33.8750 to 151.2)!! // ~550 m away
        assertThat(e.text).isEqualTo("DAS detected: tunnel coverage · Harbour Tunnel IBC")
        assertThat(e.key).isEqualTo("site:2")
        // Too far: no name.
        val far = DasDetector({ SitesSnapshot(listOf(tunnelSite)) }, { now }).evaluate(2, -95, true, -33.90 to 151.2)!!
        assertThat(far.text).isEqualTo("DAS detected: tunnel coverage")
    }

    @Test fun tunnelNeedsRsrpAtLeastMinus105() {
        assertThat(detector.evaluate(null, -106, true, null)).isNull()
        assertThat(detector.evaluate(null, null, true, null)).isNull()
        assertThat(detector.evaluate(null, -105, true, null)).isNotNull()
    }

    @Test fun notInATunnelMeansNoTunnelAnnouncement() {
        assertThat(detector.evaluate(null, -70, false, null)).isNull()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DasMonitorTest {
    private val site = SiteSummary(1, -33.8, 151.0, "Mall IBC", setOf(Network.OPTUS), setOf(Tech.LTE), setOf("B3"), SiteKind.DAS_BUILDING)

    private fun TestScope.monitor(
        ui: MutableStateFlow<ConnectedUi>, tunnel: MutableStateFlow<Boolean>, notified: MutableList<DasEvent>,
    ) = DasMonitor(
        backgroundScope, ui, tunnel, MutableStateFlow(RadioState.EMPTY), MutableStateFlow(null),
        DasDetector({ SitesSnapshot(listOf(site)) }),
        { notified += it },
    )

    @Test fun matchedDasTowerPublishesTheBannerAndANotificationOnce() = runTest(StandardTestDispatcher()) {
        val ui = MutableStateFlow(ConnectedUi.IDLE)
        val notified = ArrayList<DasEvent>()
        val m = monitor(ui, MutableStateFlow(false), notified)
        runCurrent()
        assertThat(m.banner.value).isNull()
        ui.value = ConnectedUi.IDLE.copy(
            rsrpDbm = -85, tower = au.signalatlas.app.connected.TowerRef(1, 200.0, 10.0, "1:0"),
        )
        runCurrent()
        assertThat(m.banner.value!!.text).isEqualTo("DAS detected: Mall IBC (Optus)")
        assertThat(notified).hasSize(1)
        ui.value = ui.value.copy(rsrpDbm = -84)
        runCurrent()
        assertThat(notified).hasSize(1) // within 30 minutes
        m.dismiss()
        assertThat(m.banner.value).isNull()
    }

    @Test fun tunnelFlagWithGoodRsrpFromTheRadioAnnouncesTunnelCoverage() = runTest(StandardTestDispatcher()) {
        val tunnel = MutableStateFlow(false)
        val radio = MutableStateFlow(
            RadioState.EMPTY.copy(
                serving = au.signalatlas.core.radio.ObservedCell(Tech.LTE, 100, 800.0, "B28", 1, 2, 3, -95, null, null, null, true),
            ),
        )
        val notified = ArrayList<DasEvent>()
        val m = DasMonitor(
            backgroundScope, MutableStateFlow(ConnectedUi.IDLE), tunnel, radio, MutableStateFlow(null),
            DasDetector({ SitesSnapshot(listOf(site)) }), { notified += it },
        )
        runCurrent()
        assertThat(notified).isEmpty()
        tunnel.value = true
        runCurrent()
        assertThat(m.banner.value!!.text).isEqualTo("DAS detected: tunnel coverage")
    }
}
