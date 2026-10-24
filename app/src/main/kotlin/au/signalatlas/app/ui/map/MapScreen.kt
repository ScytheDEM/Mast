package au.signalatlas.app.ui.map

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneExpansionAnchor
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculateThreePaneScaffoldValue
import androidx.compose.material3.adaptive.layout.rememberPaneExpansionState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import au.signalatlas.app.R
import au.signalatlas.app.connected.ConnectedStatus
import au.signalatlas.app.connected.ConnectedUi
import au.signalatlas.app.ui.connected.ConnectedInfoBar
import au.signalatlas.app.location.DeviceLocationSource
import au.signalatlas.app.ui.coverage.AccuracyChip
import au.signalatlas.app.ui.coverage.CoverageActions
import au.signalatlas.app.ui.coverage.CoverageControlsCard
import au.signalatlas.app.ui.coverage.CoverageLegend
import au.signalatlas.app.ui.coverage.CoverageToolbar
import au.signalatlas.app.ui.coverage.IncreaseResolutionSheet
import au.signalatlas.app.ui.coverage.PointReadout
import au.signalatlas.app.ui.nearby.NearbyContent
import au.signalatlas.core.map.MapCameraState
import au.signalatlas.core.map.TowerMap
import au.signalatlas.core.model.SiteSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps the camera across activity recreation (e.g. a dark-mode switch). */
class MapCameraHolder : ViewModel() {
    val camera = MapCameraState()
}

private enum class Pane { None, Site, Nearby }

/** What the connected-tower feature needs from the map screen's lifecycle and permission flow. */
class ConnectedActions(
    val start: () -> Unit,
    val stop: () -> Unit,
    val onPermissionsChanged: () -> Unit,
    val offerRationaleOnce: () -> Boolean,
) {
    companion object {
        val None = ConnectedActions({}, {}, {}, { false })
    }
}

/** Room the bottom info bar takes: bar height plus its margin. */
private val CONNECTED_BAR_INSET = 60.dp

private const val SITE_ZOOM = 14.0

/**
 * The map fills the window edge to edge and is always the first child of the root [Box], so its
 * MapView survives switching between the compact (bottom sheet) and expanded (side pane)
 * layouts when a foldable is folded or unfolded.
 */
@Composable
fun MapScreen(
    state: MapUiState,
    vm: MapViewModel,
    location: DeviceLocationSource,
    onOpenUpdates: () -> Unit,
    /** False only in tests: MapLibre needs a real device. */
    renderMap: Boolean = true,
    /** The connected tower (info bar and map highlight); null = the feature is not wired (tests). */
    connected: ConnectedUi? = null,
    connectedActions: ConnectedActions = ConnectedActions.None,
) {
    val camera = viewModel<MapCameraHolder>().camera
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val expanded = currentWindowAdaptiveInfo().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    var showNearby by rememberSaveable { mutableStateOf(false) }
    var showBands by rememberSaveable { mutableStateOf(false) }
    // Not saved: a fold/unfold or recreation must never bring back an expanded (focused) search.
    var searchExpanded by remember { mutableStateOf(false) }
    val windowClass = currentWindowAdaptiveInfo().windowSizeClass
    CollapseSearchOnWindowChange(windowClass.minWidthDp to windowClass.minHeightDp) { searchExpanded = false }
    var coverageOptionsOpen by rememberSaveable { mutableStateOf(true) }
    var rationaleFor by remember { mutableStateOf<(() -> Unit)?>(null) }
    var afterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }

    var phoneRationale by remember { mutableStateOf(false) }

    val pane = when {
        state.selected != null -> Pane.Site
        showNearby -> Pane.Nearby
        else -> Pane.None
    }

    // The camera may have been restored (recreation) before the map reports its first idle.
    LaunchedEffect(Unit) { vm.onZoom(camera.currentZoom) }

    // Permission may change in Settings while we are away.
    LifecycleResumeEffect(Unit) {
        vm.onLocationPermission(location.hasPermission())
        location.refresh()
        onPauseOrDispose { }
    }
    LifecycleStartEffect(state.locationGranted, state.locationEnabled) {
        if (state.locationGranted) location.start()
        onStopOrDispose { location.stop() }
    }

    // The connected tower reads the radio only while the map is on screen (no polling or logging in the background).
    LifecycleStartEffect(Unit) {
        connectedActions.start()
        onStopOrDispose { connectedActions.stop() }
    }
    // The explanation is volunteered once, the first time the bar finds the permission missing; after that on tap.
    LaunchedEffect(connected?.status) {
        val s = connected?.status
        if ((s == ConnectedStatus.NEEDS_PHONE || s == ConnectedStatus.NEEDS_LOCATION) && connectedActions.offerRationaleOnce()) {
            phoneRationale = true
        }
    }
    val phoneDeniedMessage = stringResource(R.string.phone_denied)
    val phoneSettingsLabel = stringResource(R.string.settings)
    val phoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        vm.onLocationPermission(location.hasPermission())
        if (result[Manifest.permission.READ_PHONE_STATE] == true || result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            connectedActions.onPermissionsChanged()
        }
        if (result.values.any { !it }) {
            scope.launch {
                val r = snackbar.showSnackbar(phoneDeniedMessage, actionLabel = phoneSettingsLabel, withDismissAction = true)
                if (r == SnackbarResult.ActionPerformed) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }
    val onConnectedBar: () -> Unit = {
        val s = connected
        when {
            s == null -> Unit
            s.status == ConnectedStatus.NEEDS_PHONE || s.status == ConnectedStatus.NEEDS_LOCATION -> phoneRationale = true
            else -> s.tower?.siteId?.let { id ->
                s.highlight?.serving?.let { (lat, lon) -> camera.animateTo(lat, lon, SITE_ZOOM) }
                vm.selectSite(id)
            }
        }
    }

    val deniedMessage = stringResource(R.string.location_denied)
    val settingsLabel = stringResource(R.string.settings)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.values.any { it }
        vm.onLocationPermission(granted)
        if (granted) {
            afterGrant?.invoke()
        } else {
            scope.launch {
                val r = snackbar.showSnackbar(deniedMessage, actionLabel = settingsLabel, withDismissAction = true)
                if (r == SnackbarResult.ActionPerformed) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
        afterGrant = null
    }

    val locationOffMessage = stringResource(R.string.location_off)
    val locationSettingsLabel = stringResource(R.string.location_settings)
    val openLocationSettings = {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Runs [action] with location permission, explaining first and asking if needed. */
    fun withLocation(action: () -> Unit) {
        if (location.hasPermission()) {
            vm.onLocationPermission(true)
            action()
        } else {
            rationaleFor = action
        }
    }

    val onMyLocation = {
        withLocation {
            location.refresh()
            if (!location.enabled.value) {
                // Location services off: no fix will come, so say so instead of waiting forever.
                scope.launch {
                    val r = snackbar.showSnackbar(locationOffMessage, actionLabel = locationSettingsLabel, withDismissAction = true)
                    if (r == SnackbarResult.ActionPerformed) openLocationSettings()
                }
            } else {
                // Only a fix from the last couple of minutes centres at once; otherwise ask for a new one.
                location.freshFix()?.let { (lat, lon) -> camera.animateTo(lat, lon, SITE_ZOOM) }
                scope.launch {
                    val fix = location.currentFix() ?: return@launch
                    camera.animateTo(fix.first, fix.second, SITE_ZOOM)
                }
            }
        }
    }
    val onNearby = {
        withLocation { scope.launch { location.currentFix() } }
        showNearby = true
    }
    val openSite = { s: SiteSummary ->
        // Keyboard and focus go first, so the closing keyboard cannot disturb the sheet opening.
        keyboard?.hide()
        focusManager.clearFocus()
        searchExpanded = false
        camera.animateTo(s.lat, s.lon, SITE_ZOOM)
        vm.selectSite(s.id)
    }

    val coverageActions = CoverageActions(
        onToggle = { on ->
            if (on) coverageOptionsOpen = true
            vm.setCoverageOn(on)
        },
        onOptions = { coverageOptionsOpen = !coverageOptionsOpen },
        onNetwork = vm::setCoverageNetwork,
        onFiveGOnly = vm::setCoverageFiveGOnly,
        onIndoor = vm::setCoverageIndoor,
        onView = vm::setCoverageView,
    )

    BackHandler(enabled = pane != Pane.None && !searchExpanded) {
        if (pane == Pane.Site) vm.selectSite(null) else showNearby = false
    }

    Box(Modifier.fillMaxSize()) {
        // 1. The map: always first, always full size.
        if (renderMap && state.basemapResolved) {
            TowerMap(
                sites = state.sites,
                selectedSiteId = state.selectedId,
                dark = isSystemInDarkTheme(),
                basemapKey = state.basemapKey,
                camera = camera,
                onSiteClick = { vm.selectSite(it) },
                onCameraIdle = { },
                coverageUrlTemplate = state.coverage.urlTemplate.takeIf { state.coverage.on },
                coverageView = state.coverage.view,
                onVisibleTiles = vm::onVisibleTiles,
                onZoom = vm::onZoom,
                onLongPress = vm::longPress,
                onMapTap = vm::onMapTap,
                selectedColor = MaterialTheme.colorScheme.primary.toArgb(),
                modifier = Modifier.fillMaxSize(),
                userLocation = state.location.takeIf { state.locationGranted },
                userLocationColor = MaterialTheme.colorScheme.primary.toArgb(),
                connected = connected?.highlight,
            )
        }

        // 2. Controls and detail content around it.
        val controls: @Composable (Boolean) -> Unit = { docked ->
            MapControls(
                state = state,
                docked = docked,
                searchExpanded = searchExpanded,
                onSearchExpandedChange = { searchExpanded = it },
                onQueryChange = vm::search,
                onResultClick = openSite,
                onFilter = vm::setFilter,
                onOpenBands = { showBands = true },
                onOpenUpdates = onOpenUpdates,
                onMyLocation = onMyLocation,
                onNearby = onNearby,
                onIncrease = vm::openIncreaseResolution,
                coverageOptionsOpen = coverageOptionsOpen,
                coverage = coverageActions,
                snackbar = snackbar,
                connected = connected,
                onConnectedBar = onConnectedBar,
            )
        }
        val paneContent: @Composable (Pane) -> Unit = { p ->
            when (p) {
                Pane.Site -> state.selected?.let { SiteSheetContent(it, onClose = { vm.selectSite(null) }) }
                Pane.Nearby -> NearbyContent(
                    locationGranted = state.locationGranted,
                    locationEnabled = state.locationEnabled,
                    hasLocation = state.location != null,
                    onOpenLocationSettings = openLocationSettings,
                    nearby = state.nearby,
                    onSiteClick = openSite,
                    onRequestPermission = { withLocation { } },
                    onClose = { showNearby = false },
                )
                Pane.None -> Unit
            }
        }
        if (expanded) {
            ExpandedPanes(pane, controls, paneContent)
        } else {
            controls(false)
            CompactSheet(pane, onDismiss = { if (pane == Pane.Site) vm.selectSite(null) else showNearby = false }, paneContent)
        }
    }

    state.coverage.increase?.let {
        IncreaseResolutionSheet(
            it,
            onDownload = vm::downloadIncrease,
            onConfirmMetered = vm::confirmIncreaseOnMobileData,
            onDismiss = vm::dismissIncreaseResolution,
        )
    }
    if (showBands) {
        BandsSheet(state.filter, state.availableBands, onFilter = vm::setFilter, onDismiss = { showBands = false })
    }
    if (phoneRationale) {
        AlertDialog(
            onDismissRequest = { phoneRationale = false },
            title = { Text(stringResource(R.string.phone_rationale_title)) },
            text = { Text(stringResource(R.string.phone_rationale_body)) },
            confirmButton = {
                TextButton(onClick = {
                    phoneRationale = false
                    phoneLauncher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION))
                }) { Text(stringResource(R.string.phone_rationale_continue)) }
            },
            dismissButton = { TextButton(onClick = { phoneRationale = false }) { Text(stringResource(R.string.not_now)) } },
        )
    }
    rationaleFor?.let { action ->
        AlertDialog(
            onDismissRequest = { rationaleFor = null },
            title = { Text(stringResource(R.string.location_rationale_title)) },
            text = { Text(stringResource(R.string.location_rationale_body)) },
            confirmButton = {
                TextButton(onClick = {
                    rationaleFor = null
                    afterGrant = action
                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text(stringResource(R.string.location_continue)) }
            },
            dismissButton = { TextButton(onClick = { rationaleFor = null }) { Text(stringResource(R.string.not_now)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun ExpandedPanes(
    pane: Pane,
    controls: @Composable (docked: Boolean) -> Unit,
    paneContent: @Composable (Pane) -> Unit,
) {
    val base = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())
    // Without detail content the map controls get the whole width (one partition).
    val directive = if (pane == Pane.None) base.copy(maxHorizontalPartitions = 1) else base
    val value = calculateThreePaneScaffoldValue(
        maxHorizontalPartitions = directive.maxHorizontalPartitions,
        adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies(),
        currentDestination = ThreePaneScaffoldDestinationItem<Nothing>(
            if (pane == Pane.None) ListDetailPaneScaffoldRole.List else ListDetailPaneScaffoldRole.Detail,
        ),
    )
    // The side panel keeps a fixed, comfortable width; the map side takes the rest.
    val expansion = rememberPaneExpansionState(anchors = listOf(PaneExpansionAnchor.Offset.fromEnd(SIDE_PANEL_WIDTH)), initialAnchoredIndex = 0)
    // Remember the last content so the pane can animate out with it.
    var shown by remember { mutableStateOf(pane) }
    if (pane != Pane.None) shown = pane

    ListDetailPaneScaffold(
        directive = directive,
        value = value,
        paneExpansionState = expansion,
        listPane = { AnimatedPane { controls(true) } },
        detailPane = {
            AnimatedPane {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = MaterialTheme.shapes.extraLarge,
                    shadowElevation = 6.dp,
                    modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(top = 12.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    Box(Modifier.padding(top = 12.dp)) { paneContent(shown) }
                }
            }
        },
    )
}

private val SIDE_PANEL_WIDTH = 400.dp

/**
 * The compact layout's modal sheet. It opens only once the keyboard is gone (a keyboard closing
 * mid-animation changes the sheet's anchors and could cancel the opening, leaving an invisible
 * scrim that eats taps), re-opens if an opening is cancelled anyway, and animates closed when
 * [pane] goes to [Pane.None] from outside (close button, back, site gone).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CompactSheet(pane: Pane, onDismiss: () -> Unit, paneContent: @Composable (Pane) -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    var visible by remember { mutableStateOf(false) }
    // The last content, so the sheet can animate out with it.
    var shown by remember { mutableStateOf(pane) }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(pane, imeVisible) {
        when {
            pane != Pane.None && !imeVisible -> {
                shown = pane
                if (visible) sheetState.show() else visible = true
            }
            pane == Pane.None && visible -> {
                sheetState.hide()
                visible = false
            }
        }
    }
    if (!visible) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.18f),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding()) { paneContent(shown) }
    }
    EnsureOpened(sheetState, wanted = pane != Pane.None)
}

/**
 * Re-opens a sheet whose opening animation was cancelled before it ever showed. Once it has been
 * open, a later Hidden is the user dismissing it and is left alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnsureOpened(sheetState: SheetState, wanted: Boolean) {
    val currentlyWanted by rememberUpdatedState(wanted)
    LaunchedEffect(sheetState) {
        var opening = false
        var opened = false
        var retries = 0
        snapshotFlow { sheetState.currentValue to sheetState.targetValue }.collect { (current, target) ->
            if (target != SheetValue.Hidden) opening = true
            if (current != SheetValue.Hidden) {
                opened = true
            } else if (target == SheetValue.Hidden && opening && !opened && currentlyWanted && retries < MAX_OPEN_RETRIES) {
                // The opening began and settled back at Hidden without ever showing: try again.
                retries++
                try {
                    sheetState.show()
                } catch (e: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw e // else interrupted again; the next emission retries
                }
            }
        }
    }
}

private const val MAX_OPEN_RETRIES = 3

@Composable
private fun MapControls(
    state: MapUiState,
    docked: Boolean,
    searchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    onResultClick: (SiteSummary) -> Unit,
    onFilter: (au.signalatlas.core.model.TowerFilter) -> Unit,
    onOpenBands: () -> Unit,
    onOpenUpdates: () -> Unit,
    onMyLocation: () -> Unit,
    onNearby: () -> Unit,
    onIncrease: () -> Unit,
    coverageOptionsOpen: Boolean,
    coverage: CoverageActions,
    snackbar: SnackbarHostState,
    connected: ConnectedUi?,
    onConnectedBar: () -> Unit,
) {
    // The info bar sits along the bottom; the buttons above it move up by its height.
    val barInset = if (connected != null) CONNECTED_BAR_INSET else 0.dp
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth()) {
            if (docked) {
                TowerSearchBar(
                    search = state.search,
                    expanded = searchExpanded, onExpandedChange = onSearchExpandedChange,
                    onQueryChange = onQueryChange, onResultClick = onResultClick, onOpenUpdates = onOpenUpdates,
                    docked = true,
                    modifier = Modifier.statusBarsPadding().padding(start = 16.dp, top = 12.dp).widthIn(max = 420.dp),
                )
            } else {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    TowerSearchBar(
                        search = state.search,
                        expanded = searchExpanded, onExpandedChange = onSearchExpandedChange,
                        onQueryChange = onQueryChange, onResultClick = onResultClick, onOpenUpdates = onOpenUpdates,
                        docked = false,
                    )
                }
            }
            if (!searchExpanded || docked) {
                Spacer(Modifier.height(10.dp))
                FilterChipRow(state.filter, onFilter, onOpenBands)
                AnimatedVisibility(state.update.showsChip(), enter = fadeIn(), exit = fadeOut()) {
                    UpdateChip(state.update, onClick = onOpenUpdates, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
                }
                AnimatedVisibility(state.coverage.needsZoom(state.zoom), enter = fadeIn(), exit = fadeOut()) {
                    // A label, not a chip: nothing to press (and nothing for TalkBack to offer).
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                    ) {
                        Text(
                            stringResource(R.string.coverage_zoom_in),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                AnimatedVisibility(state.coverage.on && !state.coverage.needsZoom(state.zoom), enter = fadeIn(), exit = fadeOut()) {
                    AccuracyChip(state.coverage.accuracy, onClick = onIncrease, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
                }
                state.coverage.readout?.let {
                    PointReadout(it.result, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + barInset),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SmallFloatingActionButton(
                onClick = onMyLocation,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                contentColor = MaterialTheme.colorScheme.primary,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp),
            ) { Icon(painterResource(R.drawable.ic_my_location), stringResource(R.string.fab_my_location)) }
            FloatingActionButton(
                onClick = onNearby,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp),
            ) { Icon(painterResource(R.drawable.ic_near_me), stringResource(R.string.fab_nearby)) }
        }
        Column(
            Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + barInset),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.coverage.on) {
                AnimatedVisibility(coverageOptionsOpen, enter = fadeIn(), exit = fadeOut()) {
                    CoverageControlsCard(state.coverage, coverage)
                }
                CoverageLegend(state.coverage.view)
            }
            CoverageToolbar(
                on = state.coverage.on,
                optionsOpen = coverageOptionsOpen,
                actions = coverage,
            )
        }
        if (connected != null) {
            ConnectedInfoBar(
                connected, onClick = onConnectedBar,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp + barInset))
    }
}
