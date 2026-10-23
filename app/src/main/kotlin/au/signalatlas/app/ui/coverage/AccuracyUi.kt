package au.signalatlas.app.ui.coverage

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

/** How the tiles in view were predicted. Quick and partial can be tapped to increase the resolution. */
@Composable
fun AccuracyChip(accuracy: Accuracy, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = when (accuracy) {
        Accuracy.QUICK -> R.string.accuracy_quick
        Accuracy.PARTIAL -> R.string.accuracy_partial
        Accuracy.TERRAIN -> R.string.accuracy_terrain
    }
    if (accuracy == Accuracy.TERRAIN) {
        // A label, not a chip: nothing to press (and nothing for TalkBack to offer).
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = modifier) {
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    } else {
        AssistChip(onClick = onClick, label = { Text(stringResource(label)) }, modifier = modifier)
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
            LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth())
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
