package au.signalatlas.app.ui.calibration

import android.content.Intent
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import au.signalatlas.app.R
import au.signalatlas.app.ui.connected.networkName
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** Settings → Calibration: the switch, sample count, calibrated sectors with their scatter, export and delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    state: CalibrationUiState,
    onEnabled: (Boolean) -> Unit,
    onResetSector: (String) -> Unit,
    onDeleteAll: () -> Unit,
    /** Writes the export and returns the file; the screen shares it. */
    onExport: suspend (File) -> File,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareTitle = stringResource(R.string.calibration_share_title)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calibration_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.calibration_toggle),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                        )
                        Switch(checked = state.enabled, onCheckedChange = onEnabled)
                    }
                    Text(
                        stringResource(R.string.calibration_toggle_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        pluralStringResource(R.plurals.calibration_samples, state.sampleCount, state.sampleCount),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("sample-count"),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val file = onExport(File(context.cacheDir, "calibration-export"))
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                    val send = Intent(Intent.ACTION_SEND)
                                        .setType("text/csv")
                                        .putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    context.startActivity(Intent.createChooser(send, shareTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            },
                            enabled = state.sampleCount > 0,
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text(stringResource(R.string.calibration_export)) }
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            enabled = state.sampleCount > 0 || state.sectors.isNotEmpty(),
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text(stringResource(R.string.calibration_delete_all)) }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.calibration_sectors), style = MaterialTheme.typography.titleMedium)
                    if (state.sectors.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.calibration_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(state.sectors, key = { it.ident }) { row ->
                SectorCard(row, onReset = { onResetSector(row.ident) }, Modifier.widthIn(max = 600.dp).fillMaxWidth())
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.calibration_delete_title)) },
            text = { Text(stringResource(R.string.calibration_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDeleteAll()
                }) { Text(stringResource(R.string.calibration_delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SectorCard(row: SectorRowUi, onReset: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(sectorTitle(row), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    if (row.applied) R.string.calibration_sector_applied else R.string.calibration_sector_pending,
                    signed(row.offsetDb), row.n,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Scatter(row.points, row.offsetDb.takeIf { row.applied }, Modifier.fillMaxWidth().height(140.dp))
            TextButton(onClick = onReset, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.calibration_reset_sector))
            }
        }
    }
}

@Composable
private fun sectorTitle(row: SectorRowUi): String {
    val azimuth = row.azimuthDeg?.let { stringResource(R.string.calibration_azimuth, it.toInt()) }
        ?: stringResource(R.string.calibration_omni)
    val site = row.siteId?.let { stringResource(R.string.site_id, it) }
    return listOfNotNull(networkName(row.network), site, azimuth).joinToString(" · ")
}

private fun signed(db: Double): String = String.format(Locale.US, "%+.1f", db).replace('-', '−')

/** Measured (y) against predicted (x) RSRP; the dashed line is "prediction is right", the solid one the applied offset. */
@Composable
internal fun Scatter(points: List<Pair<Double, Double>>, appliedOffsetDb: Double?, modifier: Modifier = Modifier) {
    val dot = MaterialTheme.colorScheme.primary
    val ideal = MaterialTheme.colorScheme.outline
    val fit = MaterialTheme.colorScheme.tertiary
    val description = stringResource(R.string.calibration_scatter_description, points.size)
    Canvas(modifier.semantics { contentDescription = description }.testTag("scatter")) {
        val lo = SCATTER_MIN
        val hi = SCATTER_MAX
        fun x(v: Double) = ((v.coerceIn(lo, hi) - lo) / (hi - lo)).toFloat() * size.width
        fun y(v: Double) = size.height - ((v.coerceIn(lo, hi) - lo) / (hi - lo)).toFloat() * size.height
        drawLine(ideal, Offset(x(lo), y(lo)), Offset(x(hi), y(hi)), strokeWidth = 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
        if (appliedOffsetDb != null) {
            drawLine(fit, Offset(x(lo), y(lo + appliedOffsetDb)), Offset(x(hi), y(hi + appliedOffsetDb)), strokeWidth = 3f)
        }
        points.forEach { (p, m) -> drawCircle(dot.copy(alpha = 0.6f), radius = 4.dp.toPx(), center = Offset(x(p), y(m))) }
        drawRect(ideal.copy(alpha = 0.4f), style = Stroke(1f))
    }
}

private const val SCATTER_MIN = -130.0
private const val SCATTER_MAX = -60.0
