package au.signalatlas.app.ui.offline

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import au.signalatlas.app.packs.PackManager
import au.signalatlas.app.packs.PackRecord
import au.signalatlas.app.packs.PackRepository
import au.signalatlas.app.packs.PackScheduler
import au.signalatlas.app.packs.PackState
import au.signalatlas.app.ui.theme.SignalAtlasTheme
import au.signalatlas.core.geo.packs.PackArea
import au.signalatlas.core.geo.packs.PackCatalogue
import au.signalatlas.core.geo.packs.PackContentSource
import au.signalatlas.core.model.GeoBounds
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OfflineUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class Source(override val id: String, val bytes: Long) : PackContentSource {
        override suspend fun estimateBytes(area: PackArea) = bytes
        override suspend fun download(packId: String, generation: Long, area: PackArea, allowMetered: Boolean, progress: (Float) -> Unit) = Unit
        override suspend fun sizeBytes(packId: String, area: PackArea) = 0L
        override suspend fun remove(packId: String, area: PackArea) = Unit
    }

    private val queued = ArrayList<Pair<String, Boolean>>()
    private fun manager() = PackManager(
        PackRepository(File(tmp.root, "p.json")),
        mapOf(PackManager.BASEMAP to Source("basemap", 50_000_000), PackManager.ELEVATION to Source("dem", 10_000_000)),
        object : PackScheduler {
            override fun enqueue(packId: String, allowMetered: Boolean) { queued += packId to allowMetered }
            override fun cancel(packId: String) = Unit
        },
    )

    @Test fun `the size is worked out before the offer can be confirmed, and the choice reaches the scheduler`() = runTest(dispatcher) {
        val vm = OfflineViewModel(manager(), dispatcher)
        vm.offer(PackCatalogue.byId("metro-hobart")!!)
        advanceUntilIdle()
        assertThat(vm.state.value.offer!!.estimate!!.totalBytes).isEqualTo(60_000_000)
        vm.setAllowMetered(true)
        vm.confirmOffer()
        advanceUntilIdle()
        assertThat(vm.state.value.offer).isNull()
        assertThat(queued).containsExactly("metro-hobart" to true)
        assertThat(vm.state.value.packs.map { it.id }).containsExactly("metro-hobart")
    }

    @Test fun `a drawn box is offered as a custom pack`() = runTest(dispatcher) {
        val vm = OfflineViewModel(manager(), dispatcher) { 77L }
        vm.offerCustom(GeoBounds(-34.0, 150.0, -33.0, 151.0), "Custom area")
        advanceUntilIdle()
        assertThat(vm.state.value.offer!!.def.id).isEqualTo("custom-77")
        vm.dismissOffer()
        advanceUntilIdle()
        assertThat(vm.state.value.offer).isNull()
        assertThat(queued).isEmpty()
    }

    private fun pack(state: PackState, bytes: Long = 0) = PackRecord(
        "metro-sydney", "Sydney", au.signalatlas.core.geo.packs.PackKind.METRO, PackCatalogue.byId("metro-sydney")!!.area,
        state, progress = 0.4f, bytes = bytes, generation = 1,
    )

    private fun show(state: OfflineUiState, offered: MutableList<String> = ArrayList(), confirmed: MutableList<Boolean> = ArrayList()) {
        compose.setContent {
            SignalAtlasTheme {
                OfflineRegionsScreen(
                    state, onOffer = { offered += it.id }, onDrawBox = {}, onUpdate = {}, onDelete = {}, onMobileData = {},
                    onDismissOffer = {}, onConfirmOffer = { confirmed += true }, onBack = {},
                )
            }
        }
    }

    @Test fun `lists packs with their size and offers the rest of the catalogue`() {
        val offered = ArrayList<String>()
        show(OfflineUiState(listOf(pack(PackState.READY, 12_300_000))), offered)
        compose.onNodeWithText("Sydney").assertExists()
        compose.onNodeWithText("11.7 MB").assertExists()
        compose.onNodeWithTag("offline-list").performScrollToNode(hasTestTag("offer-metro-melbourne"))
        compose.onNodeWithTag("offer-metro-melbourne").performClick()
        assertThat(offered).containsExactly("metro-melbourne")
        // Sydney is already here, so it is not offered again.
        compose.onNodeWithTag("offline-list").performScrollToNode(hasTestTag("offer-metro-darwin"))
        assertThat(compose.onAllNodesWithTagCount("offer-metro-sydney")).isEqualTo(0)
    }

    @Test fun `the dialog shows the size and enables Download only once it is known`() {
        val def = PackCatalogue.byId("metro-hobart")!!
        show(OfflineUiState(offer = DownloadOffer(def)))
        compose.onNodeWithText("Working out the size").assertExists()
        compose.onNodeWithText("Download").assertIsNotEnabled()
    }

    @Test fun `with an estimate the dialog states the total and can be confirmed`() {
        val def = PackCatalogue.byId("metro-hobart")!!
        val confirmed = ArrayList<Boolean>()
        show(OfflineUiState(offer = DownloadOffer(def, au.signalatlas.app.packs.PackEstimate(50_000_000, 10_000_000))), confirmed = confirmed)
        compose.onNodeWithTag("offer-size").assertExists()
        compose.onNodeWithText("Download").assertIsEnabled().performClick()
        assertThat(confirmed).hasSize(1)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
}
