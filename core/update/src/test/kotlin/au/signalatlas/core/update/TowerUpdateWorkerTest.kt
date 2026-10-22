package au.signalatlas.core.update

import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import au.signalatlas.core.model.TowerFilter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TowerUpdateWorkerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var env: UpdateTestEnv
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun newEnv(minSites: Int = 1) = UpdateTestEnv(tmp.root, minSites).also { env = it }

    @After fun tearDown() {
        scope.cancel()
        if (::env.isInitialized) env.close()
    }

    private fun run(force: Boolean = false, attempt: Int = 0): Result = runBlocking {
        TestListenableWorkerBuilder<TowerUpdateWorker>(env.context)
            .setWorkerFactory(TowerUpdateWorkerFactory(env.deps))
            .setInputData(workDataOf(TowerUpdateWorker.KEY_FORCE to force))
            .setRunAttemptCount(attempt)
            .build()
            .doWork()
    }

    @Test fun downloadsImportsInstallsAndReloads() {
        newEnv()
        val result = run()
        assertThat(result).isEqualTo(Result.success())
        val store = checkNotNull(env.repo.store.value)
        assertThat(store.meta.extractStamp).isEqualTo(env.stamp)
        assertThat(store.allSites(TowerFilter())).isNotEmpty()
        // The downloaded zip, its provenance sidecar and the building DB are all gone.
        assertThat(env.zip.exists()).isFalse()
        assertThat(File(env.zip.path + ".etag").exists()).isFalse()
        assertThat(File(env.workDir, "towers.db.building").exists()).isFalse()
        assertThat(env.installer.live.isFile).isTrue()
    }

    @Test fun replacesAnOlderStore() {
        newEnv().seed("202610010000")
        val old = checkNotNull(env.repo.store.value)
        assertThat(run()).isEqualTo(Result.success())
        val now = checkNotNull(env.repo.store.value)
        assertThat(now).isNotSameInstanceAs(old)
        assertThat(now.meta.extractStamp).isEqualTo(env.stamp)
        assertThat(env.installer.previous.isFile).isTrue()
    }

    @Test fun sameStampWithoutForceSkipsTheDownload() {
        newEnv().seed("202610221934")
        val old = env.repo.store.value
        assertThat(run(force = false)).isEqualTo(Result.success())
        assertThat(env.zipRequests()).isEqualTo(0)
        assertThat(env.repo.store.value).isSameInstanceAs(old)
    }

    @Test fun forceDownloadsEvenWhenUpToDate() {
        newEnv().seed("202610221934")
        assertThat(run(force = true)).isEqualTo(Result.success())
        assertThat(env.zipRequests()).isEqualTo(1)
    }

    @Test fun startsTheRepositoryItselfInAFreshProcess() {
        newEnv().seed("202610221934")
        // A worker process where nobody called repo.start(): it must not re-download current data.
        val fresh = TowerRepository(env.installer, scope)
        val deps = object : UpdateDeps by env.deps {
            override val repo = fresh
        }
        val result = runBlocking {
            TestListenableWorkerBuilder<TowerUpdateWorker>(env.context)
                .setWorkerFactory(TowerUpdateWorkerFactory(deps)).build().doWork()
        }
        assertThat(result).isEqualTo(Result.success())
        assertThat(env.zipRequests()).isEqualTo(0)
        assertThat(fresh.store.value?.meta?.extractStamp).isEqualTo("202610221934")
        fresh.close()
    }

    @Test fun corruptZipFailsAndKeepsTheCurrentStore() {
        newEnv().seed("202610010000")
        val old = checkNotNull(env.repo.store.value)
        val count = old.allSites(TowerFilter()).size
        env.zipBytes = ByteArray(4096) { it.toByte() }

        val result = run()

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.outputData.getString(TowerUpdateWorker.KEY_ERROR)).isNotEmpty()
        assertThat(env.repo.store.value).isSameInstanceAs(old)
        assertThat(old.allSites(TowerFilter())).hasSize(count)
        assertThat(old.meta.extractStamp).isEqualTo("202610010000")
        // A corrupt download is not kept for a resume that would only fail again.
        assertThat(env.zip.exists()).isFalse()
    }

    @Test fun invalidDbFailsWithTheReasonAndKeepsTheCurrentStore() {
        newEnv(minSites = 1_000_000).seed("202610010000")
        val old = checkNotNull(env.repo.store.value)
        val result = run()
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.outputData.getString(TowerUpdateWorker.KEY_ERROR)).contains("site count")
        assertThat(env.repo.store.value).isSameInstanceAs(old)
        assertThat(old.meta.extractStamp).isEqualTo("202610010000")
        assertThat(old.allSites(TowerFilter())).isNotEmpty()
    }

    @Test fun networkErrorRetriesThenGivesUp() {
        newEnv().server.close()
        assertThat(run(attempt = 0)).isEqualTo(Result.retry())
        val last = run(attempt = TowerUpdateWorker.MAX_ATTEMPTS)
        assertThat(last).isInstanceOf(Result.Failure::class.java)
        assertThat(last.outputData.getString(TowerUpdateWorker.KEY_ERROR)).isNotEmpty()
    }

    @Test fun unexpectedStampFormatFails() {
        newEnv().stamp = "<html>Maintenance</html>"
        val result = run(attempt = 0)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.outputData.getString(TowerUpdateWorker.KEY_ERROR)).isNotEmpty()
        assertThat(env.zipRequests()).isEqualTo(0)
    }

    @Test fun cleansUpLeftoversOfACrashedImport() {
        newEnv().seed("202610221934")
        val leftover = File(env.workDir, "towers.db.building").apply { writeText("half") }
        run()
        assertThat(leftover.exists()).isFalse()
    }
}
