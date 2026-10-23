package au.signalatlas.core.calibration

import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import au.signalatlas.engine.AreaClass

/** A location fix: wall-clock [timeMs], horizontal [accuracyM] (68%). */
data class Fix(val lat: Double, val lon: Double, val accuracyM: Double, val timeMs: Long)

/** Rule 2. Only [LIKELY] and [CONFIRMED] matches become samples (rule 3). */
enum class Confidence { UNCERTAIN, LIKELY, CONFIRMED }

/** One candidate carrier for the serving cell (rule 1) with what the score needs (rule 2). */
data class Candidate(
    val sectorId: Int,
    val sddId: Long,
    /** The sector's stable [au.signalatlas.core.model.SectorIdentity]. */
    val ident: String,
    /** The carrier's network (the SIM's, or the Optus/TPG joint venture's). */
    val network: Network,
    val siteLat: Double,
    val siteLon: Double,
    val freqMhz: Double,
    /** Uncalibrated predicted RSRP at the fix (terrain when available, else quick). */
    val predictedDbm: Double,
    val rMaxKm: Double,
)

/** The serving cell matched to a sector. [score] is the winner's rule-2 score; [distanceM] from its site to the fix. */
data class MatchResult(
    val candidate: Candidate,
    val confidence: Confidence,
    val score: Double,
    val distanceM: Double,
    val area: AreaClass,
    val candidates: Int,
)

/** One stored measurement (rule 3 fields). Nullable fields were unavailable from the modem. */
data class Sample(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    /** The SIM's network. */
    val network: Network,
    val tech: Tech,
    val channel: Int?,
    val pci: Int?,
    val cellId: Long?,
    val taM: Double?,
    val rsrp: Int,
    val rsrq: Int?,
    val sinr: Int?,
    val sddId: Long,
    val sectorId: Int,
    /** The matched sector's stable identity (estimates are keyed by it, not by [sectorId]). */
    val ident: String,
    /** The matched carrier's network (selects the residual grid). */
    val carrierNetwork: Network,
    val siteLat: Double,
    val siteLon: Double,
    /** Uncalibrated predicted RSRP at the fix. */
    val predictedDbm: Double,
    val distanceM: Double,
    val area: AreaClass,
    val confidence: Confidence,
) {
    /** r = M - P (rule 4). */
    val residualDb: Double get() = rsrp - predictedDbm
}
