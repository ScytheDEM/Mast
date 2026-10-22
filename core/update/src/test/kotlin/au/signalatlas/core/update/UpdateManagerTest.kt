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
                .setWorkerFactory(TowerUpdateWorkerFactory(env.deps))
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        workManager = WorkManager.getInstance(env.context)
        val prefs = UpdatePrefs.create(File(tmp.root, "update.preferences_pb"), scope)
        manager = UpdateManager(env.context, env.remote, env.repo, prefs, scope, clock)
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
            val m2 = UpdateManager(env.context, env.remote, env.repo, prefs2, scope2, clock)
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

    @Test fun schedulePeriodicEnqueuesWeeklyWorkAndStaleWorkWhenNoData() {
        manager.schedulePeriodic()
        val periodic = workManager.getWorkInfosForUniqueWork(UpdateManager.PERIODIC_WORK).get().single()
        assertThat(periodic.constraints).isEqualTo(UpdatePolicy.periodicConstraints())
        assertThat(periodic.periodicityInfo!!.repeatIntervalMillis).isEqualTo(Duration.ofDays(7).toMillis())
        val stale = workManager.getWorkInfosForUniqueWork(UpdateManager.STALE_WORK).get().single()
        assertThat(stale.constraints).isEqualTo(UpdatePolicy.staleConstraints())

        // KEEP: scheduling again does not replace the existing periodic work.
        manager.schedulePeriodic()
        assertThat(workManager.getWorkInfosForUniqueWork(UpdateManager.PERIODIC_WORK).get().single().id)
            .isEqualTo(periodic.id)
    }

    @Test fun schedulePeriodicSkipsStaleWorkForFreshData() {
        env.seed("202610221934") // importedAt = real now
        clock.now = Instant.now()
        manager.schedulePeriodic()
        assertThat(workManager.getWorkInfosForUniqueWork(UpdateManager.PERIODIC_WORK).get()).hasSize(1)
        assertThat(workManager.getWorkInfosForUniqueWork(UpdateManager.STALE_WORK).get()).isEmpty()
    }

    @Test fun updateNowUsesTheRequestedNetworkAndForces() {
        manager.updateNow(allowMetered = false)
        val wifi = workManager.getWorkInfosForUniqueWork(UpdateManager.MANUAL_WORK).get().single()
        assertThat(wifi.constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)

        manager.updateNow(allowMetered = true)
        val any = workManager.getWorkInfosForUniqueWork(UpdateManager.MANUAL_WORK).get()
            .single { !it.state.isFinished }
        assertThat(any.id).isNotEqualTo(wifi.id) // REPLACE
        assertThat(any.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        // Waiting for its network shows as a not-yet-started run.
        assertThat(env.await({ manager.status.value }) { it is UpdateStatus.Running })
            .isEqualTo(UpdateStatus.Running(UpdateManager.PHASE_WAITING, 0f))
    }

    @Test fun manualRunInstallsAndEndsUpToDate() {
        env.seed("202610221934")
        manager.updateNow(allowMetered = true)
        val id = workManager.getWorkInfosForUniqueWork(UpdateManager.MANUAL_WORK).get().single().id
        WorkManagerTestInitHelper.getTestDriver(env.context)!!.setAllConstraintsMet(id)
        env.await({ workManager.getWorkInfoById(id).get()!!.state }) { it.isFinished }
        assertThat(workManager.getWorkInfoById(id).get()!!.state).isEqualTo(WorkInfo.State.SUCCEEDED)
        assertThat(env.zipRequests()).isEqualTo(1) // force = true
        check()
        assertThat(settledStatus()).isEqualTo(UpdateStatus.UpToDate("202610221934"))
    }

    @Test fun failedManualRunIsReported() {
        env.seed("202610010000")
        env.zipBytes = ByteArray(4096) { it.toByte() }
        check()
        manager.updateNow(allowMetered = true)
        val id = workManager.getWorkInfosForUniqueWork(UpdateManager.MANUAL_WORK).get().single().id
        WorkManagerTestInitHelper.getTestDriver(env.context)!!.setAllConstraintsMet(id)
        val s = env.await({ manager.status.value }) { it is UpdateStatus.Failed }
        assertThat((s as UpdateStatus.Failed).message).isNotEmpty()
        assertThat(env.repo.store.value!!.meta.extractStamp).isEqualTo("202610010000")
    }
}
