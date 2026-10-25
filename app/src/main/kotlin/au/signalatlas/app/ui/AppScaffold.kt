package au.signalatlas.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import au.signalatlas.app.AppContainer
import au.signalatlas.app.connected.CalibrationHooks
import au.signalatlas.app.ui.firstrun.FirstRunScreen
import au.signalatlas.app.ui.calibration.CalibrationScreen
import au.signalatlas.app.ui.calibration.CalibrationViewModel
import au.signalatlas.app.ui.map.ConnectedActions
import au.signalatlas.app.ui.map.MapScreen
import au.signalatlas.app.ui.map.MapViewModel
import au.signalatlas.app.R
import au.signalatlas.app.ui.offline.DrawBoxHooks
import au.signalatlas.app.ui.offline.OfflineRegionsScreen
import au.signalatlas.app.ui.offline.OfflineViewModel
import au.signalatlas.app.ui.storage.StorageScreen
import au.signalatlas.app.ui.storage.StorageViewModel
import au.signalatlas.app.ui.updates.UpdatesScreen
import au.signalatlas.app.ui.trip.TripSettingsScreen
import au.signalatlas.app.ui.trip.TripUi
import au.signalatlas.app.ui.map.DasUi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import au.signalatlas.app.ui.updates.currentNetworkKind

private enum class Root { Loading, FirstRun, Map }

/** Top level: first-run gate, the map, and the updates screen pushed over the map. */
@Composable
fun AppScaffold(container: AppContainer) {
    val vm: MapViewModel = viewModel { container.mapViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showUpdates by rememberSaveable { mutableStateOf(false) }
    var showStorage by rememberSaveable { mutableStateOf(false) }
    var showOffline by rememberSaveable { mutableStateOf(false) }
    var drawing by rememberSaveable { mutableStateOf(false) }
    var showCalibration by rememberSaveable { mutableStateOf(false) }
    var showTrip by rememberSaveable { mutableStateOf(false) }
    val tripConfig by container.tripSettings.config.collectAsStateWithLifecycle()
    val tripUi = remember(container) {
        TripUi(container.trip.state) { if (container.trip.isRunning) container.trip.stop() else container.trip.start() }
    }
    val connected by container.connected.ui.collectAsStateWithLifecycle()
    val dasUi = remember(container) { DasUi(container.das.banner.map { it?.text }.stateIn(container.appScope, SharingStarted.Eagerly, null), container.das::dismiss) }
    val connectedActions = remember(container) {
        ConnectedActions(
            start = container.connected::start, stop = container.connected::stop,
            onPermissionsChanged = container.connected::onPermissionsChanged,
            offerRationaleOnce = container.calibrationSettings::offerRationaleOnce,
        )
    }
    val calibrationHooks = remember(container) {
        CalibrationHooks(
            enabled = container.calibrationSettings.enabled,
            samplesFor = { container.calibration.scatter(it).size },
            setEnabled = container.calibrationSettings::setEnabled,
        )
    }
    val calibrationVm: CalibrationViewModel = viewModel { container.calibrationViewModel() }
    val calibration by calibrationVm.state.collectAsStateWithLifecycle()
    val offlineVm: OfflineViewModel = viewModel { OfflineViewModel(container.packs) }
    val offline by offlineVm.state.collectAsStateWithLifecycle()
    val storageVm: StorageViewModel = viewModel { container.storageViewModel() }
    val storage by storageVm.state.collectAsStateWithLifecycle()

    val network = remember(context) { { context.currentNetworkKind() } }
    val askNotifications = rememberNotificationPermissionOnce()
    val updateNow = { allowMetered: Boolean ->
        askNotifications()
        vm.updateNow(allowMetered)
    }

    val root = when {
        state.hasData -> Root.Map
        state.loading -> Root.Loading
        else -> Root.FirstRun
    }

    Box(Modifier.fillMaxSize()) {
        Crossfade(root, label = "root") { r ->
            when (r) {
                // Same colour as the window background, so start-up shows no flash.
                Root.Loading -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { }
                Root.FirstRun -> FirstRunScreen(
                    status = state.update,
                    network = network,
                    onDownload = updateNow,
                    onCancel = vm::cancelUpdate,
                )
                Root.Map -> MapScreen(
                    state, vm, container.location, onOpenUpdates = { showUpdates = true },
                    connected = connected, connectedActions = connectedActions,
                    hints = container.hints,
                    calibration = calibrationHooks,
                    trip = tripUi,
                    onOpenOffline = { showOffline = true },
                    drawBox = DrawBoxHooks(drawing) { box ->
                        drawing = false
                        showOffline = true
                        box?.let { offlineVm.offerCustom(it, context.getString(R.string.offline_custom_name)) }
                    },
                    das = dasUi,
                )
            }
        }

        // Pushed over the map (instead of replacing it) so the MapView is not torn down.
        AnimatedVisibility(
            visible = showUpdates && root == Root.Map,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            UpdatesScreen(
                data = state.data,
                status = state.update,
                network = network,
                onCheck = vm::checkUpdates,
                onUpdateNow = updateNow,
                onCancel = vm::cancelUpdate,
                onBack = { showUpdates = false },
                patterns = state.patterns,
                onDownloadPatterns = vm::downloadPatternsNow,
                onOpenStorage = { showStorage = true },
                onOpenCalibration = { showCalibration = true },
                onOpenTrip = { showTrip = true },
            )
        }

        AnimatedVisibility(
            visible = showOffline && !drawing && root == Root.Map,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            OfflineRegionsScreen(
                state = offline,
                onOffer = offlineVm::offer,
                onDrawBox = { showOffline = false; drawing = true },
                onUpdate = { askNotifications(); offlineVm.update(it.id, it.allowMetered) },
                onDelete = offlineVm::delete,
                onMobileData = offlineVm::setAllowMetered,
                onDismissOffer = offlineVm::dismissOffer,
                onConfirmOffer = { askNotifications(); offlineVm.confirmOffer() },
                onBack = { showOffline = false },
            )
        }

        AnimatedVisibility(
            visible = showCalibration && showUpdates && root == Root.Map,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            CalibrationScreen(
                state = calibration,
                onEnabled = calibrationVm::setEnabled,
                onResetSector = { calibrationVm.resetSector(it) },
                onDeleteAll = { calibrationVm.deleteAll() },
                onExport = calibrationVm::exportCsv,
                onBack = { showCalibration = false },
            )
        }

        AnimatedVisibility(
            visible = showTrip && showUpdates && root == Root.Map,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            TripSettingsScreen(
                config = tripConfig, onConfig = container.tripSettings::update, onBack = { showTrip = false },
                beacons = container.beaconSettings,
            )
        }

        AnimatedVisibility(
            visible = showStorage && showUpdates && root == Root.Map,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            StorageScreen(
                state = storage,
                onRefresh = storageVm::refresh,
                onClearTerrain = storageVm::clearTerrain,
                onClearCoverageCache = storageVm::clearCoverageCache,
                onBudget = storageVm::setTerrainBudgetMib,
                onBack = { showStorage = false },
            )
        }
    }
    BackHandler(enabled = showUpdates && !showStorage && !showCalibration && !showTrip) { showUpdates = false }
    BackHandler(enabled = showTrip) { showTrip = false }
    BackHandler(enabled = showCalibration && !showTrip) { showCalibration = false }
    BackHandler(enabled = showStorage) { showStorage = false }
    BackHandler(enabled = drawing) { drawing = false; showOffline = true }
    BackHandler(enabled = showOffline && !drawing) { showOffline = false }
}

/**
 * POST_NOTIFICATIONS (API 33+) for the download's progress notification, asked the first time the
 * user starts a download. Optional: the download runs either way.
 */
@Composable
private fun rememberNotificationPermissionOnce(): () -> Unit {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
