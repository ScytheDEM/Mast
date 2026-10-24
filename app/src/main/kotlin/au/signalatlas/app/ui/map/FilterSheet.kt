package au.signalatlas.app.ui.map

import androidx.annotation.StringRes
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import au.signalatlas.core.model.TowerFilter

/*
 * Filters live in a Material You section (bottom sheet on phones, side pane on foldables/tablets), opened from the
 * toolbar's Filters button. Chip semantics: the default filter (everything) shows no chip selected. Tapping a chip narrows
 * to just that value; further taps add or remove values; removing the last one returns to "all".
 */

fun TowerFilter.toggleNetwork(n: Network): TowerFilter = copy(networks = toggle(networks, n, Network.entries.toSet()))

fun TowerFilter.toggleTech(t: Tech): TowerFilter = copy(techs = toggle(techs, t, Tech.entries.toSet()))

/** `null` means every band. */
fun TowerFilter.toggleBand(band: String): TowerFilter {
    val current = bands ?: return copy(bands = setOf(band))
    val next = if (band in current) current - band else current + band
    return copy(bands = next.ifEmpty { null })
}

fun TowerFilter.isNarrowedTo(n: Network) = networks.size < Network.entries.size && n in networks

fun TowerFilter.isNarrowedTo(t: Tech) = techs.size < Tech.entries.size && t in techs

private fun <T> toggle(current: Set<T>, value: T, all: Set<T>): Set<T> {
    if (current == all) return setOf(value)
    val next = if (value in current) current - value else current + value
    return next.ifEmpty { all }
}

/** Number of individual selections narrowing the map: selected networks and technologies (when narrowed) plus bands. */
fun TowerFilter.activeCount(): Int =
    (if (networks.size < Network.entries.size) networks.size else 0) +
        (if (techs.size < Tech.entries.size) techs.size else 0) +
        (bands?.size ?: 0)

/** Everything on again. */
fun TowerFilter.reset(): TowerFilter = TowerFilter()

/**
 * The filters section: networks (carrier-coloured chips), technology and bands, each on its own tonal surface, with
 * Reset. Hosted by a [ModalBottomSheet] on compact windows and by the side pane on expanded ones.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FiltersContent(
    filter: TowerFilter,
    availableBands: List<String>,
    onFilter: (TowerFilter) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = filter.activeCount()
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp).testTag("filters-content")) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.filters_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    if (count == 0) stringResource(R.string.filters_showing_all) else pluralStringResource(R.plurals.filters_active, count, count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onFilter(filter.reset()) }, enabled = count > 0) { Text(stringResource(R.string.filters_reset)) }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
        }
        FilterGroup(R.string.filters_networks) {
            Network.entries.forEach { n ->
                val selected = filter.isNarrowedTo(n)
                FilterChip(
                    selected = selected,
                    onClick = { onFilter(filter.toggleNetwork(n)) },
                    label = { Text(networkName(n)) },
                    leadingIcon = { NetworkDot(n, size = 10) },
                    trailingIcon = if (selected) {
                        { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                    modifier = Modifier.testTag("filter-net-${n.name}"),
                )
            }
        }
        FilterGroup(R.string.filters_technology) {
            listOf(Tech.LTE to R.string.tech_4g, Tech.NR to R.string.tech_5g).forEach { (t, label) ->
                FilterChip(
                    selected = filter.isNarrowedTo(t),
                    onClick = { onFilter(filter.toggleTech(t)) },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.testTag("filter-tech-${t.name}"),
                )
            }
        }
        // ACMA band names: LTE bands are "B…", NR bands are "n…".
        val (nr, lte) = availableBands.partition { it.startsWith("n") }
        FilterGroup(R.string.filters_bands) {
            FilterChip(
                selected = filter.bands == null,
                onClick = { onFilter(filter.copy(bands = null)) },
                label = { Text(stringResource(R.string.bands_all)) },
            )
        }
        listOf(R.string.bands_lte to lte, R.string.bands_nr to nr).forEach { (title, bands) ->
            if (bands.isEmpty()) return@forEach
            FilterGroup(title, subtle = true) {
                bands.forEach { band ->
                    FilterChip(
                        selected = filter.bands?.contains(band) == true,
                        onClick = { onFilter(filter.toggleBand(band)) },
                        label = { Text(band) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(@StringRes title: Int, subtle: Boolean = false, chips: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = if (subtle) 8.dp else 16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(title),
                style = if (subtle) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
                color = if (subtle) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
        }
    }
}
