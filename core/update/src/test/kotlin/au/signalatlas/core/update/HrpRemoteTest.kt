package au.signalatlas.core.update

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Rulings BB/HH: the HRP download restarts from byte 0 whenever the remote version is in doubt: a
 * 412 on a pinned resume, or an ETag / Last-Modified / length different from the HEAD's.
 */
class HrpRemoteTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private lateinit var remote: HrpRemote
    private lateinit var dest: File

    private val body = ByteArray(600_000) { (it * 7 + it / 13).toByte() }
    private val etag = "0x8DF2EDC716D83D0"
    private val modified = "Tue, 20 Oct 2026 19:00:44 GMT"
    private val info = RemoteFile(etag, body.size.toLong(), modified)
    private val sidecar get() = File(dest.path + ".etag")

    @Before fun setUp() {
        server.start()
        remote = HrpRemote(OkHttpClient(), server.url("/hrp").toString())
        dest = File(tmp.root, "spectra_licence_hrp.zip")
    }

    @After fun tearDown() = server.close()

    private fun serve(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handler(request)
            }
        }
    }

    private fun full(tag: String = etag, lm: String = modified, bytes: ByteArray = body) =
        MockResponse.Builder().code(200).addHeader("ETag", tag).addHeader("Last-Modified", lm).body(Buffer().write(bytes)).build()

    private fun partial(from: Int, tag: String = etag, lm: String = modified) =
        MockResponse.Builder().code(206).addHeader("ETag", tag).addHeader("Last-Modified", lm)
            .addHeader("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
            .body(Buffer().write(body.copyOfRange(from, body.size))).build()

    /** A partial download of [info] as a stopped run leaves it. */
    private fun prefill(bytes: Int = 1000) {
        dest.writeBytes(body.copyOf(bytes))
        sidecar.writeText("$etag\n${body.size}\n$modified")
    }

    private val gets get() = requests.filter { it.method == "GET" }

    @Test fun infoReadsEtagLengthAndLastModified() = runBlocking<Unit> {
        serve { MockResponse.Builder().addHeader("ETag", etag).addHeader("Last-Modified", modified).addHeader("Content-Length", "1562523638").build() }
        assertThat(remote.info()).isEqualTo(RemoteFile(etag, 1_562_523_638L, modified))
    }

    @Test fun aResumeIsPinnedToTheVersion() = runBlocking<Unit> {
        prefill()
        serve { r -> if (r.headers["Range"] != null) partial(1000) else full() }
        remote.download(info, dest) { _, _ -> }
        assertThat(dest.readBytes()).isEqualTo(body)
        val resumed = gets.single()
        assertThat(resumed.headers["Range"]).isEqualTo("bytes=1000-")
        assertThat(resumed.headers["If-Range"]).isEqualTo(etag)
        assertThat(resumed.headers["If-Match"]).isEqualTo(etag)
        assertThat(resumed.headers["If-Unmodified-Since"]).isEqualTo(modified)
    }

    @Test fun a412OnResumeDiscardsThePartialAndRestartsFromZero() = runBlocking<Unit> {
        prefill()
        serve { r -> if (r.headers["Range"] != null) MockResponse.Builder().code(412).build() else full() }
        remote.download(info, dest) { _, _ -> }
        assertThat(dest.readBytes()).isEqualTo(body)
        assertThat(gets.map { it.headers["Range"] }).containsExactly("bytes=1000-", null).inOrder()
        assertThat(remote.downloaded(dest)).isEqualTo(info)
    }

    @Test fun anEtagChangeBetweenHeadAndGetDiscardsAndThrows() = runBlocking<Unit> {
        serve { full(tag = "0x8DF307A679241A2") }
        assertThat(runCatching { remote.download(info, dest) { _, _ -> } }.exceptionOrNull())
            .isInstanceOf(RemoteChangedException::class.java)
        assertThat(dest.exists()).isFalse()
        assertThat(sidecar.exists()).isFalse()
    }

    @Test fun aLastModifiedChangeBetweenHeadAndGetDiscardsAndThrows() = runBlocking<Unit> {
        serve { full(lm = "Thu, 22 Oct 2026 20:23:59 GMT") }
        assertThat(runCatching { remote.download(info, dest) { _, _ -> } }.exceptionOrNull())
            .isInstanceOf(RemoteChangedException::class.java)
        assertThat(dest.exists()).isFalse()
        assertThat(sidecar.exists()).isFalse()
    }

    @Test fun aLastModifiedChangeOnAResumeDiscardsThePartialAndThrows() = runBlocking<Unit> {
        prefill()
        serve { r -> if (r.headers["Range"] != null) partial(1000, lm = "Thu, 22 Oct 2026 20:23:59 GMT") else full() }
        assertThat(runCatching { remote.download(info, dest) { _, _ -> } }.exceptionOrNull())
            .isInstanceOf(RemoteChangedException::class.java)
        assertThat(dest.exists()).isFalse()
        assertThat(sidecar.exists()).isFalse()
    }

    @Test fun aDifferentLengthUnderTheSameEtagDiscardsAndThrows() = runBlocking<Unit> {
        serve { full(bytes = body.copyOf(body.size - 10)) }
        assertThat(runCatching { remote.download(info, dest) { _, _ -> } }.exceptionOrNull())
            .isInstanceOf(RemoteChangedException::class.java)
        assertThat(dest.exists()).isFalse()
        assertThat(sidecar.exists()).isFalse()
    }

    @Test fun aSidecarOfAnotherLastModifiedIsNotResumed() = runBlocking<Unit> {
        dest.writeBytes(body.copyOf(1000))
        sidecar.writeText("$etag\n${body.size}\nMon, 19 Oct 2026 00:00:00 GMT")
        serve { full() }
        remote.download(info, dest) { _, _ -> }
        assertThat(dest.readBytes()).isEqualTo(body)
        assertThat(gets.single().headers["Range"]).isNull()
    }
}
