package au.signalatlas.core.update

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class AcmaRemoteTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private lateinit var remote: AcmaRemote
    private lateinit var dest: File

    private val etag = "0x8DF30174D05C576"
    private val body = ByteArray(700_000) { (it * 31 + it / 251).toByte() }
    private val info get() = RemoteInfo("202610221934", etag, body.size.toLong())

    @Before fun setUp() {
        server.start()
        remote = AcmaRemote(OkHttpClient(), server.url("/zip").toString(), server.url("/stamp").toString())
        dest = File(tmp.root, "spectra_rrl.zip")
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

    private val sidecar get() = File(dest.path + ".etag")

    /** Sidecar lines: ETag, size, Last-Modified (not tracked for the RRL zip). */
    private fun prefill(content: ByteArray, tag: String? = etag) {
        dest.writeBytes(content)
        if (tag != null) sidecar.writeText("$tag\n${body.size}\n")
    }

    private fun bytes(b: ByteArray) = Buffer().write(b)

    private fun ok(b: ByteArray, tag: String? = etag) = MockResponse.Builder().code(200)
        .apply { if (tag != null) addHeader("ETag", tag) }.body(bytes(b)).build()

    /** A server that honours Range/If-Range for [content], like a CDN would. */
    private fun rangeServer(content: ByteArray = body) = serve { r ->
        val range = r.headers["Range"]
        if (range != null && r.headers["If-Range"] == etag) {
            val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
            MockResponse.Builder().code(206).addHeader("ETag", etag)
                .addHeader("Content-Range", "bytes $from-${content.size - 1}/${content.size}")
                .body(bytes(content.copyOfRange(from, content.size))).build()
        } else ok(content)
    }

    @Test fun infoParsesStampAndHead() = runTest {
        serve { r ->
            if (r.url.encodedPath == "/stamp") MockResponse.Builder().body("202610221934\n").build()
            else MockResponse.Builder().addHeader("ETag", etag).addHeader("Content-Length", "68350756").build()
        }
        val i = remote.info()
        assertThat(i).isEqualTo(RemoteInfo("202610221934", etag, 68350756L))
        assertThat(requests.map { it.method }).containsExactly("GET", "HEAD").inOrder()
    }

    @Test fun infoKeepsQuotedEtagVerbatim() = runTest {
        serve { r ->
            if (r.url.encodedPath == "/stamp") MockResponse.Builder().body("202610221934").build()
            else MockResponse.Builder().addHeader("ETag", "W/\"abc\"").addHeader("Content-Length", "10").build()
        }
        assertThat(remote.info().etag).isEqualTo("W/\"abc\"")
    }

    @Test fun htmlStampIsRejected() = runTest {
        serve { MockResponse.Builder().body("<html><body>Maintenance</body></html>").build() }
        assertThrows(RemoteFormatException::class.java) { runBlocking { remote.info() } }
    }

    @Test fun headWithoutEtagIsRejected() = runTest {
        serve { r ->
            if (r.url.encodedPath == "/stamp") MockResponse.Builder().body("202610221934").build()
            else MockResponse.Builder().addHeader("Content-Length", "10").build()
        }
        assertThrows(RemoteFormatException::class.java) { runBlocking { remote.info() } }
    }

    @Test fun nonSuccessStatusIsIoExceptionWithCode() = runTest {
        serve { MockResponse.Builder().code(503).build() }
        val e = assertThrows(IOException::class.java) { runBlocking { remote.info() } }
        assertThat(e).isNotInstanceOf(RemoteFormatException::class.java)
        assertThat(e.message).contains("503")
    }

    @Test fun freshDownloadWritesAllBytesAndReportsProgress() = runTest {
        serve { ok(body) }
        val progress = mutableListOf<Pair<Long, Long>>()
        remote.download(info, dest) { b, t -> progress += b to t }
        assertThat(dest.readBytes()).isEqualTo(body)
        assertThat(progress.last()).isEqualTo(body.size.toLong() to body.size.toLong())
        assertThat(progress.map { it.first }).isInOrder()
        // 700 000 bytes at 256 KiB steps: a couple of intermediate reports, not one per buffer.
        assertThat(progress.size).isAtMost(5)
        assertThat(requests.single().headers["Range"]).isNull()
    }

    @Test fun resumesWithRangeAndIfRange() = runTest {
        prefill(body.copyOf(1000))
        rangeServer()
        remote.download(info, dest) { _, _ -> }
        assertThat(dest.readBytes()).isEqualTo(body)
        val ranged = requests.filter { it.headers["Range"] != null }
        assertThat(ranged).hasSize(1)
        assertThat(ranged.single().headers["Range"]).isEqualTo("bytes=1000-")
        assertThat(ranged.single().headers["If-Range"]).isEqualTo(etag)
    }

    @Test fun freshDownloadWithChangedEtagThrowsAndLeavesNothing() = runTest {
        serve { ok(body, tag = "0xNEW") }
        assertThrows(RemoteChangedException::class.java) { runBlocking { remote.download(info, dest) { _, _ -> } } }
        assertThat(dest.exists()).isFalse()
        assertThat(sidecar.exists()).isFalse()
    }

    @Test fun resumeAnswered200WithChangedEtagThrowsAndDeletesPartial() = runTest {
        prefill(body.copyOf(1000))
        serve { ok(body, tag = "0xNEW") }
        assertThrows(RemoteChangedException::class.java) { runBlocking { remote.download(info, dest) { _, _ -> } } }
        assertThat(dest.exists()).isFalse()
        assertThat(sidecar.exists()).isFalse()
    }

    @Test fun differentEtagOn206ThrowsAndDeletesPartial() = runTest {
        prefill(body.copyOf(1000))
        serve {
            MockResponse.Builder().code(206).addHeader("ETag", "0xOTHER")
                .addHeader("Content-Range", "bytes 1000-${body.size - 1}/${body.size}")
                .body(bytes(body.copyOfRange(1000, body.size))).build()
        }
        assertThrows(RemoteChangedException::class.java) { runBlocking { remote.download(info, dest) { _, _ -> } } }
        assertThat(dest.exists()).isFalse()
        assertThat(sidecar.exists()).isFalse()
    }

    @Test fun partialFromOlderVersionIsNeverSpliced() = runTest {
        val v1 = ByteArray(body.size) { (it * 5 + 1).toByte() }
        prefill(v1.copyOf(1000), tag = "0xV1")
        rangeServer() // honours Range for V2 (etag matches info)
        remote.download(info, dest) { _, _ -> }
        assertThat(dest.readBytes()).isEqualTo(body)
        assertThat(requests.none { it.headers["Range"] != null }).isTrue()
        assertThat(sidecar.readText()).isEqualTo("$etag\n${body.size}\n")
    }

    @Test fun partialWithoutSidecarIsRestarted() = runTest {
        prefill(body.copyOf(1000), tag = null)
        rangeServer()
        remote.download(info, dest) { _, _ -> }
        assertThat(dest.readBytes()).isEqualTo(body)
        assertThat(requests.none { it.headers["Range"] != null }).isTrue()
    }

    @Test fun sameSizeFileWithMismatchedOrMissingSidecarIsRedownloaded() = runTest {
        val old = ByteArray(body.size) { 4 }
        for (tag in listOf("0xV1", null)) {
            requests.clear()
            sidecar.delete()
            prefill(old, tag = tag)
            serve { ok(body) }
            remote.download(info, dest) { _, _ -> }
            assertThat(dest.readBytes()).isEqualTo(body)
            assertThat(requests).hasSize(1)
        }
    }

    @Test fun oneLineSidecarFromAnOlderVersionIsNotTrustedForResume() = runTest {
        dest.writeBytes(body.copyOf(1000))
        sidecar.writeText(etag)
        rangeServer()
        remote.download(info, dest) { _, _ -> }
        assertThat(dest.readBytes()).isEqualTo(body)
        assertThat(requests.none { it.headers["Range"] != null }).isTrue()
    }

    @Test fun discardDeletesFileAndSidecar() {
        prefill(body)
        remote.discard(dest)
        assertThat(dest.exists()).isFalse()
        assertThat(sidecar.exists()).isFalse()
    }

    @Test fun cancellationMidStreamPropagatesAsCancellationAndKeepsPartial() = runBlocking {
        serve {
            MockResponse.Builder().code(200).addHeader("ETag", etag).body(bytes(body))
                .throttleBody(20_000, 100, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        }
        val outcome = kotlinx.coroutines.CompletableDeferred<Throwable?>()
        val job = launch(Dispatchers.IO) {
            try {
                remote.download(info, dest) { _, _ -> }
                outcome.complete(null)
            } catch (t: Throwable) {
                outcome.complete(t)
                throw t
            }
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (dest.length() == 0L && System.currentTimeMillis() < deadline) delay(20)
        assertThat(dest.length()).isGreaterThan(0L)
        job.cancelAndJoin()
        assertThat(outcome.await()).isInstanceOf(CancellationException::class.java)
        assertThat(dest.length()).isLessThan(body.size.toLong())
        assertThat(sidecar.readText()).isEqualTo("$etag\n${body.size}\n")
    }

    @Test fun contentRangeStartMismatchRestartsFromZero() = runTest {
        prefill(body.copyOf(1000))
        serve { r ->
            if (r.headers["Range"] != null) {
                MockResponse.Builder().code(206).addHeader("ETag", etag)
                    .addHeader("Content-Range", "bytes 500-${body.size - 1}/${body.size}")
                    .body(bytes(body.copyOfRange(500, body.size))).build()
            } else ok(body)
        }
        remote.download(info, dest) { _, _ -> }
        assertThat(dest.readBytes()).isEqualTo(body)
    }

    @Test fun completeFileMakesNoRequest() = runTest {
        prefill(body)
        serve { ok(body) }
        var last: Pair<Long, Long>? = null
        remote.download(info, dest) { b, t -> last = b to t }
        assertThat(requests).isEmpty()
        assertThat(last).isEqualTo(body.size.toLong() to body.size.toLong())
    }

    @Test fun oversizedDestIsDiscardedAndRestarted() = runTest {
        prefill(ByteArray(body.size + 50) { 9 })
        serve { ok(body) }
        remote.download(info, dest) { _, _ -> }
        assertThat(dest.readBytes()).isEqualTo(body)
        assertThat(requests.single().headers["Range"]).isNull()
    }

    @Test fun truncatedResponseThrowsAndKeepsPartialFile() = runTest {
        serve {
            MockResponse.Builder().code(200).addHeader("ETag", etag)
                .setHeader("Content-Length", body.size.toString())
                .body(bytes(body.copyOf(300_000)))
                .onResponseEnd(SocketEffect.ShutdownConnection).build()
        }
        assertThrows(IOException::class.java) { runBlocking { remote.download(info, dest) { _, _ -> } } }
        assertThat(dest.length()).isGreaterThan(0L)
        assertThat(dest.length()).isLessThan(body.size.toLong())
        assertThat(dest.readBytes()).isEqualTo(body.copyOf(dest.length().toInt()))

        // The next attempt resumes from the kept partial file.
        val partial = dest.length()
        requests.clear()
        rangeServer()
        remote.download(info, dest) { _, _ -> }
        assertThat(dest.readBytes()).isEqualTo(body)
        assertThat(requests.single().headers["Range"]).isEqualTo("bytes=$partial-")
    }
}
