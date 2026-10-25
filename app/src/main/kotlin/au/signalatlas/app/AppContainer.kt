package au.signalatlas.app

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import au.signalatlas.app.car.CarBridge
import au.signalatlas.app.connected.ConnectedController
import au.signalatlas.app.connected.ReadOnlyCellMap
import au.signalatlas.app.data.PrefsUiHints
import au.signalatlas.app.data.RepositoryPatternAccess
import au.signalatlas.app.data.UiHints
import au.signalatlas.app.data.AppStorage
import au.signalatlas.app.packs.PackDeps
import au.signalatlas.app.packs.PackDownloader
import au.signalatlas.app.packs.PackManager
import au.signalatlas.app.packs.PackNotifications
import au.signalatlas.app.packs.PackRepository
import au.signalatlas.app.packs.WorkManagerPackScheduler
import au.signalatlas.core.geo.packs.DemPackContent
import au.signalatlas.core.map.StyleLoader
import au.signalatlas.core.map.packs.BasemapPackContent
import au.signalatlas.core.map.packs.MapLibreOfflineBackend
import java.io.IOException
import au.signalatlas.app.data.CalibrationPrefs
import au.signalatlas.app.data.CalibrationSettings
import au.signalatlas.app.data.StoreCalibrationData
import au.signalatlas.app.ui.calibration.CalibrationViewModel
import au.signalatlas.core.calibration.CalibrationDb
import au.signalatlas.core.calibration.CalibrationStore
import au.signalatlas.core.calibration.CellMatcher
import au.signalatlas.core.calibration.EngineCandidateSource
import au.signalatlas.core.calibration.TerrainTablesAt
import au.signalatlas.core.calibration.SampleLogger
import au.signalatlas.core.calibration.asTowerQuery
import au.signalatlas.core.model.CalibrationSnapshot
import au.signalatlas.core.radio.RadioMonitor
import kotlinx.coroutines.flow.filterNotNull
import au.signalatlas.app.data.LocatorBasemapKeys
import au.signalatlas.app.data.ServiceCoverage
import au.signalatlas.app.data.ManagerUpdates
import au.signalatlas.app.data.RepositoryTowerData
import au.signalatlas.app.location.DeviceLocationSource
import au.signalatlas.app.ui.map.MapViewModel
import au.signalatlas.app.ui.storage.StorageViewModel
import au.signalatlas.car.CarRuntime
import au.signalatlas.core.acma.AcmaImporter
import au.signalatlas.core.acma.hrp.HrpImporter
import au.signalatlas.core.data.DbInstaller
import au.signalatlas.core.data.DbValidator
import au.signalatlas.core.data.PatternInstaller
import au.signalatlas.core.data.PatternRepository
import au.signalatlas.core.data.TowerRepository
import au.signalatlas.core.coverage.ConnectivityNetworkMonitor
import au.signalatlas.core.coverage.CoverageInterceptor
import au.signalatlas.core.coverage.CoverageTileService
import au.signalatlas.core.coverage.DemFetcherSource
import au.signalatlas.core.coverage.RenderPool
import au.signalatlas.core.coverage.TerrainSupport
import au.signalatlas.core.coverage.TerrainTableStore
import au.signalatlas.core.coverage.TileCache
import au.signalatlas.core.geo.DemChunkStore
import au.signalatlas.core.geo.DemFetcher
import au.signalatlas.core.geo.GeoNetwork
import au.signalatlas.core.geo.GeoPrefs
import au.signalatlas.core.map.BasemapLocator
import au.signalatlas.core.roads.HttpRangeSource
import au.signalatlas.core.roads.PmtilesReader
import au.signalatlas.core.roads.PmtilesRoadSource
import au.signalatlas.core.roads.RoadGraphProvider
import au.signalatlas.core.map.MapHttp
import au.signalatlas.core.model.TowerFilter
import au.signalatlas.engine.CoverageEngine
import au.signalatlas.core.update.AcmaRemote
import au.signalatlas.core.update.HrpDeps
import au.signalatlas.core.update.HrpRemote
import au.signalatlas.core.update.UpdateDeps
import au.signalatlas.core.update.UpdateManager
import au.signalatlas.core.update.UpdateNotifications
import au.signalatlas.core.update.UpdatePrefs
import au.signalatlas.app.data.TripPrefs
import au.signalatlas.feature.trip.TripController
import au.signalatlas.feature.trip.TripHooks
import au.signalatlas.feature.trip.TripRuntime
import au.signalatlas.feature.trip.TripService
import au.signalatlas.feature.trip.TripSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A start/stop pair shared by several users (the map screen's connected-tower feature and a running trip): it starts on
 * the first [acquire] and stops on the last [release].
 */
class SharedRun(private val start: () -> Unit, private val stop: () -> Unit) {
    private val owners = HashSet<String>()

    fun acquire(owner: String) {
        val first = synchronized(owners) { owners.isEmpty().also { owners += owner } }
        if (first) start()
    }

    fun release(owner: String) {
        val last = synchronized(owners) { owners.remove(owner) && owners.isEmpty() }
        if (last) stop()
    }
}

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

    private val geoPrefs = GeoPrefs(app)

    /** The elevation chunk cache (`geo/dem90`), kept under the Settings budget. */
    val elevationChunks = DemChunkStore(File(app.filesDir, "geo/dem90"), budgetBytes = { geoPrefs.cacheBudgetBytes })

    /** The one elevation fetcher: its downloads are serialised process-wide. */
    private val demFetcher = DemFetcher(http, elevationChunks, isMetered = { GeoNetwork.isActiveNetworkMetered(app) })

    /** Forwards the calibration store's snapshots to the tile service (the store itself opens off the main thread). */
    private val calibrationSnapshot = MutableStateFlow(CalibrationSnapshot.EMPTY)

    private val tileCache = TileCache(File(app.cacheDir, "coverage-tiles").apply { mkdirs() })
    private val terrainTables = TerrainTableStore(File(app.cacheDir, "terrain"))

    /** Coverage overlay: tiles are rendered on demand for MapLibre's requests (see [CoverageInterceptor]). */
    val coverage = CoverageTileService(
        repo, patterns.patterns, CoverageEngine(), tileCache,
        RenderPool(::renderThreads),
        TerrainSupport(
            elevationChunks, DemFetcherSource(demFetcher), terrainTables,
            ConnectivityNetworkMonitor(app), appScope,
        ),
        calibrationSnapshot,
    )

    private val towerQuery = repo.asTowerQuery()

    /**
     * `calibration.db`: created on first use (and warmed up in the background at start-up). Estimates are stored by
     * stable sector identity and re-resolved against the tower DB whenever it changes (Ruling TT).
     */
    val calibration: CalibrationStore by lazy {
        CalibrationStore(CalibrationDb.open(File(app.filesDir, "calibration/calibration.db")), towerQuery, appScope)
    }

    val calibrationSettings: CalibrationSettings = CalibrationPrefs(app)

    val hints: UiHints = PrefsUiHints(app)

    val tripSettings: TripSettings = TripPrefs(app)

    val radio = RadioMonitor(app, appScope)

    /** The radio polling and the sample logger are shared by the map screen and a running trip. */
    private val radioRun = SharedRun(radio::start, radio::stop)
    private val loggerRun by lazy { SharedRun(sampleLogger::start, sampleLogger::stop) }

    private val candidates = EngineCandidateSource(towerQuery, CoverageEngine(), { patterns.patterns.value }, TerrainTablesAt(coverage::terrainTablesAt))

    private val sampleLogger by lazy {
        SampleLogger(
            radio.state, location.fixes.filterNotNull(), CellMatcher(candidates, calibration), calibration::add, appScope,
            SystemClock::elapsedRealtime,
        )
    }

    /** The connected-tower feature; it runs only while the map screen is started. */
    val connected: ConnectedController by lazy {
        ConnectedController(
            radio.state, location.fixes, CellMatcher(candidates, ReadOnlyCellMap(calibration)), calibrationSettings, appScope,
            startRadio = { radioRun.acquire(MAP_USER) }, stopRadio = { radioRun.release(MAP_USER) },
            startLogger = { loggerRun.acquire(MAP_USER) }, stopLogger = { loggerRun.release(MAP_USER) },
            radioStates = radio.states, dataSubId = radio.dataSubscriptionId,
        )
    }

    /** Android Auto: its own location source and connected-tower feature, so the phone's map screen is unaffected. */
    private val carLocation by lazy { DeviceLocationSource(app) }
    private val carConnected = lazy {
        ConnectedController(
            radio.state, carLocation.fixes, CellMatcher(candidates, ReadOnlyCellMap(calibration)), calibrationSettings, appScope,
            startRadio = { radioRun.acquire(CAR_USER) }, stopRadio = { radioRun.release(CAR_USER) },
            startLogger = {}, stopLogger = {},
            radioStates = radio.states, dataSubId = radio.dataSubscriptionId,
        )
    }

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

    /** Memory pressure: the coverage service sheds its terrain memory. */
    fun onTrimMemory(level: Int) = coverage.onTrimMemory(level)

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

    // Offline region packs: the base map (MapLibre offline regions) and elevation (pinned DEM chunks). Buildings and
    // roads join `packSources` when their chunk stores exist.
    private val packRepo = PackRepository(File(app.filesDir, "packs/packs.json"))
    private val packSources = linkedMapOf(
        PackManager.BASEMAP to BasemapPackContent(MapLibreOfflineBackend(app, app.resources.displayMetrics.density.coerceAtMost(2f))) { packStyleUrl() },
        PackManager.ELEVATION to DemPackContent(elevationChunks, demFetcher),
    )
    val packNotifications = PackNotifications(app)
    val packDeps = PackDeps(PackDownloader(packRepo, packSources.values.toList()), packRepo)
    val packs = PackManager(packRepo, packSources, WorkManagerPackScheduler(app))

    /** The clean light style pointed at the current build, as a file MapLibre can use as a region's style URL. */
    private suspend fun packStyleUrl(): String = withContext(Dispatchers.IO) {
        val key = basemap.currentKey() ?: throw IOException("The base map build is not known yet")
        val file = File(app.filesDir, "packs/style.json").apply { parentFile?.mkdirs() }
        file.writeText(StyleLoader.load(app, dark = false, basemapKey = key))
        "file://${file.absolutePath}"
    }

    private val towerData = RepositoryTowerData(repo, repoStarted)

    /** Roads for the trip's look-ahead: z14 tiles of the basemap archive by range reads, cached under cacheDir/roads. */
    private val roads = RoadGraphProvider(
        PmtilesRoadSource(
            openReader = {
                val key = runBlocking { basemap.currentKey() } ?: throw IOException("no basemap build known")
                PmtilesReader.open(HttpRangeSource(http, "https://build.protomaps.com/$key"))
            },
            cacheDir = File(app.cacheDir, "roads"),
        ),
        appScope,
    )

    /** The trip: look-ahead, alerts and the location service. Calibration keeps logging while driving. */
    val trip = TripController(
        appScope, { points, selection, thr -> coverage.predictAlong(points, selection, thr) },
        radio.state, radio.states, radio.dataSubscriptionId, tripSettings, { coverage.selection.value?.network },
        TripHooks(
            startRadio = { radioRun.acquire(TRIP_USER) }, stopRadio = { radioRun.release(TRIP_USER) },
            startLogger = { if (calibrationSettings.enabled.value) loggerRun.acquire(TRIP_USER) },
            stopLogger = { loggerRun.release(TRIP_USER) },
            publishFix = location::feed,
            launchService = { TripService.start(app) }, haltService = { TripService.stop(app) },
        ),
        roads = roads,
    ).also {
        TripRuntime.controller = it
        TripRuntime.voiceEnabled = { tripSettings.config.value.voice }
    }

    init {
        CarRuntime.source = CarBridge(
            app, appScope, carConnected, radio.states, carLocation.fixes,
            startLocation = { carLocation.start() }, stopLocation = { carLocation.stop() },
            controller = trip,
            nearest = { lat, lon -> towerData.current()?.nearest(lat, lon, TowerFilter(), CAR_TOWERS).orEmpty().map { it.first.lat to it.first.lon } },
            basemapKey = { basemap.currentKey() },
            selection = coverage.selection, tileTemplate = coverage::tileUrlTemplate,
        )
    }

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

    private var calibrationLinked = false

    /**
     * Opens `calibration.db` off the main thread and keeps the tile service's snapshot and the store's tower
     * identities in step with it. Once per process (the first app open).
     */
    private suspend fun linkCalibration() {
        if (calibrationLinked) return
        calibrationLinked = true
        withContext(Dispatchers.IO) { calibration }
        appScope.launch { calibration.snapshot.collect { calibrationSnapshot.value = it } }
        appScope.launch(Dispatchers.IO) { repo.store.collect { calibration.onTowersChanged() } }
    }

    fun startRepository() {
        startJob.start()
    }

    /** App open: the DB must be open before scheduling (the stale check reads its age). */
    suspend fun onAppOpen() {
        startJob.start()
        startJob.join()
        linkCalibration()
        updateManager.schedulePeriodic()
        updateManager.scheduleHrp()
        updateManager.checkOnAppOpen()
    }

    fun storageViewModel() = StorageViewModel(
        AppStorage(installer.live, patternInstaller.live, elevationChunks, terrainTables, tileCache, coverage, geoPrefs),
    )

    fun calibrationViewModel() = CalibrationViewModel(StoreCalibrationData(calibration), calibrationSettings)

    fun mapViewModel() = MapViewModel(
        towerData, ManagerUpdates(updateManager), LocatorBasemapKeys(basemap), location, ServiceCoverage(coverage, repo, patterns),
        patterns = RepositoryPatternAccess(patterns),
    )

    private companion object {
        const val TAG = "SignalAtlas"
        const val MAP_USER = "map"
        const val TRIP_USER = "trip"
        const val CAR_USER = "car"
        const val CAR_TOWERS = 80
        const val THERMAL_FORECAST_S = 10
        /** Per-phase import timings: `phase=<name> ms=<elapsed>`, then `phase=total`. */
        const val IMPORT_LOG_TAG = "AcmaImporter"
        /** HRP import timings: `phase=towers|stream|total ms=<elapsed>`. */
        const val HRP_LOG_TAG = "HrpImporter"
    }
}
