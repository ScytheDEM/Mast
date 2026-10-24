package au.signalatlas.app.ui.connected

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.app.connected.CalibrationHooks
import au.signalatlas.app.connected.ConnectedStatus
import au.signalatlas.app.connected.ConnectedUi
import au.signalatlas.core.calibration.Confidence
import au.signalatlas.core.map.NetworkColors
import au.signalatlas.core.radio.ObservedCell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Neighbours shown per SIM. */
private const val MAX_NEIGHBOURS = 10

/**
 * "Cellular details": one section per active SIM (data SIM first) with the serving cell, NR secondaries and the
 * neighbour cells the phone sees (strongest first with band chips), the matched tower, measured versus predicted signal,
 * and how many samples are improving the predictions for that tower. Used in the compact sheet and the side pane.
 */
@Composable
fun CellularDetailsContent(
    ui: ConnectedUi,
    calibration: CalibrationHooks,
    onOpenTower: (siteId: Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp).testTag("cellular-details")) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.cellular_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.cellular_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
        }
        ui.allSims.forEachIndexed { i, sim -> SimSection(sim, i, calibration, onOpenTower) }
        CalibrationSwitch(calibration)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimSection(sim: ConnectedUi, index: Int, calibration: CalibrationHooks, onOpenTower: (Int) -> Unit) {
    val res = LocalContext.current.resources
    val accent = sim.network?.let { Color(NetworkColors.argb(it)) } ?: MaterialTheme.colorScheme.outline
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp).testTag("cellular-sim-$index"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = accent, shape = MaterialTheme.shapes.small, modifier = Modifier.size(width = 5.dp, height = 28.dp)) { }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(simName(sim, res, index), style = MaterialTheme.typography.titleMedium)
                    if (sim.isData) {
                        Text(
                            stringResource(R.string.cellular_data_sim),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                val cell = sim.cells
                if (cell != null) {
                    TechBadge(sim.fiveG, large = true)
                    Spacer(Modifier.width(10.dp))
                    SignalBars(signalBars(sim.rsrpDbm), rsrpColor(sim.rsrpDbm), Modifier.size(26.dp))
                }
            }
            val cells = sim.cells
            if (cells == null) {
                Text(
                    connectedText(sim, res).takeIf { sim.status != ConnectedStatus.CONNECTED } ?: stringResource(R.string.cellular_not_available),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                return@Column
            }
            if (sim.bandList.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    sim.bandList.forEach { BandChip(it) }
                }
            }

            SectionLabel(R.string.cellular_serving)
            cellRows(cells.serving, res).forEach { (k, v) -> KeyValue(k, v, valueColor = if (k == res.getString(R.string.cellular_rsrp)) rsrpColor(cells.serving.rsrp) else null) }

            if (cells.nrSecondaries.isNotEmpty()) {
                SectionLabel(R.string.cellular_secondary)
                cells.nrSecondaries.forEach { CellLine(it) }
            }

            SectionLabel(R.string.cellular_neighbours)
            if (cells.neighbours.isEmpty()) {
                Text(stringResource(R.string.cellular_no_neighbours), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            cells.neighbours.take(MAX_NEIGHBOURS).forEach { CellLine(it) }

            sim.tower?.let { tower ->
                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SectionLabel(R.string.cellular_tower, first = true)
                Text(
                    if (tower.siteId != null) {
                        stringResource(R.string.cellular_tower_value, tower.siteId, distance(tower.distanceM), tower.compass, sim.confidence?.let { confidenceLabel(it) } ?: "")
                    } else {
                        stringResource(R.string.cellular_tower_unknown)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                measuredVsPredicted(res, sim.rsrpDbm, sim.predictedDbm)?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp).testTag("measured-vs-predicted-$index"))
                    predictionDelta(res, sim.rsrpDbm, sim.predictedDbm)?.let { d ->
                        Text(d, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                tower.sectorIdent?.let { ident -> SamplesLine(ident, calibration) }
                tower.siteId?.let { id ->
                    FilledTonalButton(onClick = { onOpenTower(id) }, modifier = Modifier.padding(top = 10.dp)) {
                        Text(stringResource(R.string.cellular_open_tower))
                    }
                }
            }
        }
    }
}

@Composable
private fun confidenceLabel(c: Confidence): String = stringResource(
    when (c) {
        Confidence.UNCERTAIN -> R.string.connected_uncertain
        Confidence.LIKELY -> R.string.connected_likely
        Confidence.CONFIRMED -> R.string.connected_confirmed
    },
)

@Composable
private fun SamplesLine(ident: String, calibration: CalibrationHooks) {
    val enabled by calibration.enabled.collectAsState()
    val samples by produceState<Int?>(null, ident, enabled) { value = withContext(Dispatchers.IO) { calibration.samplesFor(ident) } }
    val text = when {
        !enabled -> stringResource(R.string.cellular_calibration_off)
        samples == null -> return
        samples == 0 -> stringResource(R.string.cellular_samples_none)
        else -> pluralStringResource(R.plurals.cellular_samples, samples!!, samples!!)
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp).testTag("calibration-status"))
}

@Composable
private fun CalibrationSwitch(calibration: CalibrationHooks) {
    val enabled by calibration.enabled.collectAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.cellular_calibration_switch), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = calibration.setEnabled, modifier = Modifier.testTag("calibration-switch"))
    }
}

@Composable
private fun SectionLabel(res: Int, first: Boolean = false) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = if (first) 0.dp else 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun KeyValue(key: String, value: String, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CellLine(c: ObservedCell) {
    val res = LocalContext.current.resources
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        c.band?.let { BandChip(it); Spacer(Modifier.width(8.dp)) }
        Text(
            cellDetail(c, res),
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.weight(1f),
        )
        Text(
            c.rsrp?.let { dbmNumber(it) } ?: "–",
            style = MaterialTheme.typography.labelLarge,
            color = rsrpColor(c.rsrp),
        )
    }
}
