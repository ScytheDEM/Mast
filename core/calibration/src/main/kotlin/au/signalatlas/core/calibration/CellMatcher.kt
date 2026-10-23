package au.signalatlas.core.calibration

import au.signalatlas.core.model.Network
import au.signalatlas.core.radio.ObservedCell

/** A learned (network, cell) -> sector mapping (rule 2). */
data class CellMapping(val key: String, val network: Network, val ident: String, val sddId: Long, val likelyCount: Int, val confirmed: Boolean)

/** Persistence of [CellMapping]s ([CalibrationStore] implements it over `calibration.db`). */
interface CellMapStore {
    fun cellMapping(key: String): CellMapping?
    fun putCellMapping(m: CellMapping)
}

/**
 * Rules 1-2: matches the serving cell to an ACMA sector. Candidates come from [candidates]; each is scored
 * `|P - M| + TA term + range penalty` and the lowest wins, "Likely" when it beats the runner-up by >= 3 points.
 * Every Likely match of the same cell key to the same sector counts towards confirmation (a Likely match to another
 * sector restarts the count); after 5 the mapping is Confirmed, persisted, and from then on used directly whenever
 * its sector is among the candidates.
 *
 * The cell key is (network, tech, cell ID) when the modem reports the cell ID, else (network, tech, PCI, channel);
 * without either nothing is learned (matches can still be Likely).
 */
class CellMatcher(private val candidates: CandidateSource, private val cellMap: CellMapStore) {
    /**
     * Null when there is nothing to match: fix accuracy over 50 m, no frequency or RSRP, no tower data or no
     * candidate. Blocking (tower query and point prediction).
     */
    fun match(sim: Network, cell: ObservedCell, fix: Fix): MatchResult? {
        if (!(fix.accuracyM <= CalibrationMath.MAX_FIX_ACCURACY_M)) return null
        val freq = cell.freqMhz ?: return null
        val measured = cell.rsrp ?: return null
        val set = candidates.candidates(sim, cell.tech, freq, fix.lat, fix.lon) ?: return null
        if (set.candidates.isEmpty()) return null
        val scored = set.candidates.map { c ->
            val d = CalibrationMath.distanceM(c.siteLat, c.siteLon, fix.lat, fix.lon)
            Triple(c, CalibrationMath.matchScore(c.predictedDbm, measured.toDouble(), d, cell.taMeters, c.rMaxKm), d)
        }.sortedBy { it.second }
        fun result(t: Triple<Candidate, Double, Double>, conf: Confidence) =
            MatchResult(t.first, conf, t.second, t.third, set.area, set.candidates.size)

        val key = cellKey(sim, cell)
        val mapping = key?.let(cellMap::cellMapping)
        if (mapping != null && mapping.confirmed) {
            scored.firstOrNull { it.first.ident == mapping.ident }?.let { return result(it, Confidence.CONFIRMED) }
        }
        val best = scored.first()
        var conf = CalibrationMath.confidence(scored.map { it.second })!!
        if (conf == Confidence.LIKELY && key != null) {
            val same = mapping?.takeIf { it.ident == best.first.ident }
            val count = (same?.likelyCount ?: 0) + 1
            val confirmed = count >= CalibrationMath.CONFIRM_AFTER
            cellMap.putCellMapping(CellMapping(key, sim, best.first.ident, best.first.sddId, count, confirmed))
            if (confirmed) conf = Confidence.CONFIRMED
        }
        return result(best, conf)
    }

    companion object {
        fun cellKey(sim: Network, cell: ObservedCell): String? = when {
            cell.cellId != null -> "c:${sim.ordinal}:${cell.tech.ordinal}:${cell.cellId}"
            cell.pci != null && cell.channel != null -> "p:${sim.ordinal}:${cell.tech.ordinal}:${cell.pci}:${cell.channel}"
            else -> null
        }
    }
}
