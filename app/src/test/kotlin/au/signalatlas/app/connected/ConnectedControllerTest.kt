package au.signalatlas.app.connected

import au.signalatlas.app.data.CalibrationSettings
import au.signalatlas.core.calibration.CandidateSet
import au.signalatlas.core.calibration.CandidateSource
import au.signalatlas.core.calibration.CellMapStore
import au.signalatlas.core.calibration.CellMapping
import au.signalatlas.core.calibration.CellMatcher
import au.signalatlas.core.calibration.Fix
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import au.signalatlas.core.radio.RadioState
import au.signalatlas.engine.AreaClass
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

private class FakeSettings(on: Boolean = true) : CalibrationSettings {
    override val enabled = MutableStateFlow(on)
    override fun setEnabled(on: Boolean) { enabled.value = on }
    override fun offerRationaleOnce() = false
}

private class RecordingMap : CellMapStore {
    val puts = ArrayList<CellMapping>()
    override fun cellMapping(key: String): CellMapping? = null
    override fun putCellMapping(m: CellMapping) { puts += m }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedControllerTest {
    private val radioState = MutableStateFlow(radio())
    private val fixes = MutableStateFlow<Fix?>(null)
    private val settings = FakeSettings()
    private val events = ArrayList<String>()
    private val noTowers = CandidateSource { _: Network, _: Tech, _: Double, _: Double, _: Double -> CandidateSet(emptyList(), AreaClass.RURAL) }

    private fun TestScope.controller() = ConnectedController(
        radioState, fixes, CellMatcher(noTowers, RecordingMap()), settings, backgroundScope,
        startRadio = { events += "radio+" }, stopRadio = { events += "radio-" },
        startLogger = { events += "log+" }, stopLogger = { events += "log-" },
        compute = StandardTestDispatcher(testScheduler),
    )

    @Test fun nothingRunsUntilTheMapScreenStartsAndEverythingStopsWithIt() = runTest {
        val c = controller()
        runCurrent()
        assertThat(events).doesNotContain("radio+")
        assertThat(events).doesNotContain("log+")
        c.start()
        runCurrent()
        assertThat(events).containsAtLeast("radio+", "log+")
        events.clear()
        c.stop()
        assertThat(events).containsExactly("radio-", "log-")
        // Idempotent: a second stop (or start/stop churn) does not double-stop.
        events.clear()
        c.stop()
        assertThat(events).isEmpty()
    }

    @Test fun theLoggerFollowsTheSettingOnlyWhileTheMapIsStarted() = runTest {
        val c = controller()
        c.start()
        runCurrent()
        events.clear()
        settings.setEnabled(false)
        runCurrent()
        assertThat(events).containsExactly("log-")
        events.clear()
        settings.setEnabled(true)
        runCurrent()
        assertThat(events).containsExactly("log+")
        c.stop()
        events.clear()
        settings.setEnabled(false)
        settings.setEnabled(true)
        runCurrent()
        assertThat(events).doesNotContain("log+")
    }

    @Test fun theInfoBarFollowsTheRadioWhileStartedAndIsReadOnlyForTheCellMap() = runTest {
        val c = controller()
        c.start()
        runCurrent()
        assertThat(c.ui.value.status).isEqualTo(ConnectedStatus.CONNECTED)
        assertThat(c.ui.value.rsrpDbm).isEqualTo(-94)
        radioState.value = radio(airplane = true)
        runCurrent()
        assertThat(c.ui.value.status).isEqualTo(ConnectedStatus.AIRPLANE)
        c.stop()
        radioState.value = radio(sim = null)
        runCurrent()
        assertThat(c.ui.value.status).isEqualTo(ConnectedStatus.AIRPLANE)  // no longer following
    }

    @Test fun grantingPermissionRestartsTheRadioOnlyWhileStarted() = runTest {
        val c = controller()
        c.onPermissionsChanged()
        assertThat(events).doesNotContain("radio+")
        c.start()
        events.clear()
        c.onPermissionsChanged()
        assertThat(events).containsExactly("radio-", "radio+").inOrder()
    }

    @Test fun readOnlyCellMapNeverWrites() {
        val inner = RecordingMap()
        val ro = ReadOnlyCellMap(inner)
        ro.putCellMapping(CellMapping("k", Network.TELSTRA, "i", 1L, 1, false))
        assertThat(inner.puts).isEmpty()
    }
}
