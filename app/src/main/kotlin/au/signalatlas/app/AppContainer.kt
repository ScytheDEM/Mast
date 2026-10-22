package au.signalatlas.app

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import au.signalatlas.app.data.LocatorBasemapKeys
import au.signalatlas.app.data.ManagerUpdates
import au.signalatlas.app.data.RepositoryTowerData
import au.signalatlas.app.location.DeviceLocationSource
import au.signalatlas.app.ui.map.MapViewModel
import au.signalatlas.core.acma.AcmaImporter
import au.signalatlas.core.data.DbInstaller
import au.signalatlas.core.data.DbValidator
import au.signalatlas.core.map.BasemapLocator
import au.signalatlas.core.update.AcmaRemote
import au.signalatlas.core.update.TowerRepository
import au.signalatlas.core.update.UpdateDeps
import au.signalatlas.core.update.UpdateManager
import au.signalatlas.core.update.UpdateNotifications
import au.signalatlas.core.update.UpdatePrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Manual dependency graph, one per process (owned by [SignalAtlasApp]). */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    /** Lives as long as the process: owns the open DB, retiring stores and shared update status. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val towersDir = File(app.filesDir, "towers").apply { mkdirs() }
    private val remote = AcmaRemote(http)
    private val installer = DbInstaller(towersDir, DbValidator())
    val repo = TowerRepository(installer, appScope)
    private val repoStarted = MutableStateFlow(false)

    val updateDeps: UpdateDeps = object : UpdateDeps {
        override val remote = this@AppContainer.remote
        override val repo = this@AppContainer.repo
        override val importer = AcmaImporter(log = { Log.i(IMPORT_LOG_TAG, it) })
        override val installer = this@AppContainer.installer
        override val workDir = towersDir
        override val notifications = UpdateNotifications(app)
    }

    /** Lazy: WorkManager initialises on first use, through SignalAtlasApp's configuration. */
    val updateManager: UpdateManager by lazy {
        UpdateManager(app, remote, repo, UpdatePrefs.create(File(app.filesDir, "datastore/update.preferences_pb"), appScope), appScope)
    }

    private val basemap = BasemapLocator(
        http,
        PreferenceDataStoreFactory.create(scope = appScope) { File(app.filesDir, "datastore/basemap.preferences_pb") },
    )

    val location = DeviceLocationSource(app)

    private val towerData = RepositoryTowerData(repo, repoStarted)

    /** Opens the installed DB (after crash recovery) once per process. */
    private val startJob = appScope.launch(start = CoroutineStart.LAZY) {
        try {
            repo.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An unreadable DB behaves like none: the first-run screen offers a fresh download.
            Log.e(TAG, "Could not open the tower DB", e)
        } finally {
            repoStarted.value = true
        }
    }

    fun startRepository() {
        startJob.start()
    }

    /** App open: the DB must be open before scheduling (the stale check reads its age). */
    suspend fun onAppOpen() {
        startJob.start()
        startJob.join()
        updateManager.schedulePeriodic()
        updateManager.checkOnAppOpen()
    }

    fun mapViewModel() = MapViewModel(towerData, ManagerUpdates(updateManager), LocatorBasemapKeys(basemap), location)

    private companion object {
        const val TAG = "SignalAtlas"
        /** Per-phase import timings: `phase=<name> ms=<elapsed>`, then `phase=total`. */
        const val IMPORT_LOG_TAG = "AcmaImporter"
    }
}
