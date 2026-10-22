package au.signalatlas.core.update

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import au.signalatlas.core.acma.AcmaImporter
import au.signalatlas.core.data.DbInstaller
import au.signalatlas.core.data.DbValidator
import au.signalatlas.core.data.Validation
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/** A fake ACMA (stamp + zip over mockwebserver3) plus a real installer/repository in [root]. */
class UpdateTestEnv(root: File, minSites: Int = 1) : AutoCloseable {
    val context: Context = ApplicationProvider.getApplicationContext()
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    val fixture: ByteArray = checkNotNull(javaClass.getResourceAsStream("/acma/fixture.zip")).use { it.readBytes() }
    @Volatile var stamp = "202610221934"
    @Volatile var zipBytes: ByteArray = fixture
    val etag get() = "\"zip-${zipBytes.contentHashCode()}\""

    val workDir = File(root, "towers")
    val installer = DbInstaller(workDir, DbValidator(minSites = minSites))
    val repo = TowerRepository(installer)
    val importer = AcmaImporter()
    val notifications = UpdateNotifications(context)
    val zip = File(workDir, "download/spectra_rrl.zip")

    init {
        workDir.mkdirs()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
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

    /** Installs the fixture as the live DB with [stamp] (whatever [installer]'s minimum) and opens it through [repo]. */
    fun seed(stamp: String) {
        val building = File(workDir, "seed.building")
        importer.import(zipFileOf(fixture), building, stamp, "\"seed\"")
        check(DbInstaller(workDir, DbValidator(minSites = 1)).install(building) == Validation.Ok)
        runBlocking { repo.start() }
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
        repo.store.value?.close()
        server.close()
    }
}

class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}
