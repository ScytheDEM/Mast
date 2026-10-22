package au.signalatlas.app.ui.updates

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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.app.ui.map.DataSummary
import au.signalatlas.app.ui.map.isWaiting
import au.signalatlas.app.ui.map.overallFraction
import au.signalatlas.app.ui.map.runningLabel
import au.signalatlas.core.update.UpdateStatus
import java.text.NumberFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** Installed data date and counts, plus manual check / update. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(
    data: DataSummary?,
    status: UpdateStatus,
    network: () -> NetworkKind,
    onCheck: () -> Unit,
    onUpdateNow: (allowMetered: Boolean) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gate = rememberDownloadGate(network, onUpdateNow)
    gate.Dialog(downloadSizeLabel(status))
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.updates_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        if (data == null) {
                            InfoRow(stringResource(R.string.updates_data_date), stringResource(R.string.no_data))
                        } else {
                            val n = NumberFormat.getIntegerInstance(Locale.getDefault())
                            InfoRow(stringResource(R.string.updates_data_date), formatStamp(data.extractStamp))
                            Divider()
                            InfoRow(stringResource(R.string.updates_sites), n.format(data.sites))
                            Divider()
                            InfoRow(stringResource(R.string.updates_sectors), n.format(data.sectors))
                            Divider()
                            InfoRow(stringResource(R.string.updates_carriers), n.format(data.carriers))
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                // A fresh "Update now" with no network trumps an older status.
                StatusLine(if (gate.offline && status !is UpdateStatus.Running) UpdateStatus.Offline else status)
                Spacer(Modifier.height(20.dp))
                val running = status is UpdateStatus.Running
                if (status is UpdateStatus.Running && status.isWaiting()) {
                    WaitingActions(onUseMobileData = { onUpdateNow(true) }, onCancel = onCancel)
                    Spacer(Modifier.height(12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onCheck,
                        enabled = !running && status != UpdateStatus.Checking,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text(stringResource(R.string.updates_check)) }
                    Button(
                        onClick = gate::request,
                        enabled = !running,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text(stringResource(R.string.updates_update_now)) }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.updates_source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun StatusLine(status: UpdateStatus) {
    Column(Modifier.fillMaxWidth()) {
        val (text, isError) = when (status) {
            UpdateStatus.Unknown -> stringResource(R.string.status_unknown) to false
            UpdateStatus.Checking -> stringResource(R.string.status_checking) to false
            is UpdateStatus.UpToDate -> stringResource(R.string.status_up_to_date) to false
            is UpdateStatus.Available -> stringResource(R.string.status_available, formatStamp(status.info.extractStamp)) to false
            is UpdateStatus.Running -> runningLabel(status) to false
            is UpdateStatus.Failed -> status.message to true
            UpdateStatus.Offline -> stringResource(R.string.status_offline) to true
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (status is UpdateStatus.Running) {
            Spacer(Modifier.height(10.dp))
            if (status.isWaiting()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { status.overallFraction() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

private val SYDNEY: ZoneId = ZoneId.of("Australia/Sydney")
private val STAMP_IN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
private val STAMP_OUT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.forLanguageTag("en-AU"))

/**
 * ACMA's `yyyyMMddHHmm` extract stamp, which is Sydney wall-clock time, as "22 Oct 2026, 5:00 am".
 * Falls back to the raw stamp if it does not parse.
 */
fun formatStamp(stamp: String): String = try {
    LocalDateTime.parse(stamp, STAMP_IN).atZone(SYDNEY).format(STAMP_OUT)
} catch (e: DateTimeParseException) {
    stamp
}
