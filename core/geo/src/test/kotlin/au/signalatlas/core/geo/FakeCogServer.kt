package au.signalatlas.core.geo

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okio.Buffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A GLO-90 bucket on a [MockWebServer]: files by COG name (`S36_00_E148_00`), each with an ETag; honours Range and
 * If-Match; anything else is 404. [requests] records every request. [intercept] can answer instead of the bucket.
 */
class FakeCogServer {
    class Version(val bytes: ByteArray, val etag: String)

    val server = MockWebServer()
    val files = HashMap<String, Version>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** Return a response to override the normal handling of a request, or null. */
    @Volatile var intercept: (RecordedRequest, String) -> MockResponse? = { _, _ -> null }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val name = Regex("/Copernicus_DSM_COG_30_([^/]*)_DEM/").find(request.url.encodedPath)?.groupValues?.get(1)
                    ?: return MockResponse.Builder().code(404).build()
                intercept(request, name)?.let { return it }
                val v = files[name] ?: return MockResponse.Builder().code(404).build()
                return serve(request, v)
            }
        }
        server.start()
    }

    fun close() = server.close()

    fun put(name: String, bytes: ByteArray, etag: String) {
        files[name] = Version(bytes, etag)
    }

    fun serve(request: RecordedRequest, v: Version, truncateAfter: Int? = null, ignoreIfMatch: Boolean = false): MockResponse {
        val ifMatch = request.headers["If-Match"]
        if (!ignoreIfMatch && ifMatch != null && ifMatch != v.etag) return MockResponse.Builder().code(412).addHeader("ETag", v.etag).build()
        val m = Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.headers["Range"] ?: "")
            ?: return MockResponse.Builder().code(200).addHeader("ETag", v.etag).body(Buffer().write(v.bytes)).build()
        val start = m.groupValues[1].toInt()
        val end = minOf(m.groupValues[2].toInt(), v.bytes.size - 1)
        val body = v.bytes.copyOfRange(start, end + 1)
        val b = MockResponse.Builder().code(206).addHeader("ETag", v.etag)
            .addHeader("Content-Range", "bytes $start-$end/${v.bytes.size}")
        if (truncateAfter != null) {
            // The full length is declared (after body(), which sets the truncated one), then the connection drops.
            b.body(Buffer().write(body, 0, truncateAfter)).setHeader("Content-Length", body.size.toString())
                .onResponseEnd(SocketEffect.ShutdownConnection)
        } else {
            b.body(Buffer().write(body))
        }
        return b.build()
    }

    /** Requests that fetch tile bytes (everything except the 64 KiB header read at offset 0). */
    fun tileRequests() = requests.filter { (it.headers["Range"] ?: "").let { r -> r.startsWith("bytes=") && !r.startsWith("bytes=0-") } }

    fun headerRequests() = requests.filter { it.headers["Range"]?.startsWith("bytes=0-") == true }
}
