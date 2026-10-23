package au.signalatlas.core.update

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkInfo
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import au.signalatlas.core.data.TowerRepository
import au.signalatlas.core.model.TowerFilter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class UpdateWorkersTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var env: UpdateTestEnv
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun newEnv(minSites: Int = 1) = UpdateTestEnv(tmp.root, minSites).also { env = it }

    @After fun tearDown() {
        scope.cancel()
        if (::env.isInitialized) env.close()
    }

    /** Progress each worker published, by worker id. */
    private val published = CopyOnWriteArrayList<Pair<UUID, Data>>()

    private inline fun <reified W : ListenableWorker> worker(input: Data, attempt: Int = 0, deps: UpdateDeps = env.deps): W =
        TestListenableWorkerBuilder<W>(env.context)
            .setWorkerFactory(TowerUpdateWorkerFactory(deps))
            .setInputData(input)
            .setRunAttemptCount(attempt)
            .setProgressUpdater { _, id, data ->
                published += id to data
                SettableFuture.create<Void>().apply { set(null) }
            }
            .build()

    private fun downloadInput(force: Boolean = false, base: String? = null) = Data.Builder()
        .putBoolean(UpdateWork.KEY_FORCE, force)
        .apply { base?.let { putString(UpdateWork.KEY_BASE_STAMP, it) } }
        .build()

    private fun download(force: Boolean = false, base: String? = null, attempt: Int = 0): Result = runBlocking {
        worker<AcmaDownloadWorker>(downloadInput(force, base), attempt).doWork()
    }

    private fun import(input: Data, attempt: Int = 0): Result = runBlocking {
        worker<AcmaImportWorker>(input, attempt).doWork()
    }

    /** The whole chain as WorkManager runs it: the download's output is the import's input. */
    private fun update(force: Boolean = false, base: String? = null): Result {
        val d = download(force, base)
        return if (d is Result.Success) import(d.outputData) else d
    }

    @Test fun downloadsImportsInstallsAndReloads() {
        newEnv()
        assertThat(update()).isEqualTo(Result.success())
        val store = checkNotNull(env.repo.store.value)
        assertThat(store.meta.extractStamp).isEqualTo(env.stamp)
        assertThat(store.allSites(TowerFilter())).isNotEmpty()
        // The downloaded zip, its provenance sidecar and the building DB are all gone.
        assertThat(env.zip.exists()).isFalse()
        assertThat(File(env.zip.path + ".etag").exists()).isFalse()
        assertThat(File(env.workDir, "towers.db.building").exists()).isFalse()
        assertThat(env.installer.live.isFile).isTrue()
        // A first install has nothing to roll back to.
        assertThat(env.installer.previous.exists()).isFalse()
        assertThat(env.installs.get()).isEqualTo(1)
    }

    @Test fun downloadKeepsTheZipAndHandsStampEtagAndSizeToTheImport() {
        newEnv()
        val result = download()
        assertThat(result).isInstanceOf(Result.Success::class.java)
        val out = result.outputData
        assertThat(out.getString(UpdateWork.KEY_STAMP)).isEqualTo(env.stamp)
        assertThat(out.getString(UpdateWork.KEY_ETAG)).isEqualTo(env.etag)
        assertThat(out.getLong(UpdateWork.KEY_SIZE, -1)).isEqualTo(env.fixture.size.toLong())
        assertThat(env.zip.length()).isEqualTo(env.fixture.size.toLong())
        assertThat(env.repo.store.value).isNull() // nothing imported yet
    }

    @Test fun replacesAnOlderStore() {
        newEnv().seed("202610010000")
        val old = checkNotNull(env.repo.store.value)
        assertThat(update()).isEqualTo(Result.success())
        val now = checkNotNull(env.repo.store.value)
        assertThat(now).isNotSameInstanceAs(old)
        assertThat(now.meta.extractStamp).isEqualTo(env.stamp)
        assertThat(env.installer.previous.isFile).isTrue()
    }

    @Test fun sameStampWithoutForceSkipsTheDownloadAndTheImport() {
        newEnv().seed("202610221934")
        val old = env.repo.store.value
        val d = download(force = false)
        assertThat(d.outputData.getBoolean(UpdateWork.KEY_UP_TO_DATE, false)).isTrue()
        assertThat(import(d.outputData)).isEqualTo(Result.success())
        assertThat(env.zipRequests()).isEqualTo(0)
        assertThat(env.installs.get()).isEqualTo(0)
        assertThat(env.repo.store.value).isSameInstanceAs(old)
    }

    @Test fun forceDownloadsEvenWhenUpToDate() {
        newEnv().seed("202610221934")
        assertThat(update(force = true, base = "202610221934")).isEqualTo(Result.success())
        assertThat(env.zipRequests()).isEqualTo(1)
        assertThat(env.installs.get()).isEqualTo(1)
    }

    @Test fun forcedRunSkipsWhenAnotherRunInstalledTheDataSinceItWasRequested() {
        // "Update now" was tapped on 202610010000; a weekly run has installed ACMA's current data since.
        newEnv().seed("202610221934")
        assertThat(update(force = true, base = "202610010000")).isEqualTo(Result.success())
        assertThat(env.zipRequests()).isEqualTo(0)
        assertThat(env.installs.get()).isEqualTo(0)
    }

    @Test fun startsTheRepositoryItselfInAFreshProcess() {
        newEnv().seed("202610221934")
        // A worker process where nobody called repo.start(): it must not re-download current data.
        val fresh = TowerRepository(env.installer, scope)
        val deps = object : UpdateDeps by env.deps {
            override val repo = fresh
        }
        val result = runBlocking { worker<AcmaDownloadWorker>(downloadInput(), deps = deps).doWork() }
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(env.zipRequests()).isEqualTo(0)
        assertThat(fresh.store.value?.meta?.extractStamp).isEqualTo("202610221934")
        fresh.close()
    }

    @Test fun reloadsADbThatWasInstalledButNeverLoaded() {
        newEnv().seed("202610010000")
        // A worker installed ACMA's data and was stopped before its reload.
        env.installOnly("202610221934")
        assertThat(env.repo.store.value!!.meta.extractStamp).isEqualTo("202610010000")
        val d = download()
        assertThat(d.outputData.getBoolean(UpdateWork.KEY_UP_TO_DATE, false)).isTrue()
        assertThat(env.repo.store.value!!.meta.extractStamp).isEqualTo("202610221934")
        assertThat(env.zipRequests()).isEqualTo(0)
    }

    @Test fun corruptZipFailsAndKeepsTheCurrentStore() {
        newEnv().seed("202610010000")
        val old = checkNotNull(env.repo.store.value)
        val count = old.allSites(TowerFilter()).size
        env.zipBytes = ByteArray(4096) { it.toByte() }

        val result = update()

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.outputData.getString(UpdateWork.KEY_ERROR)).isNotEmpty()
        assertThat(env.repo.store.value).isSameInstanceAs(old)
        assertThat(old.allSites(TowerFilter())).hasSize(count)
        assertThat(old.meta.extractStamp).isEqualTo("202610010000")
        // A corrupt download is not kept for a resume that would only fail again.
        assertThat(env.zip.exists()).isFalse()
    }

    @Test fun invalidDbFailsWithTheReasonAndKeepsTheCurrentStore() {
        newEnv(minSites = 1_000_000).seed("202610010000")
        val old = checkNotNull(env.repo.store.value)
        val result = update()
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.outputData.getString(UpdateWork.KEY_ERROR)).contains("site count")
        assertThat(env.repo.store.value).isSameInstanceAs(old)
        assertThat(old.meta.extractStamp).isEqualTo("202610010000")
        assertThat(old.allSites(TowerFilter())).isNotEmpty()
    }

    @Test fun networkErrorRetriesThenGivesUp() {
        newEnv().server.close()
        assertThat(download(attempt = 0)).isEqualTo(Result.retry())
        val last = download(attempt = UpdateWork.MAX_ATTEMPTS)
        assertThat(last).isInstanceOf(Result.Failure::class.java)
        assertThat(last.outputData.getString(UpdateWork.KEY_ERROR)).isNotEmpty()
    }

    @Test fun unexpectedStampFormatFails() {
        newEnv().stamp = "<html>Maintenance</html>"
        val result = download(attempt = 0)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.outputData.getString(UpdateWork.KEY_ERROR)).isNotEmpty()
        assertThat(env.zipRequests()).isEqualTo(0)
    }

    @Test fun cleansUpLeftoversOfACrashedImport() {
        newEnv().seed("202610221934")
        val leftover = File(env.workDir, "towers.db.building").apply { writeText("half") }
        download()
        assertThat(leftover.exists()).isFalse()
    }

    @Test fun importWithoutItsZipFailsWithAMessage() {
        newEnv()
        val out = download().outputData
        env.remote.discard(env.zip)
        val result = import(out)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.outputData.getString(UpdateWork.KEY_ERROR)).isNotEmpty()
    }

    // C1: the import is its own work; stopping it re-runs only the import, never the download.
    @Test fun stoppedImportRerunsOnlyTheImport() {
        newEnv()
        val out = download().outputData
        assertThat(env.zipRequests()).isEqualTo(1)

        val first = worker<AcmaImportWorker>(out)
        env.onImportLog = { if (it.startsWith("phase=devices ")) first.stop(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY) }
        assertThat(runBlocking { first.doWork() }).isEqualTo(Result.retry())
        assertThat(env.installs.get()).isEqualTo(0)
        assertThat(env.zip.isFile).isTrue() // kept for the retry

        env.onImportLog = {}
        assertThat(import(out, attempt = 1)).isEqualTo(Result.success())
        assertThat(env.zipRequests()).isEqualTo(1) // no second download
        assertThat(env.repo.store.value!!.meta.extractStamp).isEqualTo(env.stamp)
    }

    // I1: a stop during finalise installs nothing; the retry installs exactly once and reloads.
    @Test fun stopDuringFinaliseInstallsNothingThenTheRetryInstallsOnceAndReloads() {
        newEnv()
        val out = download().outputData
        val first = worker<AcmaImportWorker>(out)
        env.onImportLog = { if (it.startsWith("phase=finalise.indexes ")) first.stop(WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW) }
        assertThat(runBlocking { first.doWork() }).isEqualTo(Result.retry())
        assertThat(env.installs.get()).isEqualTo(0)
        assertThat(env.installer.live.exists()).isFalse()

        env.onImportLog = {}
        assertThat(import(out, attempt = 1)).isEqualTo(Result.success())
        assertThat(env.installs.get()).isEqualTo(1)
        assertThat(env.installer.previous.exists()).isFalse() // no duplicate towers.prev.db
        assertThat(env.repo.store.value!!.meta.extractStamp).isEqualTo(env.stamp)
    }

    // I1: once the install has begun, a stop (coroutine cancelled, as WorkManager does) still reloads.
    @Test fun stopDuringInstallStillReloadsTheStore() {
        newEnv().seed("202610010000")
        val out = download().outputData
        val w = worker<AcmaImportWorker>(out)
        runBlocking {
            val job = async(Dispatchers.Default) { w.doWork() }
            env.beforeInstallMove = {
                w.stop(WorkInfo.STOP_REASON_CANCELLED_BY_APP)
                job.cancel()
            }
            runCatching { job.await() }
        }
        assertThat(env.installs.get()).isEqualTo(1)
        assertThat(env.repo.store.value!!.meta.extractStamp).isEqualTo(env.stamp)
        assertThat(env.zip.exists()).isFalse()

        // A later automatic run finds everything current: no second download, import or install.
        env.beforeInstallMove = {}
        assertThat(download().outputData.getBoolean(UpdateWork.KEY_UP_TO_DATE, false)).isTrue()
        assertThat(env.installs.get()).isEqualTo(1)
    }

    // I2: two runs at once: the one waiting for the lock says "queued", then skips the work the other did.
    @Test fun aRunWaitingForTheLockReportsQueuedAndThenSkipsDataTheOtherInstalled() {
        newEnv().seed("202610010000")
        val out = download().outputData
        val importing = CountDownLatch(1)
        val release = CountDownLatch(1)
        env.onImportLog = {
            if (it.startsWith("phase=licence ")) {
                importing.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val holder = worker<AcmaImportWorker>(out)
        // A manual "Update now" requested while the automatic run holds the lock.
        val manualInput = Data.Builder().putAll(out)
            .putBoolean(UpdateWork.KEY_FORCE, true).putString(UpdateWork.KEY_BASE_STAMP, "202610010000").build()
        val waiter = worker<AcmaImportWorker>(manualInput)
        runBlocking {
            val a = async(Dispatchers.Default) { holder.doWork() }
            assertThat(importing.await(10, TimeUnit.SECONDS)).isTrue()
            val b = async(Dispatchers.Default) { waiter.doWork() }
            env.await({ published.filter { it.first == waiter.id } }) { it.isNotEmpty() }
            val idle = setOf(null, UpdateWork.PHASE_QUEUED, UpdateWork.PHASE_STARTING)
            env.await({ published.lastOrNull { it.first == holder.id }?.second?.getString(UpdateWork.KEY_PHASE) }) { it !in idle }
            val holderPhase = published.last { it.first == holder.id }.second
            val waiterPhase = published.last { it.first == waiter.id }.second
            assertThat(waiterPhase.getString(UpdateWork.KEY_PHASE)).isEqualTo(UpdateWork.PHASE_QUEUED)

            // The status follows the lock holder, whichever of the two is listed first.
            val infos = listOf(running(waiter.id, waiterPhase), running(holder.id, holderPhase))
            val status = runningStatus(infos)!!
            assertThat(status.phase).isEqualTo(holderPhase.getString(UpdateWork.KEY_PHASE))
            assertThat(status.phase).isNotEqualTo(UpdateWork.PHASE_QUEUED)

            release.countDown()
            assertThat(a.await()).isEqualTo(Result.success())
            assertThat(b.await()).isEqualTo(Result.success())
        }
        assertThat(env.installs.get()).isEqualTo(1) // the manual run did not import again
        assertThat(env.repo.store.value!!.meta.extractStamp).isEqualTo(env.stamp)
    }

    private fun running(id: UUID, progress: Data) =
        WorkInfo(id, WorkInfo.State.RUNNING, setOf(UpdateWork.TAG_IMPORT), Data.EMPTY, progress)
}
