package au.signalatlas.app.ui.firstrun

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.app.ui.map.isWaiting
import au.signalatlas.app.ui.map.overallFraction
import au.signalatlas.app.ui.map.runningLabel
import au.signalatlas.app.ui.updates.NetworkKind
import au.signalatlas.app.ui.updates.WaitingActions
import au.signalatlas.app.ui.updates.downloadSizeLabel
import au.signalatlas.app.ui.updates.rememberDownloadGate
import au.signalatlas.core.update.TowerUpdateWorker
import au.signalatlas.core.update.UpdateStatus

/** Replaces the map until a tower DB is installed. */
@Composable
fun FirstRunScreen(
    status: UpdateStatus,
    network: () -> NetworkKind,
    onDownload: (allowMetered: Boolean) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gate = rememberDownloadGate(network, onDownload)
    gate.Dialog(downloadSizeLabel(status))

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 440.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_cell_tower), null,
                        Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.height(28.dp))
                Text(
                    stringResource(R.string.firstrun_title),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.firstrun_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(36.dp))
                AnimatedContent(
                    targetState = status.panel(gate.offline),
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    contentAlignment = Alignment.Center,
                    label = "firstRunAction",
                ) { panel ->
                    when (panel) {
                        Panel.Progress -> ProgressPanel(status as? UpdateStatus.Running)
                        Panel.Waiting -> WaitingPanel(status as? UpdateStatus.Running, onDownload, onCancel)
                        Panel.Error -> ErrorPanel(status, gate.offline, onRetry = gate::request)
                        Panel.Start -> Button(onClick = gate::request, Modifier.widthIn(min = 200.dp).height(52.dp)) {
                            Text(stringResource(R.string.firstrun_download), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

private enum class Panel { Start, Progress, Waiting, Error }

private fun UpdateStatus.panel(noNetwork: Boolean): Panel = when {
    this is UpdateStatus.Running -> if (isWaiting()) Panel.Waiting else Panel.Progress
    noNetwork || this is UpdateStatus.Failed || this == UpdateStatus.Offline -> Panel.Error
    else -> Panel.Start
}

/** Queued until a (Wi-Fi) network appears: say why, and offer a way out. */
@Composable
private fun WaitingPanel(running: UpdateStatus.Running?, onDownload: (Boolean) -> Unit, onCancel: () -> Unit) {
    Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        LinearProgressIndicator(Modifier.widthIn(max = 320.dp).fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        if (running != null) {
            Text(runningLabel(running), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(20.dp))
        WaitingActions(onUseMobileData = { onDownload(true) }, onCancel = onCancel)
    }
}

@Composable
private fun ProgressPanel(running: UpdateStatus.Running?) {
    Column(Modifier.widthIn(max = 320.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val indeterminate = running == null || running.phase == TowerUpdateWorker.PHASE_STARTING
        if (indeterminate) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { running.overallFraction() }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        if (running != null) {
            Text(
                runningLabel(running),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorPanel(status: UpdateStatus, noNetwork: Boolean, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (status is UpdateStatus.Failed && !noNetwork) status.message else stringResource(R.string.offline_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry, Modifier.widthIn(min = 200.dp).height(52.dp)) {
            Text(stringResource(R.string.try_again), style = MaterialTheme.typography.titleMedium)
        }
    }
}
