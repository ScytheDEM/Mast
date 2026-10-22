package au.signalatlas.app.ui.map

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import au.signalatlas.core.model.TowerFilter

/*
 * Chip semantics: the default filter (everything) shows no chip selected. Tapping a chip narrows
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

/** One chip per network, then 4G, 5G and "Bands" (opens [BandsSheet]); scrolls horizontally. */
@Composable
fun FilterChipRow(
    filter: TowerFilter,
    onFilter: (TowerFilter) -> Unit,
    onOpenBands: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Network.entries.forEach { n ->
            MapChip(
                selected = filter.isNarrowedTo(n),
                onClick = { onFilter(filter.toggleNetwork(n)) },
                label = networkName(n),
                leading = { NetworkDot(n, size = 8) },
            )
        }
        MapChip(filter.isNarrowedTo(Tech.LTE), { onFilter(filter.toggleTech(Tech.LTE)) }, stringResource(R.string.tech_4g))
        MapChip(filter.isNarrowedTo(Tech.NR), { onFilter(filter.toggleTech(Tech.NR)) }, stringResource(R.string.tech_5g))
        MapChip(
            selected = filter.bands != null,
            onClick = onOpenBands,
            label = filter.bands?.let { stringResource(R.string.filter_bands_count, it.size) } ?: stringResource(R.string.filter_bands),
            leading = { Icon(painterResource(R.drawable.ic_tune), null, Modifier.size(FilterChipDefaults.IconSize)) },
            trailing = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
        )
    }
}

/** A filter chip that reads well on top of the map (filled, softly elevated, no outline). */
@Composable
private fun MapChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ElevatedFilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            leading
        },
        trailingIcon = trailing,
        colors = FilterChipDefaults.elevatedFilterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        elevation = FilterChipDefaults.elevatedFilterChipElevation(elevation = 2.dp),
    )
}

/** Band picker: all bands, or any combination, grouped 4G then 5G. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BandsSheet(
    filter: TowerFilter,
    availableBands: List<String>,
    onFilter: (TowerFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.bands_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
            }
            Spacer(Modifier.height(8.dp))
            FilterChip(
                selected = filter.bands == null,
                onClick = { onFilter(filter.copy(bands = null)) },
                label = { Text(stringResource(R.string.bands_all)) },
            )
            // ACMA band names: LTE bands are "B…", NR bands are "n…".
            val (nr, lte) = availableBands.partition { it.startsWith("n") }
            listOf(R.string.bands_lte to lte, R.string.bands_nr to nr).forEach { (title, bands) ->
                if (bands.isEmpty()) return@forEach
                Spacer(Modifier.height(16.dp))
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
}
