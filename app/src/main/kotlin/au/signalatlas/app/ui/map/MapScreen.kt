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
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import au.signalatlas.app.R
import au.signalatlas.app.location.DeviceLocationSource
import au.signalatlas.app.ui.nearby.NearbyContent
import au.signalatlas.core.map.MapCameraState
import au.signalatlas.core.map.TowerMap
import au.signalatlas.core.model.SiteSummary
import kotlinx.coroutines.launch

/** Keeps the camera across activity recreation (e.g. a dark-mode switch). */
class MapCameraHolder : ViewModel() {
    val camera = MapCameraState()
}

private enum class Pane { None, Site, Nearby }

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
) {
    val camera = viewModel<MapCameraHolder>().camera
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val expanded = currentWindowAdaptiveInfo().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    var showNearby by rememberSaveable { mutableStateOf(false) }
    var showBands by rememberSaveable { mutableStateOf(false) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var centreOnFix by remember { mutableStateOf(false) }
    var rationaleFor by remember { mutableStateOf<(() -> Unit)?>(null) }
    var afterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }

    val pane = when {
        state.selected != null -> Pane.Site
        showNearby -> Pane.Nearby
        else -> Pane.None
    }

    // Permission may change in Settings while we are away.
    LifecycleResumeEffect(Unit) {
        vm.onLocationPermission(location.hasPermission())
        onPauseOrDispose { }
    }
    LifecycleStartEffect(state.locationGranted) {
        if (state.locationGranted) location.start()
        onStopOrDispose { location.stop() }
    }
    LaunchedEffect(centreOnFix, state.location) {
        val fix = state.location
        if (centreOnFix && fix != null) {
            centreOnFix = false
            camera.animateTo(fix.first, fix.second, SITE_ZOOM)
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
            val fix = state.location
            if (fix != null) camera.animateTo(fix.first, fix.second, SITE_ZOOM) else centreOnFix = true
        }
    }
    val onNearby = { withLocation { }; showNearby = true }
    val openSite = { s: SiteSummary ->
        searchExpanded = false
        camera.animateTo(s.lat, s.lon, SITE_ZOOM)
        vm.selectSite(s.id)
    }

    BackHandler(enabled = pane != Pane.None && !searchExpanded) {
        if (pane == Pane.Site) vm.selectSite(null) else showNearby = false
    }

    Box(Modifier.fillMaxSize()) {
        // 1. The map: always first, always full size.
        if (state.basemapResolved) {
            TowerMap(
                sites = state.sites,
                selectedSiteId = state.selectedId,
                dark = isSystemInDarkTheme(),
                basemapKey = state.basemapKey,
                camera = camera,
                onSiteClick = { vm.selectSite(it) },
                onCameraIdle = { },
                selectedColor = MaterialTheme.colorScheme.primary.toArgb(),
                modifier = Modifier.fillMaxSize(),
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
                snackbar = snackbar,
            )
        }
        val paneContent: @Composable (Pane) -> Unit = { p ->
            when (p) {
                Pane.Site -> state.selected?.let { SiteSheetContent(it, onClose = { vm.selectSite(null) }) }
                Pane.Nearby -> NearbyContent(
                    locationGranted = state.locationGranted,
                    hasLocation = state.location != null,
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

    if (showBands) {
        BandsSheet(state.filter, state.availableBands, onFilter = vm::setFilter, onDismiss = { showBands = false })
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactSheet(pane: Pane, onDismiss: () -> Unit, paneContent: @Composable (Pane) -> Unit) {
    if (pane == Pane.None) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.18f),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding()) { paneContent(pane) }
    }
}

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
    snackbar: SnackbarHostState,
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth()) {
            if (docked) {
                TowerSearchBar(
                    query = state.query, results = state.searchResults,
                    expanded = searchExpanded, onExpandedChange = onSearchExpandedChange,
                    onQueryChange = onQueryChange, onResultClick = onResultClick, onOpenUpdates = onOpenUpdates,
                    docked = true,
                    modifier = Modifier.statusBarsPadding().padding(start = 16.dp, top = 12.dp).widthIn(max = 420.dp),
                )
            } else {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    TowerSearchBar(
                        query = state.query, results = state.searchResults,
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
            }
        }
        Column(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
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
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
    }
}
