package au.signalatlas.app.ui.map

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.core.update.TowerUpdateWorker
import au.signalatlas.core.update.UpdateManager
import au.signalatlas.core.update.UpdateStatus

/** Shown under the filter chips while an update is available, running or has failed. */
@Composable
fun UpdateChip(status: UpdateStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    when (status) {
        is UpdateStatus.Available -> AssistChip(
            onClick = onClick,
            label = { Text(stringResource(R.string.chip_update_available)) },
            leadingIcon = { Icon(Icons.Filled.Refresh, null, Modifier.size(AssistChipDefaults.IconSize)) },
            colors = colors,
            border = null,
            modifier = modifier,
        )
        is UpdateStatus.Running -> AssistChip(
            onClick = onClick,
            label = { Text(runningLabel(status)) },
            leadingIcon = {
                CircularProgressIndicator(
                    progress = { status.overallFraction() },
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                    strokeWidth = 2.dp,
                )
            },
            colors = colors,
            border = null,
            modifier = modifier,
        )
        is UpdateStatus.Failed -> AssistChip(
            onClick = onClick,
            label = { Text(stringResource(R.string.chip_update_failed)) },
            leadingIcon = {
                Icon(
                    Icons.Filled.Warning, null, Modifier.size(AssistChipDefaults.IconSize),
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            colors = colors,
            border = null,
            modifier = modifier,
        )
        else -> Unit
    }
}

fun UpdateStatus.showsChip(): Boolean =
    this is UpdateStatus.Available || this is UpdateStatus.Running || this is UpdateStatus.Failed

/** Weight of each worker phase in one overall 0..1 progress (download dominates on mobile). */
private val PHASES: List<Pair<String, Float>> = listOf(
    TowerUpdateWorker.PHASE_DOWNLOAD to 0.55f,
    "client" to 0.02f,
    "licence" to 0.03f,
    "devices" to 0.25f,
    "sites" to 0.06f,
    "antennas" to 0.03f,
    "finalise" to 0.06f,
)

/** Overall progress across download and import phases; per-phase fractions restart at 0. */
fun UpdateStatus.Running.overallFraction(): Float {
    val index = PHASES.indexOfFirst { it.first == phase }
    if (index < 0) return 0f
    val before = PHASES.take(index).sumOf { it.second.toDouble() }.toFloat()
    return (before + PHASES[index].second * fraction.coerceIn(0f, 1f)).coerceIn(0f, 1f)
}

/** A manual update queued until a (Wi-Fi) network appears; not yet started. */
fun UpdateStatus.Running.isWaiting(): Boolean =
    phase == UpdateManager.PHASE_WAITING || phase == UpdateManager.PHASE_WAITING_WIFI

@Composable
fun runningLabel(status: UpdateStatus.Running): String {
    val percent = (status.overallFraction() * 100).toInt()
    return when (status.phase) {
        UpdateManager.PHASE_WAITING -> stringResource(R.string.phase_waiting)
        UpdateManager.PHASE_WAITING_WIFI -> stringResource(R.string.phase_waiting_wifi)
        TowerUpdateWorker.PHASE_STARTING -> stringResource(R.string.phase_starting)
        TowerUpdateWorker.PHASE_DOWNLOAD -> stringResource(R.string.phase_download, percent)
        else -> stringResource(R.string.phase_building, percent)
    }
}
