package au.signalatlas.app.ui.calibration

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import au.signalatlas.app.data.CalibrationData
import au.signalatlas.app.data.CalibrationSettings
import au.signalatlas.app.ui.theme.SignalAtlasTheme
import au.signalatlas.core.model.CalibrationSnapshot
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.SectorCorrection
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

private const val IDENT = "41428|0|1234|1200|300"

private class FakeCalibrationData : CalibrationData {
    override val snapshot = MutableStateFlow(
        CalibrationSnapshot(
            3L, mapOf(7 to SectorCorrection(7, Network.TELSTRA.ordinal, -35.27, 149.13, 12, 4.5, 0.0, 1.2, IDENT)), emptyList(),
        ),
    )
    var samples = 12
    val calls = ArrayList<String>()
    override fun sampleCount() = samples
    override fun scatter(ident: String) = listOf(-100.0 to -95.0, -90.0 to -86.0, -110.0 to -104.0)
    override fun resetSector(ident: String) {
        calls += "reset:$ident"
        snapshot.value = CalibrationSnapshot(4L, emptyMap(), emptyList())
    }
    override fun deleteAll() {
        calls += "deleteAll"
        samples = 0
        snapshot.value = CalibrationSnapshot(5L, emptyMap(), emptyList())
    }
    override fun exportCsv(out: Appendable) { out.append("time_ms,rsrp\n1,-90\n") }
}

private class FakeSettings : CalibrationSettings {
    override val enabled = MutableStateFlow(true)
    override fun setEnabled(on: Boolean) { enabled.value = on }
    override fun offerRationaleOnce() = false
}

@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val data = FakeCalibrationData()
    private val settings = FakeSettings()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun sectorsCountsAndToggleReachTheState() = runTest(dispatcher) {
        val vm = CalibrationViewModel(data, settings, io = dispatcher)
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.sampleCount).isEqualTo(12)
        val row = s.sectors.single()
        assertThat(row.siteId).isEqualTo(41428)
        assertThat(row.azimuthDeg).isEqualTo(120.0)
        assertThat(row.applied).isTrue()
        assertThat(row.offsetDb).isEqualTo(4.5)
        assertThat(row.points).hasSize(3)
        vm.setEnabled(false)
        advanceUntilIdle()
        assertThat(vm.state.value.enabled).isFalse()
    }

    @Test fun resetAndDeleteAllClearTheState() = runTest(dispatcher) {
        val vm = CalibrationViewModel(data, settings, io = dispatcher)
        advanceUntilIdle()
        vm.resetSector(IDENT)
        advanceUntilIdle()
        assertThat(data.calls).containsExactly("reset:$IDENT")
        assertThat(vm.state.value.sectors).isEmpty()
        vm.deleteAll()
        advanceUntilIdle()
        assertThat(vm.state.value.sampleCount).isEqualTo(0)
    }

    @Test fun exportWritesTheCsvToTheGivenDirectory() = runTest(dispatcher) {
        val vm = CalibrationViewModel(data, settings, io = dispatcher)
        val dir = java.nio.file.Files.createTempDirectory("export").toFile()
        val file = vm.exportCsv(dir)
        assertThat(file.name).isEqualTo("signal-atlas-calibration.csv")
        assertThat(file.readText()).isEqualTo("time_ms,rsrp\n1,-90\n")
        dir.deleteRecursively()
    }
}

@RunWith(RobolectricTestRunner::class)
class CalibrationScreenTest {
    @get:Rule val compose = createComposeRule()

    private val row = SectorRowUi(IDENT, Network.TELSTRA, 41428, 120.0, 12, 4.5, true, listOf(-100.0 to -95.0))

    private fun show(state: CalibrationUiState, onReset: (String) -> Unit = {}, onDelete: () -> Unit = {}) {
        compose.setContent {
            SignalAtlasTheme {
                CalibrationScreen(state, onEnabled = {}, onResetSector = onReset, onDeleteAll = onDelete, onExport = { File("x") }, onBack = {})
            }
        }
    }

    @Test fun showsTheCountToggleAndSectorWithItsScatter() {
        show(CalibrationUiState(true, 12, listOf(row)))
        compose.onNodeWithText("Improve predictions with my signal (stays on this phone)").assertIsDisplayed()
        compose.onNodeWithTag("sample-count").assertIsDisplayed()
        compose.onNodeWithText("12 samples logged").assertIsDisplayed()
        compose.onNodeWithText("Telstra · Site 41428 · 120°").assertIsDisplayed()
        compose.onNodeWithText("Offset +4.5 dB from 12 samples · applied").assertIsDisplayed()
        compose.onNodeWithTag("scatter").assertExists()
        compose.onNodeWithText("Export CSV").assertIsEnabled()
    }

    @Test fun anEmptyStateDisablesExportAndDelete() {
        show(CalibrationUiState(true, 0, emptyList()))
        compose.onNodeWithText("0 samples logged").assertIsDisplayed()
        compose.onNodeWithText("Export CSV").assertIsNotEnabled()
        compose.onNodeWithText("Delete all").assertIsNotEnabled()
        compose.onNodeWithText("None yet.", substring = true).assertIsDisplayed()
    }

    @Test fun deleteAllAsksFirstAndResetSectorIsPerSector() {
        var deleted = 0
        val reset = ArrayList<String>()
        show(CalibrationUiState(true, 12, listOf(row)), onReset = { reset += it }, onDelete = { deleted++ })
        compose.onNodeWithText("Reset this sector").performScrollTo().performClick()
        assertThat(reset).containsExactly(IDENT)
        compose.onNodeWithText("Delete all").performClick()
        assertThat(deleted).isEqualTo(0)  // confirmation first
        compose.onNodeWithText("Delete all calibration data?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertThat(deleted).isEqualTo(0)
        compose.onNodeWithText("Delete all").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertThat(deleted).isEqualTo(1)
    }
}
