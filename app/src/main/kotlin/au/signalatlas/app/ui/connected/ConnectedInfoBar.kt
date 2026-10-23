package au.signalatlas.app.ui.connected

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.app.connected.ConnectedStatus
import au.signalatlas.app.connected.ConnectedUi
import au.signalatlas.core.calibration.Confidence
import au.signalatlas.core.model.Network
import java.util.Locale

@Composable
fun networkName(n: Network): String = stringResource(networkNameRes(n))

private fun networkNameRes(n: Network) = when (n) {
    Network.TELSTRA -> R.string.network_telstra
    Network.OPTUS -> R.string.network_optus
    Network.TPG -> R.string.network_tpg
    Network.OPTUS_TPG_JV -> R.string.network_optus_tpg_jv
    Network.NBN -> R.string.network_nbn
}

/**
 * The info bar's one line, e.g. "Telstra 5G · B28 + n78 · −94 dBm · tower 1.3 km NE · Likely · ≈ 85 Mbps", or the reason
 * there is nothing to show ("Allow phone access to see your tower").
 */
fun connectedText(ui: ConnectedUi, res: Resources): String = when (ui.status) {
    ConnectedStatus.NEEDS_PHONE -> res.getString(R.string.connected_needs_phone)
    ConnectedStatus.NEEDS_LOCATION -> res.getString(R.string.connected_needs_location)
    ConnectedStatus.AIRPLANE -> res.getString(R.string.connected_airplane)
    ConnectedStatus.NO_SIM -> res.getString(R.string.connected_no_sim)
    ConnectedStatus.ROAMING -> res.getString(R.string.connected_roaming, carrierOf(ui, res))
    ConnectedStatus.SEARCHING -> res.getString(R.string.connected_searching)
    ConnectedStatus.UNTRACKED -> res.getString(R.string.connected_untracked, carrierOf(ui, res))
    ConnectedStatus.CONNECTED -> listOfNotNull(
        res.getString(R.string.connected_carrier_tech, carrierOf(ui, res), res.getString(if (ui.fiveG) R.string.tech_5g else R.string.tech_4g)),
        ui.bands,
        ui.rsrpDbm?.let { res.getString(R.string.connected_rsrp, it.toString().replace('-', '−')) },
        ui.tower?.let { res.getString(R.string.connected_tower, distance(it.distanceM), it.compass) },
        ui.confidence?.let { res.getString(confidenceRes(it)) },
        ui.speedMbps?.let { res.getString(R.string.connected_speed, it) },
    ).joinToString(" · ")
}

private fun carrierOf(ui: ConnectedUi, res: Resources): String =
    ui.carrier ?: ui.network?.let { res.getString(networkNameRes(it)) } ?: res.getString(R.string.connected_unknown_carrier)

private fun confidenceRes(c: Confidence) = when (c) {
    Confidence.UNCERTAIN -> R.string.connected_uncertain
    Confidence.LIKELY -> R.string.connected_likely
    Confidence.CONFIRMED -> R.string.connected_confirmed
}

/** "850 m", "1.3 km", "12 km". */
internal fun distance(m: Double): String = when {
    m < 1000 -> String.format(Locale.getDefault(), "%d m", (m / 10).toInt() * 10)
    m < 10_000 -> String.format(Locale.getDefault(), "%.1f km", m / 1000)
    else -> String.format(Locale.getDefault(), "%d km", (m / 1000).toInt())
}

/** The bar at the bottom of the map. Tapping it opens the tower's site sheet, or asks for the missing permission. */
@Composable
fun ConnectedInfoBar(ui: ConnectedUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val text = connectedText(ui, LocalContext.current.resources)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth().testTag("connected-bar"),
    ) {
        Row(Modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
