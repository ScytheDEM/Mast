package au.signalatlas.core.update

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import au.signalatlas.core.acma.AcmaImporter
import au.signalatlas.core.acma.hrp.HrpImporter
import au.signalatlas.core.data.DbInstaller
import au.signalatlas.core.data.DbValidator
import au.signalatlas.core.data.PatternInstaller
import au.signalatlas.core.data.PatternRepository
import au.signalatlas.core.data.TowerRepository
import au.signalatlas.core.data.Validation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A fake ACMA (stamp + RRL zip + HRP zip over mockwebserver3) plus real installers/repositories in
 * [root]: towers.db and patterns.db side by side in [workDir], as in the app.
 */
class UpdateTestEnv(
    root: File,
    minSites: Int = 1,
    repoScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {
    val context: Context = ApplicationProvider.getApplicationContext()
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    val fixture: ByteArray = checkNotNull(javaClass.getResourceAsStream("/acma/fixture.zip")).use { it.readBytes() }
    @Volatile var stamp = "202610221934"
    @Volatile var zipBytes: ByteArray = fixture
    val etag get() = "\"zip-${zipBytes.contentHashCode()}\""

    val workDir = File(root, "towers")
    /** Called with every importer timing line (`phase=<name> ms=<n>`), on the importing thread. */
    @Volatile var onImportLog: (String) -> Unit = {}
    /** Called just before [installer] renames a candidate into place. */
    @Volatile var beforeInstallMove: () -> Unit = {}
    /** How many candidates [installer] has renamed into place. */
    val installs = AtomicInteger()
    val installer = DbInstaller(workDir, DbValidator(minSites = minSites)) { from, to ->
        if (from.fileName.toString().endsWith(".building")) {
            beforeInstallMove()
            installs.incrementAndGet()
        }
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    val repo = TowerRepository(installer, repoScope)
    val importer = AcmaImporter(log = { onImportLog(it) })
    val notifications = UpdateNotifications(context)
    val zip = File(workDir, "download/spectra_rrl.zip")

    // --- HRP (patterns) ---
    val hrpFixture: ByteArray = checkNotNull(javaClass.getResourceAsStream("/acma/hrp_fixture.zip")).use { it.readBytes() }
    @Volatile var hrpBytes: ByteArray = hrpFixture
    /** Null: derived from [hrpBytes]. */
    @Volatile var hrpEtagOverride: String? = null
    val hrpEtag get() = hrpEtagOverride ?: "\"hrp-${hrpBytes.contentHashCode()}\""
    @Volatile var hrpLastModified = "Tue, 20 Oct 2026 19:00:44 GMT"
    /** ETags the next HRP GETs answer with instead of [hrpEtag] (one per GET), as a CDN edge serving another version. */
    val hrpGetEtags = java.util.concurrent.ConcurrentLinkedQueue<String>()
    /** Free bytes the HRP worker sees (fake StatFs). */
    @Volatile var freeBytes: Long = 64L shl 30
    /** Called with every HRP importer timing line, on the importing thread. */
    @Volatile var onHrpLog: (String) -> Unit = {}
    val patternInstaller = PatternInstaller(workDir)
    val patterns = PatternRepository(patternInstaller)
    val hrpZip = File(workDir, "download/spectra_licence_hrp.zip")

    init {
        workDir.mkdirs()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (request.url.encodedPath == "/hrp") {
                    val tag = if (request.method == "GET") hrpGetEtags.poll() ?: hrpEtag else hrpEtag
                    val r = MockResponse.Builder().addHeader("ETag", tag).addHeader("Last-Modified", hrpLastModified)
                    return if (request.method == "HEAD") {
                        r.addHeader("Content-Length", hrpBytes.size.toString()).build()
                    } else {
                        r.body(Buffer().write(hrpBytes)).build()
                    }
                }
                return when {
                    request.url.encodedPath == "/stamp" -> MockResponse.Builder().body(stamp + "\n").build()
                    request.method == "HEAD" -> MockResponse.Builder()
                        .addHeader("ETag", etag).addHeader("Content-Length", zipBytes.size.toString()).build()
                    else -> MockResponse.Builder().addHeader("ETag", etag).body(Buffer().write(zipBytes)).build()
                }
            }
        }
        server.start()
    }

    val remote = AcmaRemote(OkHttpClient(), server.url("/zip").toString(), server.url("/stamp").toString())

    val deps = object : UpdateDeps {
        override val remote get() = this@UpdateTestEnv.remote
        override val repo get() = this@UpdateTestEnv.repo
        override val importer get() = this@UpdateTestEnv.importer
        override val installer get() = this@UpdateTestEnv.installer
        override val workDir get() = this@UpdateTestEnv.workDir
        override val notifications get() = this@UpdateTestEnv.notifications
    }

    val hrpRemote = HrpRemote(OkHttpClient(), server.url("/hrp").toString())

    val hrpDeps = object : HrpDeps {
        override val remote get() = this@UpdateTestEnv.hrpRemote
        override val importer = HrpImporter(log = { onHrpLog(it) })
        override val installer get() = this@UpdateTestEnv.patternInstaller
        override val patterns get() = this@UpdateTestEnv.patterns
        override val towersDb get() = this@UpdateTestEnv.installer.live
        override val workDir get() = this@UpdateTestEnv.workDir
        override val notifications get() = this@UpdateTestEnv.notifications
        override val freeBytes: (File) -> Long = { this@UpdateTestEnv.freeBytes }
    }

    fun hrpRequests() = requests.count { it.url.encodedPath == "/hrp" }
    fun hrpGets() = requests.count { it.url.encodedPath == "/hrp" && it.method == "GET" }

    /** Installs the fixture as the live DB with [stamp] (whatever [installer]'s minimum) and opens it through [repo]. */
    fun seed(stamp: String) {
        installOnly(stamp)
        runBlocking { repo.start() }
    }

    /** Installs the fixture as the live DB with [stamp] without telling [repo] (as a worker stopped before its reload). */
    fun installOnly(stamp: String) {
        val building = File(workDir, "seed.building")
        AcmaImporter().import(zipFileOf(fixture), building, stamp, "\"seed\"")
        check(DbInstaller(workDir, DbValidator(minSites = 1)).install(building) == Validation.Ok)
    }

    private fun zipFileOf(bytes: ByteArray): File =
        File.createTempFile("fixture", ".zip", workDir.parentFile).apply { writeBytes(bytes) }

    fun zipRequests() = requests.count { it.url.encodedPath == "/zip" && it.method == "GET" }

    /** Polls [read] (running the Robolectric main looper in between) until [done] holds. */
    fun <T> await(read: () -> T, timeoutMs: Long = 10_000, done: (T) -> Boolean): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val v = read()
            if (done(v)) return v
            check(System.currentTimeMillis() < end) { "timed out; last value: $v" }
            Thread.sleep(10)
        }
    }

    override fun close() {
        repo.close()
        patterns.close()
        server.close()
    }
}

class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}
