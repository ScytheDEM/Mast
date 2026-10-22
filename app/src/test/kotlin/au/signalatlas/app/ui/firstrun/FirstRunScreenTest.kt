package au.signalatlas.app.ui.firstrun

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import au.signalatlas.app.ui.theme.SignalAtlasTheme
import au.signalatlas.core.update.UpdateStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FirstRunScreenTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<Boolean>()

    private fun show(status: UpdateStatus, metered: Boolean = false) {
        compose.setContent {
            SignalAtlasTheme {
                FirstRunScreen(status = status, isMetered = { metered }, onDownload = { calls += it })
            }
        }
    }

    @Test fun initial_showsCopyAndDownloadButton() {
        show(UpdateStatus.Unknown)
        compose.onNodeWithText("Download tower data").assertIsDisplayed()
        compose.onNodeWithText(
            "Signal Atlas builds its tower database from the ACMA register on your phone (about 68 MB). After that, it works offline.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Download now").performClick()
        assertThat(calls).containsExactly(false)
    }

    @Test fun offline_showsTryAgain_whichStartsTheDownload() {
        show(UpdateStatus.Offline)
        compose.onNodeWithText("Try again").assertIsDisplayed().performClick()
        assertThat(calls).containsExactly(false)
    }

    @Test fun failed_showsMessageAndTryAgain() {
        show(UpdateStatus.Failed("Could not reach ACMA: timeout"))
        compose.onNodeWithText("Could not reach ACMA: timeout").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test fun running_showsProgress_andHidesButton() {
        show(UpdateStatus.Running("download", 0.4f))
        compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
                .and(SemanticsMatcher("determinate") { node ->
                    node.config[SemanticsProperties.ProgressBarRangeInfo] != ProgressBarRangeInfo.Indeterminate
                }),
        ).assertIsDisplayed()
        compose.onNodeWithText("Download now").assertDoesNotExist()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test fun metered_asksBeforeUsingMobileData() {
        show(UpdateStatus.Unknown, metered = true)
        compose.onNodeWithText("Download now").performClick()
        assertThat(calls).isEmpty()
        compose.onNodeWithText("Use mobile data for about 68 MB?").assertIsDisplayed()
        compose.onNodeWithText("Use mobile data").performClick()
        assertThat(calls).containsExactly(true)
    }
}
