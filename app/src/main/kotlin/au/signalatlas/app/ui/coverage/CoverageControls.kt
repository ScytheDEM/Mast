package au.signalatlas.app.ui.coverage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.app.ui.map.CoverageUiState
import au.signalatlas.app.ui.map.MapViewModel
import au.signalatlas.app.ui.map.networkNameRes
import au.signalatlas.core.coverage.CoverageSelection
import au.signalatlas.core.model.Network

/** Everything the coverage controls can change; implemented by the view model's setters. */
class CoverageActions(
    val onToggle: (Boolean) -> Unit,
    val onOptions: () -> Unit,
    val onNetwork: (Network) -> Unit,
    val onFiveGOnly: (Boolean) -> Unit,
    val onIndoor: (Boolean) -> Unit,
    val onView: (CoverageSelection.View) -> Unit,
)

/**
 * The floating toolbar: "Filters" (with a badge counting the active filters; only when [onFilters] is given), a labelled
 * "Coverage" toggle and, while coverage is on, a button that shows or hides the options.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CoverageToolbar(
    on: Boolean,
    optionsOpen: Boolean,
    actions: CoverageActions,
    modifier: Modifier = Modifier,
    filterCount: Int = 0,
    filtersOpen: Boolean = false,
    onFilters: (() -> Unit)? = null,
    /** The "Trip" button (only when given): checked while a trip runs. */
    tripRunning: Boolean = false,
    onTrip: (() -> Unit)? = null,
) {
    HorizontalFloatingToolbar(expanded = true, modifier = modifier) {
        if (onTrip != null) {
            ToggleButton(checked = tripRunning, onCheckedChange = { onTrip() }, modifier = Modifier.testTag("trip-button")) {
                Icon(painterResource(R.drawable.ic_trip), null, Modifier.size(ToggleButtonDefaults.IconSize))
                Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
                Text(stringResource(R.string.trip_button))
            }
        }
        if (onFilters != null) {
            val badgeDescription = if (filterCount > 0) pluralStringResource(R.plurals.filters_badge_description, filterCount, filterCount) else null
            ToggleButton(checked = filtersOpen, onCheckedChange = { onFilters() }, modifier = Modifier.testTag("filters-button")) {
                BadgedBox(
                    badge = { if (filterCount > 0) Badge(Modifier.testTag("filters-badge")) { Text(filterCount.toString()) } },
                    modifier = Modifier.semantics { badgeDescription?.let { contentDescription = it } },
                ) {
                    Icon(painterResource(R.drawable.ic_tune), null, Modifier.size(ToggleButtonDefaults.IconSize))
                }
                Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
                Text(stringResource(R.string.filters_button))
            }
        }
        ToggleButton(checked = on, onCheckedChange = actions.onToggle, modifier = Modifier.testTag("coverage-button")) {
            Icon(painterResource(R.drawable.ic_coverage), null, Modifier.size(ToggleButtonDefaults.IconSize))
            Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
            Text(stringResource(R.string.coverage_toolbar))
        }
        AnimatedVisibility(on) {
            IconToggleButton(checked = optionsOpen, onCheckedChange = { actions.onOptions() }) {
                Icon(painterResource(R.drawable.ic_coverage_options), stringResource(R.string.coverage_options))
            }
        }
    }
}

/** The compact control card: network, techs, indoor and view. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverageControlsCard(state: CoverageUiState, actions: CoverageActions, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(max = 360.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Choice(
                MapViewModel.COVERAGE_NETWORKS, state.network, actions.onNetwork, { stringResource(networkNameRes(it)) },
            )
            Choice(
                listOf(false, true), state.fiveGOnly, actions.onFiveGOnly,
                { stringResource(if (it) R.string.coverage_5g_only else R.string.coverage_4g_5g) },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.coverage_indoor), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = state.indoor, onCheckedChange = actions.onIndoor)
            }
            Choice(
                CoverageSelection.View.entries, state.view, actions.onView,
                { stringResource(if (it == CoverageSelection.View.RSRP) R.string.coverage_signal else R.string.coverage_reliability) },
            )
        }
    }
}

/** One single-choice segmented row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<T>, selected: T, onSelect: (T) -> Unit, label: @Composable (T) -> String) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
                label = { Text(label(option), maxLines = 1) },
            )
        }
    }
}
