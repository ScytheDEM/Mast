package au.signalatlas.app.connected

import au.signalatlas.core.calibration.Confidence
import au.signalatlas.core.calibration.Fix
import au.signalatlas.core.calibration.MatchResult
import au.signalatlas.core.map.ConnectedHighlight
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.SectorIdentity
import au.signalatlas.core.model.SiteKind
import au.signalatlas.core.model.Tech
import au.signalatlas.core.radio.ObservedCell
import au.signalatlas.core.radio.Permission
import au.signalatlas.core.radio.RadioState
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the info bar says; everything but [CONNECTED] is an explanation of why there is no tower to show. */
enum class ConnectedStatus { NEEDS_PHONE, NEEDS_LOCATION, AIRPLANE, NO_SIM, ROAMING, UNTRACKED, SEARCHING, CONNECTED }

/** The matched tower: its ACMA site (opens the site sheet), distance and compass bearing from the phone. */
data class TowerRef(val siteId: Int?, val distanceM: Double, val bearingDeg: Double, val sectorIdent: String? = null) {
    val compass: String get() = COMPASS[((bearingDeg + 22.5) / 45.0).toInt() % 8]

    private companion object {
        val COMPASS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    }
}

data class ConnectedUi(
    val status: ConnectedStatus,
    /** The SIM's carrier name ("Boost"), or null to use [network]'s name. */
    val carrier: String? = null,
    val network: Network? = null,
    val fiveG: Boolean = false,
    /** "B28" or "B28 + n78" (NSA secondary). */
    val bands: String? = null,
    val rsrpDbm: Int? = null,
    val tower: TowerRef? = null,
    val confidence: Confidence? = null,
    val speedMbps: Int? = null,
    val highlight: ConnectedHighlight? = null,
    /** The SIM this describes (dual SIM); null in tests. */
    val subId: Int? = null,
    /** True for the SIM that carries mobile data (the primary one). */
    val isData: Boolean = true,
    /** Serving band plus the NSA secondary, e.g. ["B28", "n78"]. */
    val bandList: List<String> = emptyList(),
    /** The live cell lists for the "Cellular details" sheet; null without a serving cell. */
    val cells: CellDetails? = null,
    /** What the tower prediction says the signal is at the phone's position (matched sector, uncalibrated), dBm. */
    val predictedDbm: Int? = null,
    /** The other active SIMs of a dual-SIM phone, each as its own compact row (set on the primary only). */
    val otherSims: List<ConnectedUi> = emptyList(),
    /** The matched serving site is a DAS site (in-building / tunnel): the phone card shows a DAS badge. */
    val dasKind: SiteKind? = null,
) {
    /** The primary SIM first, then the others. */
    val allSims: List<ConnectedUi> get() = listOf(this) + otherSims

    companion object {
        /** The map is not visible: nothing is being read. */
        val IDLE = ConnectedUi(ConnectedStatus.SEARCHING)
    }
}

/** What the phone sees around it, as shown on the details sheet: neighbours sorted strongest first. */
data class CellDetails(
    val serving: ObservedCell,
    val nrSecondaries: List<ObservedCell>,
    val neighbours: List<ObservedCell>,
)

/** The info bar's model from the radio state and the (optional) tower match. Pure. */
fun connectedUi(state: RadioState, match: MatchResult?, fix: Fix?): ConnectedUi {
    when (state.permission) {
        Permission.MISSING_PHONE -> return ConnectedUi(ConnectedStatus.NEEDS_PHONE)
        Permission.MISSING_LOCATION -> return ConnectedUi(ConnectedStatus.NEEDS_LOCATION)
        Permission.GRANTED -> Unit
    }
    if (state.airplane) return ConnectedUi(ConnectedStatus.AIRPLANE)
    val sim = state.sim ?: return ConnectedUi(ConnectedStatus.NO_SIM)
    val base = ConnectedUi(
        ConnectedStatus.SEARCHING, carrier = sim.carrierName?.takeIf { it.isNotBlank() }, network = sim.network, subId = sim.subId,
    )
    if (sim.roaming) return base.copy(status = ConnectedStatus.ROAMING)
    val serving = state.serving ?: return base
    val withCell = base.copy(
        status = if (sim.network == null) ConnectedStatus.UNTRACKED else ConnectedStatus.CONNECTED,
        fiveG = state.displayFiveG || serving.tech == Tech.NR,
        bands = bandsOf(serving, state.nrSecondaries),
        rsrpDbm = serving.rsrp,
        bandList = listOfNotNull(serving.band, state.nrSecondaries.firstOrNull()?.band?.takeIf { serving.tech == Tech.LTE }).distinct(),
        cells = CellDetails(
            serving, state.nrSecondaries,
            state.neighbours.sortedWith(compareByDescending<ObservedCell> { it.rsrp != null }.thenByDescending { it.rsrp ?: Int.MIN_VALUE }),
        ),
    )
    if (sim.network == null || match == null || fix == null) return withCell
    val c = match.candidate
    val tower = TowerRef(
        SectorIdentity.siteIdOf(c.ident), match.distanceM,
        bearingDeg(fix.lat, fix.lon, c.siteLat, c.siteLon), c.ident,
    )
    val sure = match.confidence != Confidence.UNCERTAIN
    return withCell.copy(
        tower = tower,
        predictedDbm = c.predictedDbm.takeIf { it.isFinite() }?.roundToInt(),
        confidence = match.confidence,
        speedMbps = if (sure) speedMbps(serving.sinr, serving.tech, c.bandwidthMhz) else null,
        highlight = if (sure) {
            ConnectedHighlight(c.siteLat to c.siteLon, fix.lat to fix.lon, match.others.map { it.siteLat to it.siteLon })
        } else {
            null
        },
    )
}

private fun bandsOf(serving: ObservedCell, nr: List<ObservedCell>): String? =
    listOfNotNull(serving.band, nr.firstOrNull()?.band?.takeIf { serving.tech == Tech.LTE }).distinct().joinToString(" + ").ifEmpty { null }

/**
 * `min(0.75 log2(1 + SINR), 5.5 LTE | 7.4 NR) * BW * 0.75` in Mbps for SINR in dB and the carrier's bandwidth in MHz;
 * null when either is unknown.
 */
fun speedMbps(sinrDb: Int?, tech: Tech, bandwidthMhz: Double): Int? {
    if (sinrDb == null || !(bandwidthMhz > 0.0)) return null
    val sinr = 10.0.pow(sinrDb / 10.0)
    val bitsPerHz = min(0.75 * ln(1.0 + sinr) / ln(2.0), if (tech == Tech.NR) 7.4 else 5.5)
    return (bitsPerHz * bandwidthMhz * 0.75).roundToInt().takeIf { it > 0 }
}

/** Initial compass bearing from (lat1, lon1) to (lat2, lon2), degrees clockwise from north in [0, 360). */
fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lon2 - lon1)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}
