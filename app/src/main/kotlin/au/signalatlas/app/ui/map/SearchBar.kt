package au.signalatlas.app.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.core.model.SiteSummary

/**
 * Material 3 search bar over the map; results are sites. [docked] uses the docked variant (for
 * the expanded two-pane layout) instead of expanding to full screen.
 *
 * The field's text lives here (not in the view model's state), so typing never round-trips
 * through a StateFlow; every change is forwarded through [onQueryChange].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TowerSearchBar(
    search: SearchUiState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    onResultClick: (SiteSummary) -> Unit,
    onOpenUpdates: () -> Unit,
    docked: Boolean,
    modifier: Modifier = Modifier,
    /** Active filters (badges the overflow icon); [onFilters] opens the filters from the overflow menu. */
    filterCount: Int = 0,
    onFilters: () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // The field only expands when the user touched the bar: a fold/unfold recomposes it and the system may hand it
    // focus, which must neither expand it nor raise the keyboard.
    var touched by remember { mutableStateOf(false) }
    val requestExpanded: (Boolean) -> Unit = { want ->
        if (!want) {
            touched = false
            onExpandedChange(false)
        } else if (touched || expanded) {
            onExpandedChange(true)
        } else {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    var text by rememberSaveable { mutableStateOf(search.query) }
    val setText = { q: String ->
        text = q
        onQueryChange(q)
    }
    val results = search.resultsFor(text)
    val input = @Composable {
        SearchBarDefaults.InputField(
            query = text,
            onQueryChange = setText,
            onSearch = { results?.firstOrNull()?.let(onResultClick); focus.clearFocus() },
            expanded = expanded,
            onExpandedChange = requestExpanded,
            placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = {
                if (expanded) {
                    IconButton(onClick = { onExpandedChange(false) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                } else {
                    Icon(Icons.Filled.Search, null)
                }
            },
            trailingIcon = {
                when {
                    text.isNotEmpty() -> IconButton(onClick = { setText("") }) {
                        Icon(Icons.Filled.Close, stringResource(R.string.clear))
                    }
                    !expanded -> OverflowMenu(onOpenUpdates, filterCount, onFilters)
                }
            },
        )
    }
    val colors = SearchBarDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    val content: @Composable () -> Unit = { SearchResults(text, results, onResultClick) }
    if (docked) {
        DockedSearchBar(
            inputField = input,
            expanded = expanded,
            onExpandedChange = requestExpanded,
            modifier = modifier.touchGate { touched = true },
            colors = colors,
            shadowElevation = 3.dp,
        ) { content() }
    } else {
        SearchBar(
            inputField = input,
            expanded = expanded,
            onExpandedChange = requestExpanded,
            modifier = modifier.touchGate { touched = true },
            colors = colors,
            shadowElevation = if (expanded) 0.dp else 3.dp,
        ) { content() }
    }
}

@Composable
private fun OverflowMenu(onOpenUpdates: () -> Unit, filterCount: Int, onFilters: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val description = if (filterCount > 0) {
        stringResource(R.string.more_options) + ", " + pluralStringResource(R.plurals.filters_badge_description, filterCount, filterCount)
    } else {
        stringResource(R.string.more_options)
    }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("overflow-button")) {
            BadgedBox(badge = { if (filterCount > 0) Badge(Modifier.testTag("filters-badge")) { Text(filterCount.toString()) } }) {
                Icon(Icons.Filled.MoreVert, description)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Text(if (filterCount > 0) stringResource(R.string.menu_filters_active, filterCount) else stringResource(R.string.menu_filters))
                },
                leadingIcon = { Icon(painterResource(R.drawable.ic_tune), null) },
                onClick = { open = false; onFilters() },
                modifier = Modifier.testTag("menu-filters"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_tower_data)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_cell_tower), null) },
                onClick = { open = false; onOpenUpdates() },
            )
        }
    }
}

/** [results] null = still looking up [query]: show nothing rather than "No matching sites". */
@Composable
private fun SearchResults(query: String, results: List<SiteSummary>?, onResultClick: (SiteSummary) -> Unit) {
    if (results == null) return
    if (query.isNotBlank() && results.isEmpty()) {
        Text(
            stringResource(R.string.search_no_results),
            Modifier.fillMaxWidth().padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(Modifier.fillMaxWidth().imePadding()) {
        items(results, key = { it.id }) { site ->
            ListItem(
                headlineContent = { Text(site.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(stringResource(R.string.site_id, site.id)) },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        site.networks.sortedBy { it.ordinal }.forEach { NetworkDot(it, size = 8) }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                modifier = Modifier.clickable { onResultClick(site) },
            )
        }
    }
}

/**
 * Folding or unfolding changes the window size class and swaps the search bar between its docked and full-screen
 * variants. Whenever [sizeKey] changes (and once at start) the search is collapsed, unfocused and the keyboard hidden,
 * so a fold never opens the keyboard or a results list the user did not ask for.
 */
@Composable
fun CollapseSearchOnWindowChange(sizeKey: Any, onCollapse: () -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val collapse by rememberUpdatedState(onCollapse)
    LaunchedEffect(sizeKey) {
        collapse()
        focus.clearFocus()
        keyboard?.hide()
    }
}

/** Calls [onTouch] when a pointer goes down anywhere on this element, without consuming it. */
private fun Modifier.touchGate(onTouch: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.any { it.pressed }) onTouch()
        }
    }
}
