package au.signalatlas.app.ui.updates

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import au.signalatlas.app.R
import au.signalatlas.core.update.UpdateStatus
import kotlin.math.roundToInt

/** "about 68 MB", from ACMA's Content-Length when a check has seen it. */
@Composable
fun downloadSizeLabel(status: UpdateStatus): String {
    val bytes = (status as? UpdateStatus.Available)?.info?.sizeBytes
    return if (bytes != null && bytes > 0) {
        stringResource(R.string.size_about_mb, (bytes / 1_000_000.0).roundToInt().coerceAtLeast(1))
    } else {
        stringResource(R.string.size_default)
    }
}

/**
 * Starts a manual download. On a metered network it first asks "Use mobile data for …?";
 * "Wait for Wi-Fi" queues the download for an unmetered network instead.
 *
 * Call [DownloadGate.request] from a button and place [DownloadGate.Dialog] in the composition.
 */
class DownloadGate internal constructor(
    private val isMetered: () -> Boolean,
    private val onDownload: (allowMetered: Boolean) -> Unit,
    private val asking: () -> Boolean,
    private val setAsking: (Boolean) -> Unit,
) {
    fun request() {
        if (isMetered()) setAsking(true) else onDownload(false)
    }

    @Composable
    fun Dialog(sizeLabel: String) {
        if (!asking()) return
        AlertDialog(
            onDismissRequest = { setAsking(false) },
            title = { Text(stringResource(R.string.metered_title, sizeLabel)) },
            text = { Text(stringResource(R.string.metered_body)) },
            confirmButton = {
                TextButton(onClick = { setAsking(false); onDownload(true) }) { Text(stringResource(R.string.metered_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { setAsking(false); onDownload(false) }) { Text(stringResource(R.string.metered_wait)) }
            },
        )
    }
}

@Composable
fun rememberDownloadGate(isMetered: () -> Boolean, onDownload: (allowMetered: Boolean) -> Unit): DownloadGate {
    var asking by rememberSaveable { mutableStateOf(false) }
    return DownloadGate(isMetered, onDownload, { asking }, { asking = it })
}
