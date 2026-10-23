package au.signalatlas.core.calibration

import au.signalatlas.core.model.Network
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CellMatcherTest {
    private val cands = FakeCandidates()
    private val map = MemoryCellMap()
    private val matcher = CellMatcher(cands, map)

    @Test fun nothingToMatch() {
        cands.list = listOf(candidate(1, -95.0))
        assertThat(matcher.match(Network.TELSTRA, cell(), fix(acc = 50.1))).isNull()   // rule 1: accuracy
        assertThat(matcher.match(Network.TELSTRA, cell(freq = null), fix())).isNull()
        assertThat(matcher.match(Network.TELSTRA, cell(rsrp = null), fix())).isNull()
        assertThat(cands.calls).isEqualTo(0)
        cands.list = emptyList()
        assertThat(matcher.match(Network.TELSTRA, cell(), fix())).isNull()
        assertThat(matcher.match(Network.TELSTRA, cell(), fix(acc = 50.0))).isNull()     // 50 m is allowed (no candidates)
        assertThat(cands.calls).isEqualTo(2)
    }

    @Test fun bestScoreWinsWithLikelyOrUncertain() {
        cands.list = listOf(candidate(1, -110.0), candidate(2, -96.0), candidate(3, -101.0))
        val m = matcher.match(Network.TELSTRA, cell(rsrp = -95), fix())!!
        assertThat(m.candidate.sectorId).isEqualTo(2)
        assertThat(m.score).isWithin(1e-9).of(1.0)
        assertThat(m.confidence).isEqualTo(Confidence.LIKELY)  // 6 vs 1
        assertThat(m.candidates).isEqualTo(3)
        assertThat(m.distanceM).isWithin(2.0).of(1000.0)
        cands.list = listOf(candidate(1, -97.0), candidate(2, -96.0))
        assertThat(matcher.match(Network.TELSTRA, cell(rsrp = -95), fix())!!.confidence).isEqualTo(Confidence.UNCERTAIN)
        cands.list = listOf(candidate(9, -60.0))  // alone: Likely however far off
        assertThat(matcher.match(Network.TELSTRA, cell(rsrp = -95), fix())!!.confidence).isEqualTo(Confidence.LIKELY)
    }

    @Test fun timingAdvanceAndRangeDecide() {
        // Equal predictions; TA 3 km picks the 3 km site (|d - TA| / 150 * 3 dB).
        cands.list = listOf(candidate(1, -95.0, km = 1.0), candidate(2, -95.0, km = 3.0))
        assertThat(matcher.match(Network.TELSTRA, cell(ta = 3000.0), fix())!!.candidate.sectorId).isEqualTo(2)
        // Beyond R_max costs 100 points.
        cands.list = listOf(candidate(1, -95.0, km = 5.0, rMaxKm = 4.0), candidate(2, -105.0, km = 2.0))
        val m = matcher.match(Network.TELSTRA, cell(rsrp = -95), fix())!!
        assertThat(m.candidate.sectorId).isEqualTo(2)
        assertThat(m.confidence).isEqualTo(Confidence.LIKELY)
    }

    @Test fun fiveLikelyMatchesConfirmAndTheMappingIsThenUsedDirectly() {
        cands.list = listOf(candidate(1, -95.0), candidate(2, -110.0))
        repeat(4) { assertThat(matcher.match(Network.TELSTRA, cell(), fix())!!.confidence).isEqualTo(Confidence.LIKELY) }
        val key = CellMatcher.cellKey(Network.TELSTRA, cell())!!
        assertThat(map.map[key]!!.likelyCount).isEqualTo(4)
        assertThat(map.map[key]!!.confirmed).isFalse()
        assertThat(matcher.match(Network.TELSTRA, cell(), fix())!!.confidence).isEqualTo(Confidence.CONFIRMED)
        assertThat(map.map[key]).isEqualTo(CellMapping(key, Network.TELSTRA, "s1", 1001L, 5, true))
        // Confirmed: sector 1 is used even when the prediction now favours sector 2 by a wide margin.
        cands.list = listOf(candidate(1, -120.0), candidate(2, -95.0))
        val m = matcher.match(Network.TELSTRA, cell(), fix())!!
        assertThat(m.candidate.sectorId).isEqualTo(1)
        assertThat(m.confidence).isEqualTo(Confidence.CONFIRMED)
        // Unless that sector is no longer a candidate: scoring takes over and a Likely match restarts the count.
        cands.list = listOf(candidate(2, -95.0), candidate(3, -120.0))
        assertThat(matcher.match(Network.TELSTRA, cell(), fix())!!.confidence).isEqualTo(Confidence.LIKELY)
        assertThat(map.map[key]).isEqualTo(CellMapping(key, Network.TELSTRA, "s2", 1002L, 1, false))
    }

    @Test fun inconsistentMatchesRestartTheCountAndUncertainOnesAreNotCounted() {
        cands.list = listOf(candidate(1, -95.0), candidate(2, -110.0))
        repeat(3) { matcher.match(Network.TELSTRA, cell(), fix()) }
        cands.list = listOf(candidate(1, -110.0), candidate(2, -95.0))
        matcher.match(Network.TELSTRA, cell(), fix())
        val key = CellMatcher.cellKey(Network.TELSTRA, cell())!!
        assertThat(map.map[key]!!.ident).isEqualTo("s2")
        assertThat(map.map[key]!!.likelyCount).isEqualTo(1)
        cands.list = listOf(candidate(1, -96.0), candidate(2, -95.0))  // Uncertain
        matcher.match(Network.TELSTRA, cell(), fix())
        assertThat(map.map[key]!!.likelyCount).isEqualTo(1)
    }

    @Test fun cellKeysPreferTheCellIdAndNeedSomething() {
        assertThat(CellMatcher.cellKey(Network.OPTUS, cell(cellId = 77L))).isEqualTo("c:1:0:77")
        assertThat(CellMatcher.cellKey(Network.OPTUS, cell())).isEqualTo("p:1:0:101:9410")
        assertThat(CellMatcher.cellKey(Network.OPTUS, cell(pci = null))).isNull()
        assertThat(CellMatcher.cellKey(Network.OPTUS, cell(channel = null))).isNull()
        cands.list = listOf(candidate(1, -95.0))
        assertThat(matcher.match(Network.TELSTRA, cell(pci = null), fix())!!.confidence).isEqualTo(Confidence.LIKELY)
        assertThat(map.map).isEmpty()  // nothing to learn without a key
    }

    @Test fun servingNetworksIncludeTheJointVentureForOptusAndTpg() {
        assertThat(EngineCandidateSource.servingNetworks(Network.TELSTRA)).containsExactly(Network.TELSTRA)
        assertThat(EngineCandidateSource.servingNetworks(Network.OPTUS)).containsExactly(Network.OPTUS, Network.OPTUS_TPG_JV)
        assertThat(EngineCandidateSource.servingNetworks(Network.TPG)).containsExactly(Network.TPG, Network.OPTUS_TPG_JV)
    }
}
