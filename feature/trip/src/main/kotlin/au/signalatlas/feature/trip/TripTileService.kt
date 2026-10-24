package au.signalatlas.feature.trip

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** What the tile shows. */
data class TileModel(val state: Int, val subtitleRes: Int) {
    companion object {
        /** Active while the trip runs; inactive otherwise. */
        fun of(running: Boolean) =
            if (running) TileModel(Tile.STATE_ACTIVE, R.string.trip_tile_running) else TileModel(Tile.STATE_INACTIVE, R.string.trip_tile_off)
    }
}

/** Quick Settings tile "Trip": shows whether a trip runs; a tap starts or stops it. */
class TripTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        val controller = TripRuntime.controller
        if (controller == null) {
            render(false)
            return
        }
        job = s.launch { controller.state.collect { render(it is TripState.Running) } }
    }

    override fun onStopListening() {
        job?.cancel()
        scope?.cancel()
        job = null
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val controller = TripRuntime.controller ?: return
        if (controller.isRunning) {
            controller.stop()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            openApp() // location is asked for in the app
            return
        }
        controller.start() // a tile tap is a user action, so the foreground service may start
    }

    private fun render(running: Boolean) {
        val tile = qsTile ?: return
        val m = TileModel.of(running)
        tile.state = m.state
        tile.label = getString(R.string.trip_tile_label)
        tile.subtitle = getString(m.subtitleRes)
        tile.updateTile()
    }

    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launch)
        }
    }
}
