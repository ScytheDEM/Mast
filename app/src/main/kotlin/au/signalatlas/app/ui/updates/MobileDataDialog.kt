package au.signalatlas.app.ui.updates

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

/** What the active network allows a manual download to do right now. */
enum class NetworkKind { Offline, Metered, Unmetered }

/** No active network, or one without internet, counts as offline (not as "metered"). */
fun Context.currentNetworkKind(): NetworkKind {
    val cm = getSystemService(ConnectivityManager::class.java) ?: return NetworkKind.Offline
    val network = cm.activeNetwork ?: return NetworkKind.Offline
    val caps = cm.getNetworkCapabilities(network) ?: return NetworkKind.Offline
    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkKind.Offline
    // "Treat mobile data as unmetered": no "Use mobile data?" question.
    return if (cm.isActiveNetworkMetered && !au.signalatlas.core.model.DataSettings.mobileDataUnmetered) NetworkKind.Metered else NetworkKind.Unmetered
}

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
 * Starts a manual download according to the network:
 * - offline: nothing is queued; [offline] becomes true so the screen shows the offline message;
 * - metered: first asks "Use mobile data for …?"; "Wait for Wi-Fi" queues it for Wi-Fi instead;
 * - unmetered: downloads at once (Wi-Fi only).
 *
 * Call [request] from a button and place [Dialog] in the composition.
 */
class DownloadGate internal constructor(
    private val network: () -> NetworkKind,
    private val onDownload: (allowMetered: Boolean) -> Unit,
    private val asking: () -> Boolean,
    private val setAsking: (Boolean) -> Unit,
    private val isOffline: () -> Boolean,
    private val setOffline: (Boolean) -> Unit,
) {
    /** The last request found no network. */
    val offline: Boolean get() = isOffline()

    fun request() {
        when (network()) {
            NetworkKind.Offline -> setOffline(true)
            NetworkKind.Metered -> { setOffline(false); setAsking(true) }
            NetworkKind.Unmetered -> { setOffline(false); onDownload(false) }
        }
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
fun rememberDownloadGate(network: () -> NetworkKind, onDownload: (allowMetered: Boolean) -> Unit): DownloadGate {
    var asking by rememberSaveable { mutableStateOf(false) }
    var offline by rememberSaveable { mutableStateOf(false) }
    return DownloadGate(network, onDownload, { asking }, { asking = it }, { offline }, { offline = it })
}

/** Ways out of a queued (waiting) download: go now on mobile data, or cancel it. */
@Composable
fun WaitingActions(onUseMobileData: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        Button(onClick = onUseMobileData) { Text(stringResource(R.string.use_mobile_data_now)) }
    }
}
