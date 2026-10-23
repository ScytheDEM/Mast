package au.signalatlas.core.calibration

import au.signalatlas.core.model.Network
import au.signalatlas.core.radio.Permission
import au.signalatlas.core.radio.RadioState
import au.signalatlas.core.radio.SimInfo
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SampleLoggerTest {
    private val cands = FakeCandidates(listOf(candidate(7, -95.0), candidate(8, -110.0)))
    private val stored = ArrayList<Sample>()
    private var now = 50_000L
    private val sim = SimInfo(1, Network.TELSTRA, "Telstra", "505-01", false)
    private val good = RadioState(Permission.GRANTED, sim, cell(rsrp = -93), emptyList(), emptyList(), false, false, 49_000L)
    private val radio = MutableStateFlow(good)
    private val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 16)

    private fun logger(scope: kotlinx.coroutines.CoroutineScope, dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        SampleLogger(radio, fixes, CellMatcher(cands, MemoryCellMap()), { stored += it }, scope, { now }, dispatcher)

    @Test fun storesLikelyMatchesWithAllFields() = runTest {
        val l = logger(backgroundScope, StandardTestDispatcher(testScheduler))
        val s = l.process(fix(), good)!!
        assertThat(stored).containsExactly(s)
        assertThat(s.sectorId).isEqualTo(7)
        assertThat(s.sddId).isEqualTo(1007L)
        assertThat(s.rsrp).isEqualTo(-93)
        assertThat(s.predictedDbm).isEqualTo(-95.0)
        assertThat(s.residualDb).isEqualTo(2.0)
        assertThat(s.network).isEqualTo(Network.TELSTRA)
        assertThat(s.carrierNetwork).isEqualTo(Network.TELSTRA)
        assertThat(s.pci).isEqualTo(101)
        assertThat(s.channel).isEqualTo(9410)
        assertThat(s.distanceM).isWithin(2.0).of(1000.0)
        assertThat(s.confidence).isEqualTo(Confidence.LIKELY)
    }

    @Test fun gates() = runTest {
        val l = logger(backgroundScope, StandardTestDispatcher(testScheduler))
        assertThat(l.process(fix(), good.copy(permission = Permission.MISSING_LOCATION))).isNull()
        assertThat(l.process(fix(), good.copy(airplane = true))).isNull()
        assertThat(l.process(fix(), good.copy(sim = null))).isNull()
        assertThat(l.process(fix(), good.copy(sim = sim.copy(roaming = true)))).isNull()
        assertThat(l.process(fix(), good.copy(sim = sim.copy(network = null)))).isNull()
        assertThat(l.process(fix(), good.copy(serving = null))).isNull()
        assertThat(l.process(fix(), good.copy(serving = cell().copy(registered = false)))).isNull()
        assertThat(l.process(fix(), good.copy(serving = cell(rsrp = null)))).isNull()
        assertThat(l.process(fix(), good.copy(updatedAtElapsedMs = now - 10_001))).isNull()  // stale radio
        assertThat(l.process(fix(acc = 51.0), good)).isNull()
        cands.list = listOf(candidate(7, -95.0), candidate(8, -96.0))  // Uncertain
        assertThat(l.process(fix(), good)).isNull()
        assertThat(stored).isEmpty()
        assertThat(cands.calls).isEqualTo(1)  // only the last one got as far as matching
    }

    @Test fun throttleNeedsFiftyMetresAndFiveSeconds() = runTest {
        val l = logger(backgroundScope, StandardTestDispatcher(testScheduler))
        assertThat(l.process(fix(ms = 0), good)).isNotNull()
        assertThat(l.process(fix(lat = -35.281, ms = 4_999), good)).isNull()        // 111 m but 5 s not reached
        assertThat(l.process(fix(lat = -35.2803, ms = 60_000), good)).isNull()      // 33 m
        assertThat(l.process(fix(lat = -35.281, ms = 5_000), good)).isNotNull()
        assertThat(stored).hasSize(2)
    }

    @Test fun logsOnlyWhileStarted() = runTest {
        val l = logger(backgroundScope, StandardTestDispatcher(testScheduler))
        fixes.tryEmit(fix(ms = 0))
        runCurrent()
        assertThat(stored).isEmpty()
        l.start()
        l.start()
        runCurrent()
        assertThat(l.isRunning).isTrue()
        fixes.tryEmit(fix(ms = 10_000))
        runCurrent()
        assertThat(stored).hasSize(1)
        l.stop()
        runCurrent()
        assertThat(l.isRunning).isFalse()
        fixes.tryEmit(fix(lat = -35.29, ms = 20_000))
        runCurrent()
        assertThat(stored).hasSize(1)
        l.start()
        runCurrent()
        fixes.tryEmit(fix(lat = -35.29, ms = 30_000))
        runCurrent()
        assertThat(stored).hasSize(2)
    }
}
