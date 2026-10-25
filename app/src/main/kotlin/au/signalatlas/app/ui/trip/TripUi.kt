package au.signalatlas.app.ui.trip

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.signalatlas.app.data.BeaconSettings
import au.signalatlas.core.beacons.bleScanPermissions
import au.signalatlas.core.beacons.hasBleScanPermission
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.feature.trip.TechFloor
import au.signalatlas.feature.trip.TripConfig
import au.signalatlas.feature.trip.TripState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the map screen needs of the trip: its state and the start/stop button. */
class TripUi(val state: StateFlow<TripState>, val onToggle: () -> Unit) {
    companion object {
        val None = TripUi(MutableStateFlow(TripState.Idle), {})
    }
}

/** "Trip · Telstra 4G · next dead zone in 6.2 km" along the top while a trip runs. */
@Composable
fun TripBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 3.dp,
        modifier = modifier.testTag("trip-banner"),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), maxLines = 2)
    }
}

/** Settings → Trip alerts: warning window, minimum dead zone, what counts as losing coverage, voice. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripSettingsScreen(
    config: TripConfig,
    onConfig: ((TripConfig) -> TripConfig) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Tunnel beacon settings; null hides the section (tests). */
    beacons: BeaconSettings? = null,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trip_settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section(stringResource(R.string.trip_window)) {
                Choice(TripConfig.WINDOWS, config.windowSeconds, "trip-window", { onConfig { c -> c.copy(windowSeconds = it) } }) {
                    stringResource(R.string.trip_seconds, it)
                }
            }
            Section(stringResource(R.string.trip_min_dead)) {
                Choice(TripConfig.MIN_DEAD_ZONES, config.minDeadZoneM, "trip-min-dead", { onConfig { c -> c.copy(minDeadZoneM = it) } }) {
                    stringResource(R.string.trip_metres, it)
                }
            }
            Section(stringResource(R.string.trip_floor)) {
                Choice(TechFloor.entries.reversed(), config.floor, "trip-floor", { onConfig { c -> c.copy(floor = it) } }) {
                    stringResource(
                        when (it) {
                            TechFloor.LOSE_5G -> R.string.trip_floor_5g
                            TechFloor.LOSE_4G -> R.string.trip_floor_4g
                            TechFloor.LOSE_ALL -> R.string.trip_floor_all
                        },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.trip_voice), Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = config.voice, onCheckedChange = { v -> onConfig { it.copy(voice = v) } }, modifier = Modifier.testTag("trip-voice"))
            }
            if (beacons != null) BeaconSection(beacons)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<T>, selected: T, tag: String, onSelect: (T) -> Unit, label: @Composable (T) -> String) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag(tag)) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
                label = { Text(label(option), maxLines = 1) },
            )
        }
    }
}

/** Settings: "Use Bluetooth beacons in tunnels" (asks for BLUETOOTH_SCAN + location only when turned on) and the CSV import. */
@Composable
private fun BeaconSection(beacons: BeaconSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by beacons.enabled.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var count by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { count = beacons.count() }
    val denied = stringResource(R.string.beacons_permission_denied)
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.isNotEmpty() && granted.values.all { it }) {
            beacons.setEnabled(true)
            message = null
        } else {
            message = denied
        }
    }
    val importedFmt = stringResource(R.string.beacons_imported)
    val failed = stringResource(R.string.beacons_import_failed)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = beacons.importCsv(uri)
            message = if (r == null) failed else String.format(importedFmt, r.imported, r.skipped)
            count = beacons.count()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.beacons_title), Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    if (!on) {
                        beacons.setEnabled(false)
                    } else if (hasBleScanPermission(context)) {
                        beacons.setEnabled(true)
                    } else {
                        permissions.launch(bleScanPermissions())
                    }
                },
                modifier = Modifier.testTag("beacons-enabled"),
            )
        }
        Text(stringResource(R.string.beacons_summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        count?.let { Text(stringResource(R.string.beacons_known, it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("beacons-count")) }
        OutlinedButton(
            onClick = { picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv", "application/vnd.ms-excel")) },
            modifier = Modifier.testTag("beacons-import"),
        ) { Text(stringResource(R.string.beacons_import)) }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("beacons-message")) }
    }
}
