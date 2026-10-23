package au.signalatlas.core.update

import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class UpdateManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var env: UpdateTestEnv
    private lateinit var workManager: WorkManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = MutableClock(Instant.parse("2026-10-22T10:00:00Z"))
    private lateinit var manager: UpdateManager

    @Before fun setUp() {
        env = UpdateTestEnv(tmp.root)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            env.context,
            Configuration.Builder()
                .setWorkerFactory(TowerUpdateWorkerFactory(env.deps, env.hrpDeps))
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        workManager = WorkManager.getInstance(env.context)
        val prefs = UpdatePrefs.create(File(tmp.root, "update.preferences_pb"), scope)
        manager = UpdateManager(env.context, env.remote, env.repo, env.patterns, prefs, scope, clock)
    }

    @After fun tearDown() {
        scope.cancel()
        WorkManagerTestInitHelper.closeWorkDatabase()
        env.close()
    }

    private fun check() = runBlocking { manager.checkOnAppOpen() }

    private fun settledStatus() = env.await({ manager.status.value }) { it != UpdateStatus.Unknown && it != UpdateStatus.Checking }

    @Test fun sameStampIsUpToDate() {
        env.seed("202610221934")
        check()
        assertThat(settledStatus()).isEqualTo(UpdateStatus.UpToDate("202610221934"))
    }

    @Test fun olderStampIsAvailable() {
        env.seed("202610010000")
        check()
        val s = settledStatus()
        assertThat(s).isInstanceOf(UpdateStatus.Available::class.java)
        assertThat((s as UpdateStatus.Available).info)
            .isEqualTo(RemoteInfo("202610221934", env.etag, env.fixture.size.toLong()))
    }

    @Test fun noDataIsAvailable() {
        check()
        assertThat(settledStatus()).isInstanceOf(UpdateStatus.Available::class.java)
    }

    @Test fun serverDownIsOfflineAndDoesNotThrow() {
        env.seed("202610221934")
        env.server.close()
        check()
        assertThat(settledStatus()).isEqualTo(UpdateStatus.Offline)
    }

    @Test fun secondCheckWithinThirtyMinutesMakesNoRequest() {
        env.seed("202610221934")
        check()
        val made = env.requests.size
        assertThat(made).isGreaterThan(0)

        clock.now += Duration.ofMinutes(29)
        check()
        assertThat(env.requests.size).isEqualTo(made)
        assertThat(settledStatus()).isEqualTo(UpdateStatus.UpToDate("202610221934"))

        clock.now += Duration.ofMinutes(2)
        check()
        assertThat(env.requests.size).isGreaterThan(made)
    }

    @Test fun throttleSurvivesARestartAndRemembersWhatWasSeen() {
        env.seed("202610010000")
        check()
        val made = env.requests.size
        // A new manager (as after a process restart) reads lastCheckAt and the last RemoteInfo from prefs.
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val prefs2 = UpdatePrefs.create(File(tmp.root, "update.preferences_pb"), scope2)
            // DataStore allows one instance per file per process; the first must be gone first.
            scope.cancel()
            val m2 = UpdateManager(env.context, env.remote, env.repo, env.patterns, prefs2, scope2, clock)
            runBlocking { m2.checkOnAppOpen() }
            assertThat(env.requests.size).isEqualTo(made)
            assertThat(env.await({ m2.status.value }) { it is UpdateStatus.Available }).isInstanceOf(UpdateStatus.Available::class.java)
        } finally {
            scope2.cancel()
        }
    }

    @Test fun statusFollowsTheStoreAfterAnInstall() {
        env.seed("202610010000")
        check()
        assertThat(settledStatus()).isInstanceOf(UpdateStatus.Available::class.java)
        env.seed("202610221934") // as the worker would: install + reload
        runBlocking { env.repo.reload() }
        assertThat(env.await({ manager.status.value }) { it is UpdateStatus.UpToDate })
            .isEqualTo(UpdateStatus.UpToDate("202610221934"))
    }

    private fun works(name: String) = workManager.getWorkInfosForUniqueWork(name).get()
    private fun List<WorkInfo>.download() = single { UpdateWork.TAG_DOWNLOAD in it.tags }
    private fun List<WorkInfo>.import() = single { UpdateWork.TAG_IMPORT in it.tags }

    @Test fun schedulePeriodicEnqueuesWeeklyWorkAndStaleWorkWhenNoData() {
        manager.schedulePeriodic()
        val periodic = workManager.getWorkInfosForUniqueWork(UpdateManager.PERIODIC_WORK).get().single()
        assertThat(periodic.constraints).isEqualTo(UpdatePolicy.periodicConstraints())
        assertThat(periodic.periodicityInfo!!.repeatIntervalMillis).isEqualTo(Duration.ofDays(7).toMillis())
        assertThat(periodic.tags).contains(UpdateWork.TAG_DOWNLOAD)
        // The stale chain: download on Wi-Fi, then an import with no network constraint.
        val stale = works(UpdateManager.AUTO_WORK)
        assertThat(stale).hasSize(2)
        assertThat(stale.download().constraints).isEqualTo(UpdatePolicy.staleConstraints())
        assertThat(stale.import().constraints.requiredNetworkType).isEqualTo(NetworkType.NOT_REQUIRED)
        assertThat(stale.import().constraints.requiresStorageNotLow()).isTrue()
        assertThat(stale.import().state).isEqualTo(WorkInfo.State.BLOCKED)

        // KEEP: scheduling again does not replace the existing periodic work.
        manager.schedulePeriodic()
        assertThat(workManager.getWorkInfosForUniqueWork(UpdateManager.PERIODIC_WORK).get().single().id)
            .isEqualTo(periodic.id)
    }

    @Test fun scheduleHrpEnqueuesMonthlyWorkOnWifiAndChargingAndKeepsIt() {
        manager.scheduleHrp()
        val monthly = works(UpdateManager.HRP_WORK).single()
        assertThat(monthly.constraints).isEqualTo(UpdatePolicy.hrpConstraints())
        assertThat(monthly.periodicityInfo!!.repeatIntervalMillis).isEqualTo(Duration.ofDays(30).toMillis())
        assertThat(monthly.tags).contains(UpdateWork.TAG_HRP)
        manager.scheduleHrp()
        assertThat(works(UpdateManager.HRP_WORK).single().id).isEqualTo(monthly.id)
        // Independent of the weekly tower update.
        assertThat(works(UpdateManager.PERIODIC_WORK)).isEmpty()
    }

    @Test fun downloadPatternsNowRunsOnWifiOnlyAndReplaces() {
        manager.downloadPatternsNow()
        val first = works(UpdateManager.HRP_MANUAL_WORK).single()
        assertThat(first.constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(first.constraints.requiresCharging()).isFalse()
        manager.downloadPatternsNow()
        val all = works(UpdateManager.HRP_MANUAL_WORK)
        assertThat(all.single { !it.state.isFinished }.id).isNotEqualTo(first.id)
    }

    @Test fun hrpStatusFollowsTheInstalledPatternsAndTheRunningJob() {
        assertThat(env.await({ manager.hrpStatus.value }) { true }).isEqualTo(HrpStatus(null, 0, null))
        env.seed("202610221934")
        manager.downloadPatternsNow()
        val id = works(UpdateManager.HRP_MANUAL_WORK).single().id
        WorkManagerTestInitHelper.getTestDriver(env.context)!!.setAllConstraintsMet(id)
        env.await({ works(UpdateManager.HRP_MANUAL_WORK).single().state }) { it.isFinished }
        // The patterns and the work state reach the status separately: wait for both.
        val s = env.await({ manager.hrpStatus.value }) { it.devices > 0 && it.running == null }
        assertThat(s.devices).isEqualTo(12)
        assertThat(s.lastImportedAt).isEqualTo(env.patterns.patterns.value!!.importedAt)
    }

    @Test fun schedulePeriodicSkipsStaleWorkForFreshData() {
        env.seed("202610221934") // importedAt = real now
        clock.now = Instant.now()
        manager.schedulePeriodic()
        assertThat(workManager.getWorkInfosForUniqueWork(UpdateManager.PERIODIC_WORK).get()).hasSize(1)
        assertThat(workManager.getWorkInfosForUniqueWork(UpdateManager.AUTO_WORK).get()).isEmpty()
    }

    @Test fun updateNowUsesTheRequestedNetworkAndForces() {
        manager.updateNow(allowMetered = false)
        val wifi = works(UpdateManager.MANUAL_WORK).download()
        assertThat(wifi.constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        // Only the download needs a network; the import keeps going through network changes.
        assertThat(works(UpdateManager.MANUAL_WORK).import().constraints.requiredNetworkType).isEqualTo(NetworkType.NOT_REQUIRED)

        manager.updateNow(allowMetered = true)
        val any = works(UpdateManager.MANUAL_WORK).filter { !it.state.isFinished }.download()
        assertThat(any.id).isNotEqualTo(wifi.id) // REPLACE
        assertThat(any.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        // Waiting for its network shows as a not-yet-started run.
        assertThat(env.await({ manager.status.value }) { it is UpdateStatus.Running })
            .isEqualTo(UpdateStatus.Running(UpdateManager.PHASE_WAITING, 0f))
    }

    @Test fun wifiOnlyManualRunWaitsForWifi() {
        manager.updateNow(allowMetered = false)
        assertThat(env.await({ manager.status.value }) { it is UpdateStatus.Running })
            .isEqualTo(UpdateStatus.Running(UpdateManager.PHASE_WAITING_WIFI, 0f))
    }

    @Test fun cancelManualStopsWaiting() {
        manager.updateNow(allowMetered = false)
        env.await({ manager.status.value }) { it is UpdateStatus.Running }
        manager.cancelManual()
        val infos = env.await({ works(UpdateManager.MANUAL_WORK) }) { all -> all.all { it.state.isFinished } }
        assertThat(infos.map { it.state }).containsExactly(WorkInfo.State.CANCELLED, WorkInfo.State.CANCELLED)
        assertThat(env.await({ manager.status.value }) { it !is UpdateStatus.Running }).isNotInstanceOf(UpdateStatus.Running::class.java)
    }

    private fun runManualChain() {
        val chain = works(UpdateManager.MANUAL_WORK)
        val driver = WorkManagerTestInitHelper.getTestDriver(env.context)!!
        driver.setAllConstraintsMet(chain.download().id)
        env.await({ workManager.getWorkInfoById(chain.download().id).get()!!.state }) { it.isFinished }
        driver.setAllConstraintsMet(chain.import().id)
        env.await({ works(UpdateManager.MANUAL_WORK) }) { all -> all.all { it.state.isFinished } }
    }

    @Test fun manualRunInstallsAndEndsUpToDate() {
        env.seed("202610221934")
        manager.updateNow(allowMetered = true)
        runManualChain()
        assertThat(works(UpdateManager.MANUAL_WORK).map { it.state })
            .containsExactly(WorkInfo.State.SUCCEEDED, WorkInfo.State.SUCCEEDED)
        assertThat(env.zipRequests()).isEqualTo(1) // force = true
        assertThat(env.installs.get()).isEqualTo(1)
        check()
        assertThat(settledStatus()).isEqualTo(UpdateStatus.UpToDate("202610221934"))
    }

    @Test fun failedManualRunIsReported() {
        env.seed("202610010000")
        env.zipBytes = ByteArray(4096) { it.toByte() }
        check()
        manager.updateNow(allowMetered = true)
        runManualChain()
        val s = env.await({ manager.status.value }) { it is UpdateStatus.Failed }
        assertThat((s as UpdateStatus.Failed).message).isNotEmpty()
        assertThat(env.repo.store.value!!.meta.extractStamp).isEqualTo("202610010000")
    }

    @Test fun periodicDownloadEnqueuesItsImportAsAutomaticWork() {
        env.seed("202610010000")
        manager.schedulePeriodic()
        val periodic = works(UpdateManager.PERIODIC_WORK).single()
        WorkManagerTestInitHelper.getTestDriver(env.context)!!.setAllConstraintsMet(periodic.id)
        val import = env.await({ works(UpdateManager.AUTO_WORK).filter { UpdateWork.TAG_IMPORT in it.tags } }) { it.isNotEmpty() }
        // One import, fed by the periodic download (no second download).
        assertThat(import).hasSize(1)
        assertThat(env.zipRequests()).isEqualTo(1)
    }

    @Test fun downloadedImportAboutToStartShowsAsRunning() {
        env.seed("202610010000")
        check()
        manager.updateNow(allowMetered = true)
        val chain = works(UpdateManager.MANUAL_WORK)
        WorkManagerTestInitHelper.getTestDriver(env.context)!!.setAllConstraintsMet(chain.download().id)
        env.await({ workManager.getWorkInfoById(chain.download().id).get()!!.state }) { it.isFinished }
        // The import is enqueued (constraints not yet met): one update in progress, not "available".
        assertThat(env.await({ manager.status.value }) { it is UpdateStatus.Running })
            .isEqualTo(UpdateStatus.Running(UpdateWork.PHASE_STARTING, 0f))
    }
}
