package au.signalatlas.app.ui.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import au.signalatlas.app.ui.theme.SignalAtlasTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Folding/unfolding (a window size class change) must leave the search collapsed. */
@RunWith(RobolectricTestRunner::class)
class SearchWindowChangeTest {
    @get:Rule val compose = createComposeRule()

    private var expanded by mutableStateOf(false)
    private var sizeKey by mutableStateOf("compact")

    private fun show() {
        compose.setContent {
            SignalAtlasTheme {
                TowerSearchBar(
                    search = SearchUiState(), expanded = expanded, onExpandedChange = { expanded = it },
                    onQueryChange = {}, onResultClick = {}, onOpenUpdates = {}, docked = sizeKey != "compact",
                )
                CollapseSearchOnWindowChange(sizeKey) { expanded = false }
            }
        }
    }

    @Test fun startsCollapsed() {
        show()
        compose.waitForIdle()
        assertThat(expanded).isFalse()
    }

    @Test fun windowSizeChangeCollapsesAnExpandedSearch() {
        show()
        expanded = true // the user opened it on the folded screen
        compose.waitForIdle()
        assertThat(expanded).isTrue()
        sizeKey = "expanded" // unfold
        compose.waitForIdle()
        assertThat(expanded).isFalse()
    }

    @Test fun sameSizeKeepsAnExpandedSearch() {
        show()
        expanded = true
        compose.waitForIdle()
        compose.waitForIdle()
        assertThat(expanded).isTrue()
    }
}
