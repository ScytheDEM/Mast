package au.signalatlas.core.update

import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.WorkInfo
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.testing.TestListenableWorkerBuilder
import au.signalatlas.core.data.PatternRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class HrpWorkerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var env: UpdateTestEnv
    private val published = CopyOnWriteArrayList<Data>()

    @Before fun setUp() {
        env = UpdateTestEnv(tmp.root)
    }

    @After fun tearDown() = env.close()

    private val building get() = File(env.workDir, "patterns.db.building")
    private val live get() = File(env.workDir, "patterns.db")
    private val sidecar get() = File(env.hrpZip.path + ".etag")

    private fun worker(attempt: Int = 0): HrpWorker =
        TestListenableWorkerBuilder<HrpWorker>(env.context)
            .setWorkerFactory(TowerUpdateWorkerFactory(env.deps, env.hrpDeps))
            .setRunAttemptCount(attempt)
            .setProgressUpdater { _, _, data ->
                published += data
                SettableFuture.create<Void>().apply { set(null) }
            }
            .build()

    private fun run(attempt: Int = 0): Result = runBlocking { worker(attempt).doWork() }

    @Test fun downloadsImportsInstallsAndPublishesThePatterns() {
        env.seed("202610221934")
        assertThat(run()).isEqualTo(Result.success())
        val store = checkNotNull(env.patterns.patterns.value)
        assertThat(store.deviceCount).isEqualTo(12)
        assertThat(store.patternCount).isEqualTo(7)
        assertThat(store.etag).isEqualTo(env.hrpEtag)
        assertThat(store.towerStamp).isEqualTo("202610221934")
        assertThat(live.isFile).isTrue()
        // The zip, its sidecar and the candidate are gone; towers.db is untouched.
        assertThat(env.hrpZip.exists()).isFalse()
        assertThat(sidecar.exists()).isFalse()
        assertThat(building.exists()).isFalse()
        assertThat(env.installer.live.isFile).isTrue()
        assertThat(env.hrpGets()).isEqualTo(1)
        // Progress is throttled (250 ms), so a run this short may publish only its first phases.
        assertThat(published.first().getString(UpdateWork.KEY_PHASE)).isEqualTo(UpdateWork.PHASE_QUEUED)
    }

    @Test fun lessThanThreeGigabytesFreeRetriesWithoutAnyRequest() {
        env.seed("202610221934")
        env.freeBytes = UpdatePolicy.HRP_MIN_FREE_BYTES - 1
        assertThat(run()).isEqualTo(Result.retry())
        assertThat(env.hrpRequests()).isEqualTo(0)
        assertThat(live.exists()).isFalse()
    }

    @Test fun bytesAlreadyDownloadedCountTowardsTheFreeSpace() {
        env.seed("202610221934")
        env.hrpZip.parentFile!!.mkdirs()
        env.hrpZip.writeBytes(ByteArray(1000))
        env.freeBytes = UpdatePolicy.HRP_MIN_FREE_BYTES - 1000
        assertThat(run()).isEqualTo(Result.success())
        assertThat(env.patterns.patterns.value).isNotNull()
    }

    @Test fun sameEtagAsInstalledSucceedsWithoutADownload() {
        env.seed("202610221934")
        assertThat(run()).isEqualTo(Result.success())
        val installed = live.lastModified() to live.length()
        env.requests.clear()
        assertThat(run()).isEqualTo(Result.success())
        assertThat(env.hrpGets()).isEqualTo(0)
        assertThat(env.hrpRequests()).isEqualTo(1) // the HEAD
        assertThat(live.lastModified() to live.length()).isEqualTo(installed)
    }

    @Test fun sameEtagIsRecognisedInAFreshProcess() {
        env.seed("202610221934")
        assertThat(run()).isEqualTo(Result.success())
        env.patterns.close() // as if the process restarted: nothing loaded yet
        env.requests.clear()
        val fresh = PatternRepository(env.patternInstaller)
        val deps = object : HrpDeps by env.hrpDeps {
            override val patterns = fresh
        }
        val w = TestListenableWorkerBuilder<HrpWorker>(env.context)
            .setWorkerFactory(TowerUpdateWorkerFactory(env.deps, deps)).build()
        assertThat(runBlocking { w.doWork() }).isEqualTo(Result.success())
        assertThat(env.hrpGets()).isEqualTo(0)
        assertThat(fresh.patterns.value).isNotNull()
    }

    @Test fun aNewEtagReplacesTheInstalledPatterns() {
        env.seed("202610221934")
        assertThat(run()).isEqualTo(Result.success())
        val old = checkNotNull(env.patterns.patterns.value)
        env.hrpEtagOverride = "\"next-month\""
        assertThat(run()).isEqualTo(Result.success())
        val now = checkNotNull(env.patterns.patterns.value)
        assertThat(now.etag).isEqualTo("\"next-month\"")
        assertThat(old.isClosed).isTrue()
        assertThat(env.hrpGets()).isEqualTo(2)
    }

    @Test fun noTowerDataRetriesWithoutAnyRequest() {
        assertThat(run()).isEqualTo(Result.retry())
        assertThat(env.hrpRequests()).isEqualTo(0)
    }

    @Test fun aStoppedWorkerNeverLeavesABuildingFileThatGetsOpened() {
        env.seed("202610221934")
        val w = worker()
        env.onHrpLog = { if (it.startsWith("phase=towers ")) w.stop(WorkInfo.STOP_REASON_CONSTRAINT_CHARGING) }
        assertThat(runBlocking { w.doWork() }).isEqualTo(Result.retry())
        env.onHrpLog = {}
        assertThat(building.exists()).isFalse()
        assertThat(live.exists()).isFalse()
        assertThat(env.patterns.patterns.value).isNull()
        // The download survives the stop; only the import restarts.
        assertThat(env.hrpZip.length()).isEqualTo(env.hrpFixture.size.toLong())
        assertThat(sidecar.isFile).isTrue()

        // A fresh process finds nothing to open ...
        val fresh = PatternRepository(env.patternInstaller)
        runBlocking { fresh.start() }
        assertThat(fresh.patterns.value).isNull()
        // ... and the retry imports from the kept zip without downloading again.
        assertThat(run(attempt = 1)).isEqualTo(Result.success())
        assertThat(env.hrpGets()).isEqualTo(1)
        assertThat(env.patterns.patterns.value).isNotNull()
    }

    @Test fun aStaleBuildingFileFromACrashIsDeletedNotOpened() {
        env.seed("202610221934")
        building.writeText("left by a killed process")
        env.freeBytes = 0 // the run stops before downloading
        assertThat(run()).isEqualTo(Result.retry())
        assertThat(building.exists()).isFalse()
        assertThat(env.patterns.patterns.value).isNull()
    }

    // Ruling BB: a zip whose entry CRC does not match is discarded with its sidecar and fetched again.
    @Test fun crcMismatchDiscardsTheZipAndSidecarAndRetries() {
        env.seed("202610221934")
        env.hrpBytes = flipCrc(env.hrpFixture)
        assertThat(run()).isEqualTo(Result.retry())
        assertThat(env.hrpZip.exists()).isFalse()
        assertThat(sidecar.exists()).isFalse()
        assertThat(live.exists()).isFalse()
        assertThat(building.exists()).isFalse()

        env.hrpBytes = env.hrpFixture
        assertThat(run(attempt = 1)).isEqualTo(Result.success())
        assertThat(env.hrpGets()).isEqualTo(2)
    }

    // A short deflate stream makes the inflater throw EOFException (a plain IOException): the zip is
    // still bad, and its sidecar still matches, so it must be discarded or every retry reuses it.
    @Test fun truncatedDeflateDataDiscardsTheZipAndSidecarAndRetries() {
        env.seed("202610221934")
        env.hrpBytes = truncateDeflate(env.hrpFixture)
        assertThat(env.hrpBytes.size).isEqualTo(env.hrpFixture.size)
        assertThat(run()).isEqualTo(Result.retry())
        assertThat(env.hrpZip.exists()).isFalse()
        assertThat(sidecar.exists()).isFalse()
        assertThat(live.exists()).isFalse()
        assertThat(building.exists()).isFalse()

        env.hrpBytes = env.hrpFixture
        assertThat(run(attempt = 1)).isEqualTo(Result.success())
        assertThat(env.hrpGets()).isEqualTo(2)
    }

    // A stop that lands after the import returned but before the install must not leave the
    // complete candidate behind.
    @Test fun aStopRightAfterTheImportDeletesTheBuildingFile() {
        env.seed("202610221934")
        val w = worker()
        env.onHrpLog = { if (it.startsWith("phase=total ")) w.stop(WorkInfo.STOP_REASON_CONSTRAINT_CHARGING) }
        assertThat(runBlocking { w.doWork() }).isEqualTo(Result.retry())
        env.onHrpLog = {}
        assertThat(building.exists()).isFalse()
        assertThat(live.exists()).isFalse()
        assertThat(env.hrpZip.isFile).isTrue()
    }

    @Test fun anUnexpectedImportFailureRetriesAndKeepsTheZip() {
        env.seed("202610221934")
        env.onHrpLog = { if (it.startsWith("phase=towers ")) throw IllegalStateException("carrier count changed") }
        assertThat(run()).isEqualTo(Result.retry())
        env.onHrpLog = {}
        assertThat(env.hrpZip.isFile).isTrue()
        assertThat(run(attempt = UpdateWork.MAX_ATTEMPTS)).isEqualTo(Result.success())
    }

    // The CDN's edges mix versions: a GET answering with another ETag than the HEAD restarts from 0
    // with a fresh HEAD in the same run.
    @Test fun aVersionChangeBetweenHeadAndGetRestartsFromZeroInTheSameRun() {
        env.seed("202610221934")
        env.hrpGetEtags += "\"other-edge\""
        assertThat(run()).isEqualTo(Result.success())
        assertThat(env.hrpGets()).isEqualTo(2)
        assertThat(env.requests.count { it.url.encodedPath == "/hrp" && it.method == "HEAD" }).isEqualTo(2)
        assertThat(env.patterns.patterns.value!!.etag).isEqualTo(env.hrpEtag)
    }

    @Test fun aVersionThatKeepsChangingRetriesLater() {
        env.seed("202610221934")
        repeat(HrpWorker.VERSION_ATTEMPTS) { env.hrpGetEtags += "\"other-edge-$it\"" }
        assertThat(run()).isEqualTo(Result.retry())
        assertThat(env.hrpZip.exists()).isFalse()
        assertThat(sidecar.exists()).isFalse()
        assertThat(live.exists()).isFalse()
    }

    @Test fun sidecarRecordsEtagSizeAndLastModified() {
        env.seed("202610221934")
        val w = worker()
        env.onHrpLog = { if (it.startsWith("phase=towers ")) w.stop(WorkInfo.STOP_REASON_CANCELLED_BY_APP) }
        runBlocking { w.doWork() }
        assertThat(sidecar.readText())
            .isEqualTo("${env.hrpEtag}\n${env.hrpFixture.size}\n${env.hrpLastModified}")
    }

    @Test fun aChangedLastModifiedUnderTheSameEtagRestartsTheDownload() {
        env.seed("202610221934")
        val w = worker()
        env.onHrpLog = { if (it.startsWith("phase=towers ")) w.stop(WorkInfo.STOP_REASON_CANCELLED_BY_APP) }
        runBlocking { w.doWork() }
        env.onHrpLog = {}
        env.hrpLastModified = "Fri, 23 Oct 2026 01:00:00 GMT" // same ETag, different file
        assertThat(run(attempt = 1)).isEqualTo(Result.success())
        assertThat(env.hrpGets()).isEqualTo(2)
    }

    /** Shrinks the entry's compressed size in the central directory: same file length, short stream. */
    private fun truncateDeflate(zip: ByteArray): ByteArray {
        val b = zip.copyOf()
        val cd = (0 until b.size - 4).first {
            b[it] == 0x50.toByte() && b[it + 1] == 0x4b.toByte() && b[it + 2] == 1.toByte() && b[it + 3] == 2.toByte()
        }
        val half = (java.nio.ByteBuffer.wrap(b, cd + 20, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int) / 2
        java.nio.ByteBuffer.wrap(b, cd + 20, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(half)
        return b
    }

    private fun flipCrc(zip: ByteArray): ByteArray {
        val b = zip.copyOf()
        b[14] = (b[14].toInt() xor 1).toByte()
        val cd = (0 until b.size - 4).first {
            b[it] == 0x50.toByte() && b[it + 1] == 0x4b.toByte() && b[it + 2] == 1.toByte() && b[it + 3] == 2.toByte()
        }
        b[cd + 16] = (b[cd + 16].toInt() xor 1).toByte()
        return b
    }
}
