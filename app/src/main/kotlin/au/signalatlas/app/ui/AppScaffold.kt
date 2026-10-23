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
import au.signalatlas.app.ui.firstrun.FirstRunScreen
import au.signalatlas.app.ui.map.MapScreen
import au.signalatlas.app.ui.map.MapViewModel
import au.signalatlas.app.ui.updates.UpdatesScreen
import au.signalatlas.app.ui.updates.currentNetworkKind

private enum class Root { Loading, FirstRun, Map }

/** Top level: first-run gate, the map, and the updates screen pushed over the map. */
@Composable
fun AppScaffold(container: AppContainer) {
    val vm: MapViewModel = viewModel { container.mapViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showUpdates by rememberSaveable { mutableStateOf(false) }

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
                Root.Map -> MapScreen(state, vm, container.location, onOpenUpdates = { showUpdates = true })
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
            )
        }
    }
    BackHandler(enabled = showUpdates) { showUpdates = false }
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
