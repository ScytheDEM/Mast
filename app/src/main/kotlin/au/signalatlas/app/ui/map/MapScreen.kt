package au.signalatlas.app.ui.map

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import au.signalatlas.app.R
import au.signalatlas.app.ui.offline.DrawBoxHooks
import au.signalatlas.app.ui.offline.DrawBoxOverlay
import au.signalatlas.app.connected.CalibrationHooks
import au.signalatlas.app.connected.ConnectedStatus
import au.signalatlas.app.connected.ConnectedUi
import au.signalatlas.app.connected.ServingStabiliser
import au.signalatlas.app.ui.connected.CellularDetailsContent
import au.signalatlas.app.ui.connected.ConnectedInfoBar
import au.signalatlas.app.data.UiHints
import au.signalatlas.app.location.DeviceLocationSource
import au.signalatlas.app.ui.coverage.CalculatingPill
import au.signalatlas.app.ui.coverage.CoverageExplainerCard
import au.signalatlas.app.ui.coverage.AccuracyCard
import au.signalatlas.app.ui.coverage.CoverageActions
import au.signalatlas.app.ui.coverage.CoverageControlsCard
import au.signalatlas.app.ui.coverage.CoverageLegend
import au.signalatlas.app.ui.coverage.CoverageToolbar
import au.signalatlas.app.ui.trip.TripBanner
import au.signalatlas.app.ui.trip.TripUi
import au.signalatlas.feature.trip.TripState
import au.signalatlas.app.ui.coverage.IncreaseResolutionSheet
import au.signalatlas.app.ui.coverage.PointReadout
import au.signalatlas.app.ui.nearby.NearbyContent
import au.signalatlas.core.update.UpdateStatus
import au.signalatlas.core.map.MapCameraState
import au.signalatlas.core.map.SectorBeams
import au.signalatlas.core.map.TowerMap
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.SiteSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps the camera across activity recreation (e.g. a dark-mode switch). */
class MapCameraHolder : ViewModel() {
    val camera = MapCameraState()
}

/** What the side pane (expanded) or a modal sheet (compact: filters and cellular only) shows. */
private enum class Pane { None, Site, Cellular, Filters, Nearby }

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

/** Room the bottom info bar takes: bar height plus its gap. */
private val CONNECTED_BAR_INSET = 100.dp

/** Further room per extra SIM row in the bar. */
private val CONNECTED_ROW_INSET = 41.dp

/** The floating navigation bar, its bottom margin and the gap above it. */
private val NAV_AREA = FLOATING_NAV_HEIGHT + FLOATING_NAV_MARGIN + 8.dp

/** The compact site sheet collapsed: drag handle plus the site's name, id line and network pills. */
private val SITE_PEEK = 176.dp

/** The compact site sheet open: at most this much, and never more than [SITE_SHEET_FRACTION] of the window. */
private val SITE_SHEET_MAX = 560.dp
private const val SITE_SHEET_FRACTION = 0.58f

private const val SITE_ZOOM = 14.0

/** Roughly the 10 km a tower's predicted coverage reaches. */
private const val COVERAGE_FIT_ZOOM = 12.0

/**
 * The map fills the window edge to edge and is always the first child of the root [Box], so its
 * MapView survives switching between the compact (bottom sheet) and expanded (side pane)
 * layouts when a foldable is folded or unfolded.
 *
 * A floating navigation bar switches between Home (towers; a tapped tower shows its sheet and sector lobes), Coverage
 * (the predicted-coverage layer and its controls, plus Trip) and Nearby (the nearest towers). The selected site stays
 * selected, its lobes on the map, until its tower is tapped again or the sheet's close button is pressed; on compact
 * windows its sheet opens at, and collapses to, a peek instead of closing.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    hints: UiHints = remember { UiHints.InMemory() },
    calibration: CalibrationHooks = CalibrationHooks.None,
    trip: TripUi = TripUi.None,
    onOpenOffline: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /** Draw-a-box mode for offline regions (see [DrawBoxOverlay]); null = off. */
    drawBox: DrawBoxHooks? = null,
    das: DasUi = DasUi.None,
) {
    val tripState by trip.state.collectAsStateWithLifecycle()
    val dasBanner by das.banner.collectAsStateWithLifecycle()
    val camera = viewModel<MapCameraHolder>().camera
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val expanded = currentWindowAdaptiveInfo().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showCellular by rememberSaveable { mutableStateOf(false) }
    // Not saved: a fold/unfold or recreation must never bring back an expanded (focused) search.
    var searchExpanded by remember { mutableStateOf(false) }
    val windowClass = currentWindowAdaptiveInfo().windowSizeClass
    CollapseSearchOnWindowChange(windowClass.minWidthDp to windowClass.minHeightDp) { searchExpanded = false }
    var explainerSeen by remember { mutableStateOf(hints.coverageExplainerSeen()) }
    var patternExpanded by remember { mutableStateOf(hints.patternExpanded()) }
    var coverageOptionsOpen by rememberSaveable { mutableStateOf(false) }
    var rationaleFor by remember { mutableStateOf<(() -> Unit)?>(null) }
    var afterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }

    var phoneRationale by remember { mutableStateOf(false) }

    // The bar shows calmed serving info (hysteresis on bands/tech, smoothed RSRP); the details sheet stays raw.
    val stabiliser = remember { ServingStabiliser() }
    val barUi = remember(connected) { connected?.let { stabiliser.update(it, SystemClock.elapsedRealtime()) } }

    val section = state.section
    val compactSite = !expanded && state.selected != null
    val pane = when {
        showFilters -> Pane.Filters
        showCellular && connected?.cells != null -> Pane.Cellular
        state.selected != null -> Pane.Site
        section == MapSection.NEARBY -> Pane.Nearby
        else -> Pane.None
    }
    // On compact windows only the filters and cellular details are modal sheets; the site has its peek sheet and
    // Nearby is a full-screen section.
    val modalPane = if (pane == Pane.Filters || pane == Pane.Cellular) pane else Pane.None

    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val sheetMax = min(SITE_SHEET_MAX, windowHeight * SITE_SHEET_FRACTION)
    val sheetState = rememberPeekSheetState()
    val sheetExpanded = compactSite && sheetState.targetValue == PeekValue.Expanded
    PeekOnNewKey(sheetState, state.selectedId)
    // Collapsed, the peek shows the site's header, not wherever the list was scrolled to.
    val siteList = rememberLazyListState()
    LaunchedEffect(sheetState.currentValue) { if (sheetState.currentValue == PeekValue.Peek) siteList.scrollToItem(0) }
    /** The map area the site's peek sheet covers, so the camera centres a site in what is left. */
    val cameraInsetPx = if (expanded) 0 else with(density) { (SITE_PEEK + NAV_AREA).roundToPx() }

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
            s.cells != null -> {
                showFilters = false
                showCellular = true
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

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val onTrip = {
        if (tripState is TripState.Running) {
            trip.onToggle()
        } else {
            withLocation {
                // Alerts need notifications (API 33+); the trip runs without them, so this is a request, not a gate.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                trip.onToggle()
            }
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
    val selectSection: (MapSection) -> Unit = { s ->
        if (s == MapSection.NEARBY) withLocation { scope.launch { location.currentFix() } }
        showFilters = false
        showCellular = false
        vm.selectSection(s)
    }
    val onFilters = {
        showCellular = false
        showFilters = !showFilters
    }
    val openSite = { s: SiteSummary ->
        // Keyboard and focus go first, so the closing keyboard cannot disturb the sheet opening.
        keyboard?.hide()
        focusManager.clearFocus()
        searchExpanded = false
        camera.animateTo(s.lat, s.lon, SITE_ZOOM, bottomInsetPx = cameraInsetPx)
        vm.selectSite(s.id)
        // Nearby covers the map on compact windows: show the site on it.
        if (!expanded && section == MapSection.NEARBY) vm.selectSection(MapSection.HOME)
    }

    val coverageActions = CoverageActions(
        onToggle = vm::setCoverageOn,
        onOptions = { coverageOptionsOpen = !coverageOptionsOpen },
        onNetwork = vm::setCoverageNetwork,
        onFiveGOnly = vm::setCoverageFiveGOnly,
        onIndoor = vm::setCoverageIndoor,
        onView = vm::setCoverageView,
    )

    val closePane = {
        when (pane) {
            Pane.Site -> vm.selectSite(null)
            Pane.Filters -> showFilters = false
            Pane.Cellular -> showCellular = false
            Pane.Nearby -> vm.selectSection(MapSection.HOME)
            Pane.None -> Unit
        }
    }
    // Back: a modal sheet first, then an open site sheet collapses, then the site, then back to Home.
    val backTarget = when {
        searchExpanded -> null
        modalPane != Pane.None || (expanded && pane != Pane.None) -> "pane"
        compactSite && sheetState.currentValue == PeekValue.Expanded -> "collapse"
        compactSite -> "site"
        section != MapSection.HOME -> "home"
        else -> null
    }
    BackHandler(enabled = backTarget != null) {
        when (backTarget) {
            "pane" -> closePane()
            "collapse" -> scope.launch { sheetState.animateTo(PeekValue.Peek, tween()) }
            "site" -> vm.selectSite(null)
            "home" -> vm.selectSection(MapSection.HOME)
        }
    }

    val omniLabel = stringResource(R.string.pattern_omni)
    val siteBeams = remember(state.selected, state.selectedPlots, omniLabel) {
        state.selected?.let { siteBeams(it.site.lat, it.site.lon, state.selectedPlots, omniLabel) }
    }
    val onShowCoverage: (Network) -> Unit = { n ->
        state.selected?.site?.let { camera.animateTo(it.lat, it.lon, COVERAGE_FIT_ZOOM) }
        vm.showCoverageFor(n)
        // The open sheet would hide the map: collapse it to its peek (the site stays selected).
        if (!expanded) scope.launch { sheetState.animateTo(PeekValue.Peek, tween()) }
    }
    val onPatternExpanded: (Boolean) -> Unit = { patternExpanded = it; hints.setPatternExpanded(it) }

    Box(Modifier.fillMaxSize()) {
        // 1. The map: always first, always full size.
        if (renderMap && state.basemapResolved) {
            TowerMap(
                sites = state.sites,
                selectedSiteId = state.selectedId,
                dark = isSystemInDarkTheme(),
                basemapKey = state.basemapKey,
                camera = camera,
                onSiteClick = vm::onSiteTapped,
                onCameraIdle = { },
                coverageUrlTemplate = state.coverage.urlTemplate.takeIf { state.coverage.on },
                coverageView = state.coverage.view,
                onVisibleTiles = vm::onVisibleTiles,
                onZoom = vm::onZoom,
                onLongPress = vm::longPress,
                onMapTap = vm::onMapTap,
                onCoverageLoading = vm::onCoverageLoading,
                selectedColor = MaterialTheme.colorScheme.primary.toArgb(),
                modifier = Modifier.fillMaxSize(),
                userLocation = state.location.takeIf { state.locationGranted },
                userLocationColor = MaterialTheme.colorScheme.primary.toArgb(),
                userLocationEstimated = state.locationEstimated,
                connected = connected?.highlight,
                siteBeams = siteBeams,
                beamLengthM = SectorBeams.lengthMetersForZoom(state.zoom),
                beamLabelsAlways = expanded || sheetExpanded,
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
                filterCount = state.filter.activeCount(),
                onFilters = onFilters,
                onOpenUpdates = onOpenUpdates,
                onOpenOffline = onOpenOffline,
                onOpenSettings = onOpenSettings,
                onMyLocation = onMyLocation,
                onIncrease = vm::openIncreaseResolution,
                coverageOptionsOpen = coverageOptionsOpen,
                showExplainer = state.coverage.on && !explainerSeen,
                onDismissExplainer = { explainerSeen = true; hints.markCoverageExplainerSeen() },
                coverage = coverageActions,
                snackbar = snackbar,
                connected = barUi,
                onConnectedBar = onConnectedBar,
                tripRunning = tripState is TripState.Running,
                tripBanner = (tripState as? TripState.Running)?.stats?.banner,
                dasBanner = dasBanner,
                onDismissDas = das.onDismiss,
                onTrip = onTrip,
                onSelectSection = selectSection,
                // On compact windows a selected site's peek sheet takes the bottom; the bar and controls make way.
                sitePeek = compactSite,
            )
        }
        val nearbyContent: @Composable (Modifier, PaddingValues) -> Unit = { modifier, padding ->
            NearbyContent(
                locationGranted = state.locationGranted,
                locationEnabled = state.locationEnabled,
                hasLocation = state.location != null,
                onOpenLocationSettings = openLocationSettings,
                nearby = state.nearby,
                onSiteClick = openSite,
                onRequestPermission = { withLocation { } },
                modifier = modifier,
                contentPadding = padding,
            )
        }
        val siteContent: @Composable (PaddingValues) -> Unit = { padding ->
            state.selected?.let {
                SiteSheetContent(
                    it, onClose = { vm.selectSite(null) }, plots = state.selectedPlots, onShowCoverage = onShowCoverage,
                    patternExpanded = patternExpanded, onPatternExpandedChange = onPatternExpanded, contentPadding = padding,
                    listState = siteList,
                )
            }
        }
        val paneContent: @Composable (Pane) -> Unit = { p ->
            when (p) {
                Pane.Site -> siteContent(PaddingValues(bottom = 24.dp))
                Pane.Cellular -> connected?.let { ui ->
                    CellularDetailsContent(
                        ui = ui,
                        calibration = calibration,
                        onOpenTower = { id ->
                            ui.highlight?.serving?.let { (lat, lon) -> camera.animateTo(lat, lon, SITE_ZOOM, bottomInsetPx = cameraInsetPx) }
                            showCellular = false
                            vm.selectSite(id)
                        },
                        onClose = { showCellular = false },
                    )
                }
                Pane.Filters -> FiltersContent(
                    filter = state.filter,
                    availableBands = state.availableBands,
                    onFilter = vm::setFilter,
                    onClose = { showFilters = false },
                )
                Pane.Nearby -> nearbyContent(Modifier, PaddingValues(bottom = 24.dp))
                Pane.None -> Unit
            }
        }
        if (expanded) {
            ExpandedPanes(pane, controls, paneContent)
        } else {
            controls(false)
            // Nearby: full screen over the map, under the navigation bar.
            AnimatedVisibility(section == MapSection.NEARBY, enter = fadeIn(), exit = fadeOut()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
                    val navInset = WindowInsets.navigationBars.let { with(density) { it.getBottom(density).toDp() } }
                    nearbyContent(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp), PaddingValues(bottom = NAV_AREA + navInset + 16.dp))
                }
            }
            // The selected site: a peek sheet that never hides the lobes it is about.
            AnimatedVisibility(
                state.selected != null && section != MapSection.NEARBY,
                enter = slideInVertically { it }, exit = slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val navInset = WindowInsets.navigationBars.let { with(density) { it.getBottom(density).toDp() } }
                PeekSheet(sheetState, peekHeight = SITE_PEEK, maxHeight = sheetMax, bottomInset = navInset) {
                    siteContent(PaddingValues(bottom = 16.dp))
                }
            }
            // The site sheet takes the bottom edge; the navigation bar returns when it closes.
            AnimatedVisibility(!compactSite, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                NavBarHost(section, selectSection, tripState is TripState.Running)
            }
            // A different pane gets a fresh sheet: a modal sheet whose content changes height while open can lose its anchors.
            var lastPane by remember { mutableStateOf(modalPane) }
            if (modalPane != Pane.None) lastPane = modalPane
            key(lastPane) { CompactSheet(modalPane, onDismiss = closePane, paneContent) }
        }
        if (drawBox?.active == true) DrawBoxOverlay(camera, drawBox.onResult)
    }

    state.coverage.increase?.let {
        IncreaseResolutionSheet(
            it,
            onDownload = vm::downloadIncrease,
            onConfirmMetered = vm::confirmIncreaseOnMobileData,
            onDismiss = vm::dismissIncreaseResolution,
        )
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

/** The floating navigation bar, centred above the system navigation bar. */
@Composable
private fun NavBarHost(section: MapSection, onSelect: (MapSection) -> Unit, tripRunning: Boolean, modifier: Modifier = Modifier) {
    FloatingNavBar(
        section, onSelect, tripRunning = tripRunning,
        modifier = modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = FLOATING_NAV_MARGIN),
    )
}

@Composable
private fun MapControls(
    state: MapUiState,
    docked: Boolean,
    searchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    onResultClick: (SiteSummary) -> Unit,
    filterCount: Int,
    onFilters: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenSettings: () -> Unit,
    onMyLocation: () -> Unit,
    onIncrease: () -> Unit,
    coverageOptionsOpen: Boolean,
    showExplainer: Boolean,
    onDismissExplainer: () -> Unit,
    coverage: CoverageActions,
    snackbar: SnackbarHostState,
    connected: ConnectedUi?,
    onConnectedBar: () -> Unit,
    tripRunning: Boolean,
    tripBanner: String?,
    dasBanner: String?,
    onDismissDas: () -> Unit,
    onTrip: () -> Unit,
    onSelectSection: (MapSection) -> Unit,
    sitePeek: Boolean,
) {
    val inCoverage = state.section == MapSection.COVERAGE
    // Bottom up: the navigation bar, then the info bar (or a site's peek sheet), then the buttons and coverage controls.
    val showBar = connected != null && !sitePeek && !inCoverage
    val barInset = if (showBar) CONNECTED_BAR_INSET + CONNECTED_ROW_INSET * connected!!.otherSims.size else 0.dp
    val stackInset: Dp = if (sitePeek) SITE_PEEK + 8.dp else NAV_AREA + barInset
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth()) {
            val search: @Composable (Modifier) -> Unit = { m ->
                TowerSearchBar(
                    search = state.search,
                    expanded = searchExpanded, onExpandedChange = onSearchExpandedChange,
                    onQueryChange = onQueryChange, onResultClick = onResultClick, onOpenUpdates = onOpenUpdates, onOpenOffline = onOpenOffline,
                    docked = docked, filterCount = filterCount, onFilters = onFilters, modifier = m,
                    updateAvailable = state.update is UpdateStatus.Available,
                    onOpenSettings = onOpenSettings,
                )
            }
            if (docked) {
                search(Modifier.statusBarsPadding().padding(start = 16.dp, top = 12.dp).widthIn(max = 420.dp))
            } else {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { search(Modifier) }
            }
            if (!searchExpanded || docked) {
                Spacer(Modifier.height(10.dp))
                tripBanner?.let { TripBanner(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                dasBanner?.let { DasBanner(it, onDismissDas, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                AnimatedVisibility(state.update.showsChip() && state.update !is UpdateStatus.Available, enter = fadeIn(), exit = fadeOut()) {
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
                AnimatedVisibility(state.coverage.showsCalculating(state.zoom), enter = fadeIn(), exit = fadeOut()) {
                    CalculatingPill(Modifier.padding(start = 16.dp, top = 4.dp), state.coverage.renderDone, state.coverage.renderTotal)
                }
                state.coverage.readout?.let {
                    PointReadout(it.result, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
                }
            }
        }
        SmallFloatingActionButton(
            onClick = onMyLocation,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            contentColor = MaterialTheme.colorScheme.primary,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp),
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = stackInset + 4.dp),
        ) { Icon(painterResource(R.drawable.ic_my_location), stringResource(R.string.fab_my_location)) }
        // The Coverage section's controls, bottom start, above the info bar.
        AnimatedVisibility(
            inCoverage,
            enter = fadeIn(), exit = fadeOut(),
            // Kept below the search bar; on short windows the stack scrolls, its toolbar (at the bottom) in view.
            modifier = Modifier.align(Alignment.BottomStart).statusBarsPadding().padding(top = 84.dp).navigationBarsPadding()
                .padding(start = 16.dp, end = 72.dp, bottom = stackInset),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedVisibility(showExplainer && !sitePeek, enter = fadeIn(), exit = fadeOut()) {
                    CoverageExplainerCard(onDismissExplainer)
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState(), reverseScrolling = true),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AnimatedVisibility(coverageOptionsOpen && !sitePeek, enter = fadeIn(), exit = fadeOut()) {
                        CoverageControlsCard(state.coverage, coverage)
                    }
                    AnimatedVisibility(state.coverage.on && !state.coverage.needsZoom(state.zoom), enter = fadeIn(), exit = fadeOut()) {
                        AccuracyCard(state.coverage.accuracy, onImprove = onIncrease)
                    }
                    CoverageLegend(state.coverage.view)
                    CoverageToolbar(
                        optionsOpen = coverageOptionsOpen && !sitePeek,
                        onOptions = coverage.onOptions,
                        tripRunning = tripRunning,
                        onTrip = onTrip,
                    )
                }
            }
        }
        if (showBar) {
            ConnectedInfoBar(
                connected!!, onClick = onConnectedBar,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = NAV_AREA).widthIn(max = 560.dp),
            )
        }
        if (docked) NavBarHost(state.section, onSelectSection, tripRunning, Modifier.align(Alignment.BottomCenter))
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = stackInset + 56.dp))
    }
}
