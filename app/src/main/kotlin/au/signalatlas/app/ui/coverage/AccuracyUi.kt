@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package au.signalatlas.app.ui.coverage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.app.ui.map.IncreaseStatus
import au.signalatlas.app.ui.map.IncreaseUiState
import au.signalatlas.core.coverage.Accuracy
import kotlin.math.roundToInt

private const val MIB = 1024L * 1024

/** Whole MB for "about N MB"; 0 means "less than 1 MB". */
internal fun roundedMb(bytes: Long): Int = if (bytes < MIB) 0 else (bytes.toDouble() / MIB).roundToInt()

/**
 * How the tiles in view were predicted, shown with the coverage controls so it is never missed. Quick and partial
 * predictions leave out hills and valleys, so they carry an "Improve accuracy" button that downloads terrain.
 */
@Composable
fun AccuracyCard(accuracy: Accuracy, onImprove: () -> Unit, modifier: Modifier = Modifier) {
    val improvable = accuracy != Accuracy.TERRAIN
    Surface(
        color = if (improvable) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (improvable) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 2.dp,
        modifier = modifier.testTag("accuracy-card"),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    stringResource(
                        when (accuracy) {
                            Accuracy.QUICK -> R.string.accuracy_quick
                            Accuracy.PARTIAL -> R.string.accuracy_partial
                            Accuracy.TERRAIN -> R.string.accuracy_terrain
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (improvable) {
                    Text(stringResource(R.string.accuracy_quick_detail), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (improvable) {
                FilledTonalButton(onClick = onImprove, modifier = Modifier.testTag("improve-accuracy")) {
                    Text(stringResource(R.string.accuracy_improve))
                }
            }
        }
    }
}

/** The bottom sheet behind the accuracy chip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncreaseResolutionSheet(
    ui: IncreaseUiState,
    onDownload: () -> Unit,
    onConfirmMetered: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        IncreaseResolutionContent(ui, onDownload, onConfirmMetered, onDismiss)
    }
}

@Composable
fun IncreaseResolutionContent(
    ui: IncreaseUiState,
    onDownload: () -> Unit,
    onConfirmMetered: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(
            when (val bytes = ui.bytes) {
                null -> stringResource(R.string.increase_title)
                else -> roundedMb(bytes).let {
                    if (it == 0) stringResource(R.string.increase_title_small) else stringResource(R.string.increase_title_about, it)
                }
            },
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        val message = when (ui.status) {
            IncreaseStatus.ZOOM_IN -> R.string.increase_zoom_in
            IncreaseStatus.STORAGE_FULL -> R.string.increase_storage_full
            IncreaseStatus.ERROR -> R.string.increase_error
            else -> R.string.increase_body
        }
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        if (ui.status == IncreaseStatus.FETCHING) {
            Text(stringResource(R.string.increase_fetching), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            LinearWavyProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth())
        } else {
            val offline = ui.status == IncreaseStatus.OFFLINE
            Button(
                onClick = onDownload,
                enabled = ui.status == IncreaseStatus.READY || ui.status == IncreaseStatus.STORAGE_FULL || ui.status == IncreaseStatus.ERROR,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text(stringResource(if (offline) R.string.increase_offline else R.string.increase_download)) }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (ui.status == IncreaseStatus.CONFIRM_METERED) {
        val mb = roundedMb(ui.bytes ?: 0L)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(if (mb == 0) stringResource(R.string.increase_metered_small) else stringResource(R.string.increase_metered_about, mb))
            },
            text = { Text(stringResource(R.string.metered_body)) },
            confirmButton = { TextButton(onClick = onConfirmMetered) { Text(stringResource(R.string.metered_confirm)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
