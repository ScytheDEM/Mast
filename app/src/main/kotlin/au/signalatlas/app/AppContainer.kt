package au.signalatlas.app

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import au.signalatlas.app.data.LocatorBasemapKeys
import au.signalatlas.app.data.ServiceCoverage
import au.signalatlas.app.data.ManagerUpdates
import au.signalatlas.app.data.RepositoryTowerData
import au.signalatlas.app.location.DeviceLocationSource
import au.signalatlas.app.ui.map.MapViewModel
import au.signalatlas.core.acma.AcmaImporter
import au.signalatlas.core.acma.hrp.HrpImporter
import au.signalatlas.core.data.DbInstaller
import au.signalatlas.core.data.DbValidator
import au.signalatlas.core.data.PatternInstaller
import au.signalatlas.core.data.PatternRepository
import au.signalatlas.core.data.TowerRepository
import au.signalatlas.core.coverage.CoverageInterceptor
import au.signalatlas.core.coverage.CoverageTileService
import au.signalatlas.core.coverage.RenderPool
import au.signalatlas.core.coverage.TileCache
import au.signalatlas.core.map.BasemapLocator
import au.signalatlas.core.map.MapHttp
import au.signalatlas.engine.CoverageEngine
import au.signalatlas.core.update.AcmaRemote
import au.signalatlas.core.update.HrpDeps
import au.signalatlas.core.update.HrpRemote
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
import okhttp3.Dispatcher
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

    /** Antenna patterns (`patterns.db`, beside towers.db); null until imported: standard patterns. */
    private val patternInstaller = PatternInstaller(towersDir)
    val patterns = PatternRepository(patternInstaller, onLoadError = { Log.e(TAG, "Could not load patterns.db", it) })
    private val notifications = UpdateNotifications(app)

    val updateDeps: UpdateDeps = object : UpdateDeps {
        override val remote = this@AppContainer.remote
        override val repo = this@AppContainer.repo
        override val importer = AcmaImporter(log = { Log.i(IMPORT_LOG_TAG, it) })
        override val installer = this@AppContainer.installer
        override val workDir = towersDir
        override val notifications = this@AppContainer.notifications
    }

    val hrpDeps: HrpDeps = object : HrpDeps {
        override val remote = HrpRemote(http)
        override val importer = HrpImporter(log = { Log.i(HRP_LOG_TAG, it) })
        override val installer = patternInstaller
        override val patterns = this@AppContainer.patterns
        override val towersDb = this@AppContainer.installer.live
        override val workDir = towersDir
        override val notifications = this@AppContainer.notifications
    }

    /** Lazy: WorkManager initialises on first use, through SignalAtlasApp's configuration. */
    val updateManager: UpdateManager by lazy {
        UpdateManager(
            app, remote, repo, patterns,
            UpdatePrefs.create(File(app.filesDir, "datastore/update.preferences_pb"), appScope), appScope,
        )
    }

    /** Coverage overlay: tiles are rendered on demand for MapLibre's requests (see [CoverageInterceptor]). */
    val coverage = CoverageTileService(
        repo, patterns.patterns, CoverageEngine(), TileCache(File(app.cacheDir, "coverage-tiles").apply { mkdirs() }),
        RenderPool(::renderThreads),
    )

    init {
        // Every tile request blocks one dispatcher thread until its render is done, so the per-host limit has to
        // exceed the render pool and MapLibre's concurrent tile requests.
        MapHttp.install(
            http.newBuilder()
                .addInterceptor(CoverageInterceptor(coverage))
                .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 20 })
                .build(),
        )
    }

    private fun renderThreads(): Int {
        val pm = app.getSystemService(PowerManager::class.java)
        val sdk = Build.VERSION.SDK_INT
        // getThermalHeadroom is API 30; API 29 only has the coarse status.
        val headroom = if (sdk >= Build.VERSION_CODES.R) pm?.getThermalHeadroom(THERMAL_FORECAST_S) else null
        return RenderThreads.budget(sdk, headroom, pm?.currentThermalStatus ?: 0, Runtime.getRuntime().availableProcessors())
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
        patterns.start() // never throws for a bad file: it is ignored (standard patterns)
    }

    fun startRepository() {
        startJob.start()
    }

    /** App open: the DB must be open before scheduling (the stale check reads its age). */
    suspend fun onAppOpen() {
        startJob.start()
        startJob.join()
        updateManager.schedulePeriodic()
        updateManager.scheduleHrp()
        updateManager.checkOnAppOpen()
    }

    fun mapViewModel() = MapViewModel(
        towerData, ManagerUpdates(updateManager), LocatorBasemapKeys(basemap), location, ServiceCoverage(coverage, repo, patterns),
    )

    private companion object {
        const val TAG = "SignalAtlas"
        const val THERMAL_FORECAST_S = 10
        /** Per-phase import timings: `phase=<name> ms=<elapsed>`, then `phase=total`. */
        const val IMPORT_LOG_TAG = "AcmaImporter"
        /** HRP import timings: `phase=towers|stream|total ms=<elapsed>`. */
        const val HRP_LOG_TAG = "HrpImporter"
    }
}
