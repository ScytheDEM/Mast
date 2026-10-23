package au.signalatlas.app.ui.storage

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.core.geo.GeoPrefs
import kotlinx.coroutines.delay
import java.util.Locale

private const val KIB = 1024L
private const val MIB = KIB * 1024
private const val GIB = MIB * 1024

/** "0 B", "512 KB", "12.3 MB", "1.4 GB": the largest unit that keeps the number at least 1. */
fun formatSize(res: Resources, bytes: Long): String {
    val b = bytes.coerceAtLeast(0)
    return when {
        b < KIB -> res.getString(R.string.size_bytes, b.toString())
        b < MIB -> res.getString(R.string.size_kb, String.format(Locale.getDefault(), "%.0f", b.toDouble() / KIB))
        b < GIB -> res.getString(R.string.size_mb, String.format(Locale.getDefault(), "%.1f", b.toDouble() / MIB))
        else -> res.getString(R.string.size_gb, String.format(Locale.getDefault(), "%.1f", b.toDouble() / GIB))
    }
}

private enum class Clearing { None, Terrain, Cache }

/** Settings → Storage: sizes of what the app keeps, Clear for the two caches and the terrain limit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    state: StorageUiState,
    onRefresh: () -> Unit,
    onClearTerrain: () -> Unit,
    onClearCoverageCache: () -> Unit,
    onBudget: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var clearing by rememberSaveable { mutableStateOf(Clearing.None) }
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    // Downloads and renders keep changing the sizes while the screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            delay(REFRESH_MS)
        }
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.storage_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
                val sizes = state.sizes
                fun size(pick: (au.signalatlas.app.data.StorageSizes) -> Long) =
                    sizes?.let { formatSize(res, pick(it)) } ?: res.getString(R.string.storage_measuring)
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        StorageRow(stringResource(R.string.storage_towers), size { it.towers })
                        RowDivider()
                        StorageRow(stringResource(R.string.storage_patterns), size { it.patterns })
                        RowDivider()
                        StorageRow(stringResource(R.string.storage_terrain), size { it.terrain }, onClear = { clearing = Clearing.Terrain })
                        RowDivider()
                        StorageRow(stringResource(R.string.storage_coverage_cache), size { it.coverageCache }, onClear = { clearing = Clearing.Cache })
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.storage_budget), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.storage_budget_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                BudgetPicker(state.terrainBudgetMib, onBudget)
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    when (clearing) {
        Clearing.None -> Unit
        Clearing.Terrain -> ConfirmClear(R.string.storage_clear_terrain_title, R.string.storage_clear_terrain_body, onDismiss = { clearing = Clearing.None }) {
            clearing = Clearing.None
            onClearTerrain()
        }
        Clearing.Cache -> ConfirmClear(R.string.storage_clear_cache_title, R.string.storage_clear_cache_body, onDismiss = { clearing = Clearing.None }) {
            clearing = Clearing.None
            onClearCoverageCache()
        }
    }
}

private const val REFRESH_MS = 3_000L

@Composable
private fun StorageRow(label: String, size: String, onClear: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(size, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onClear != null) {
            TextButton(onClick = onClear) { Text(stringResource(R.string.clear)) }
        } else {
            Spacer(Modifier.padding(end = 8.dp))
        }
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetPicker(selectedMib: Int, onSelect: (Int) -> Unit) {
    val options = GeoPrefs.BUDGET_OPTIONS_MIB
    val labels = listOf(R.string.storage_budget_05, R.string.storage_budget_1, R.string.storage_budget_2, R.string.storage_budget_4)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, mib ->
            SegmentedButton(
                selected = mib == selectedMib,
                onClick = { onSelect(mib) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(stringResource(labels[i])) }
        }
    }
}

@Composable
private fun ConfirmClear(title: Int, body: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.clear)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
