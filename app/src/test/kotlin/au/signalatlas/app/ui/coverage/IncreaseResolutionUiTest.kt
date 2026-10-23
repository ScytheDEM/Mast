package au.signalatlas.app.ui.coverage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import au.signalatlas.app.ui.map.IncreaseStatus
import au.signalatlas.app.ui.map.IncreaseUiState
import au.signalatlas.app.ui.theme.SignalAtlasTheme
import au.signalatlas.core.coverage.Accuracy
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IncreaseResolutionUiTest {
    @get:Rule val compose = createComposeRule()

    private var downloads = 0
    private var confirms = 0
    private var dismissals = 0

    private fun show(ui: IncreaseUiState) = compose.setContent {
        SignalAtlasTheme { IncreaseResolutionContent(ui, { downloads++ }, { confirms++ }, { dismissals++ }) }
    }

    @Test fun roundsToWholeMegabytesAndSaysLessThanOneBelowOne() {
        assertThat(roundedMb(0)).isEqualTo(0)
        assertThat(roundedMb(1_048_575)).isEqualTo(0)
        assertThat(roundedMb(1_048_576)).isEqualTo(1)
        assertThat(roundedMb(12L * 1_048_576 + 400_000)).isEqualTo(12)
        assertThat(roundedMb(12L * 1_048_576 + 600_000)).isEqualTo(13)
    }

    @Test fun readySheetShowsTheSizeAndAnEnabledDownloadButton() {
        show(IncreaseUiState(IncreaseStatus.READY, 12L * 1_048_576))
        compose.onNodeWithText("Increase resolution · about 12 MB").assertIsDisplayed()
        compose.onNodeWithText("Download").assertIsEnabled().performClick()
        assertThat(downloads).isEqualTo(1)
    }

    @Test fun smallDownloadsSayLessThanOneMegabyte() {
        show(IncreaseUiState(IncreaseStatus.READY, 200_000))
        compose.onNodeWithText("Increase resolution · less than 1 MB").assertIsDisplayed()
    }

    @Test fun offlineSaysAvailableWhenOnlineAndCannotBePressed() {
        show(IncreaseUiState(IncreaseStatus.OFFLINE))
        compose.onNodeWithText("Available when online").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun zoomedOutSaysZoomInAndOffersNoDownload() {
        show(IncreaseUiState(IncreaseStatus.ZOOM_IN))
        compose.onNodeWithText("Zoom in to improve accuracy").assertIsDisplayed()
        compose.onNodeWithText("Download").assertIsNotEnabled()
    }

    @Test fun fetchingShowsAProgressBarAtTheFraction() {
        show(IncreaseUiState(IncreaseStatus.FETCHING, 12L * 1_048_576, 0.4f))
        compose.onNodeWithText("Downloading terrain…").assertIsDisplayed()
        compose.onNode(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo(0.4f, 0f..1f))).assertIsDisplayed()
        compose.onNodeWithText("Download").assertDoesNotExist()
    }

    @Test fun meteredConfirmationNamesTheSizeAndAcceptingStartsTheFetch() {
        show(IncreaseUiState(IncreaseStatus.CONFIRM_METERED, 30L * 1_048_576))
        compose.onNodeWithText("Use mobile data for about 30 MB?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertThat(dismissals).isEqualTo(1)
        compose.onNodeWithText("Use mobile data").performClick()
        assertThat(confirms).isEqualTo(1)
    }

    @Test fun storageAndErrorMessagesAreShownWithRetry() {
        show(IncreaseUiState(IncreaseStatus.STORAGE_FULL, 5L * 1_048_576))
        compose.onNodeWithText("Not enough free storage for the terrain data.").assertIsDisplayed()
        compose.onNodeWithText("Download").assertIsEnabled()
    }

    @Test fun chipLabelsAndTapBehaviour() {
        var taps = 0
        var accuracy by androidx.compose.runtime.mutableStateOf(Accuracy.QUICK)
        compose.setContent { SignalAtlasTheme { AccuracyChip(accuracy, onClick = { taps++ }) } }
        compose.onNodeWithText("Quick estimate").assertIsDisplayed().performClick()
        accuracy = Accuracy.PARTIAL
        compose.onNodeWithText("Terrain (partial)").assertIsDisplayed().performClick()
        assertThat(taps).isEqualTo(2)
        accuracy = Accuracy.TERRAIN
        compose.onNodeWithText("Terrain").assertIsDisplayed().performClick()
        assertThat(taps).isEqualTo(2)
    }
}
