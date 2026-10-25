package au.signalatlas.core.roads

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * HTTP range reads of one archive, pinned to the ETag of the first response: later reads send `If-Match` and must come
 * back with the same ETag, otherwise the file was replaced under us and [PmtilesException] says to start again.
 */
class HttpRangeSource(private val client: OkHttpClient, private val url: String) : RangeSource {
    @Volatile
    var etag: String? = null
        private set

    @Throws(IOException::class)
    override fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length > 0)
        val pinned = etag
        val req = Request.Builder().url(url)
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .apply { if (pinned != null && !pinned.startsWith("W/")) header("If-Match", pinned) }
            .build()
        client.newCall(req).execute().use { resp ->
            when {
                resp.code == 412 -> throw PmtilesException("archive changed (412)")
                resp.code == 416 -> return ByteArray(0)
                resp.code != 206 -> throw IOException("range read of $url: HTTP ${resp.code}")
            }
            val tag = resp.header("ETag")
            if (pinned != null && tag != null && tag != pinned) throw PmtilesException("archive changed (ETag $tag)")
            if (pinned == null) etag = tag
            val start = Regex("bytes (\\d+)-").find(resp.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
            if (start != offset) throw IOException("range read of $url answered from $start, not $offset")
            val body = resp.body.bytes()
            return if (body.size > length) body.copyOf(length) else body
        }
    }
}
