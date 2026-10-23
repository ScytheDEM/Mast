package au.signalatlas.core.calibration

import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import au.signalatlas.core.radio.ObservedCell
import au.signalatlas.engine.AreaClass

/** A fix at (-35.28, 149.13) unless moved; accuracy 10 m. */
fun fix(lat: Double = -35.28, lon: Double = 149.13, ms: Long = 1_000_000L, acc: Double = 10.0) = Fix(lat, lon, acc, ms)

fun cell(
    rsrp: Int? = -95, freq: Double? = 758.0, pci: Int? = 101, channel: Int? = 9410, cellId: Long? = null, ta: Double? = null,
    tech: Tech = Tech.LTE,
) = ObservedCell(tech, channel, freq, "B28", pci, cellId, 1, rsrp, -10, 12, ta, true)

/** Candidate sector [id] ~[km] km north of the default fix, predicted at [pred] dBm. */
fun candidate(id: Int, pred: Double, km: Double = 1.0, rMaxKm: Double = 30.0, network: Network = Network.TELSTRA) =
    Candidate(id, 1000L + id, network, -35.28 + km / 111.195, 149.13, 758.0, pred, rMaxKm)

class FakeCandidates(var list: List<Candidate> = emptyList(), var area: AreaClass = AreaClass.SUBURBAN) : CandidateSource {
    var calls = 0
    override fun candidates(network: Network, tech: Tech, freqMhz: Double, lat: Double, lon: Double): CandidateSet {
        calls++
        return CandidateSet(list, area)
    }
}

class MemoryCellMap : CellMapStore {
    val map = HashMap<String, CellMapping>()
    override fun cellMapping(key: String) = map[key]
    override fun putCellMapping(m: CellMapping) { map[m.key] = m }
}

fun sample(
    sector: Int = 7, rsrp: Int = -90, predicted: Double = -96.0, distanceM: Double = 1500.0, lat: Double = -35.28,
    lon: Double = 149.13, ms: Long = 1_000_000L, network: Network = Network.TELSTRA,
) = Sample(
    ms, lat, lon, 8.0, network, Tech.LTE, 9410, 101, 123456L, 300.0, rsrp, -11, 9, 5000L + sector, sector, network,
    -35.27, 149.13, predicted, distanceM, AreaClass.URBAN, Confidence.LIKELY,
)
