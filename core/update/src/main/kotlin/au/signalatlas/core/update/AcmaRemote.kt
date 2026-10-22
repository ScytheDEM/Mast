package au.signalatlas.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Network side of the ACMA update flow: a cheap "is there newer data?" check and a resumable
 * download of the export zip. It never retries (WorkManager owns that) and never splices two
 * different files: any doubt about the remote's identity restarts the download at byte 0.
 */
class AcmaRemote(
    private val http: OkHttpClient,
    private val zipUrl: String = ZIP_URL,
    private val stampUrl: String = STAMP_URL,
) {
    companion object {
        const val ZIP_URL = "https://cdn.acma.gov.au/rrl/spectra_rrl.zip"
        const val STAMP_URL = "https://backend.acma.gov.au/rrl/v1/extracts/datetime-of-extract.txt"

        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 256L * 1024
        private val STAMP = Regex("""^\d{12}$""")
        private val CONTENT_RANGE = Regex("""^bytes (\d+)-(\d+)/(\d+)$""")
    }

    /** Fetches the extract stamp (GET) and the zip's ETag and size (HEAD). */
    suspend fun info(): RemoteInfo {
        val stamp = call(Request.Builder().url(stampUrl).get().build()) { response ->
            requireSuccess(response)
            response.body.string().trim()
        }
        if (!STAMP.matches(stamp)) throw RemoteFormatException("Unexpected extract stamp: ${stamp.take(40)}")

        return call(Request.Builder().url(zipUrl).head().build()) { response ->
            requireSuccess(response)
            val etag = response.header("ETag") ?: throw RemoteFormatException("Zip response has no ETag")
            val size = response.header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                ?: throw RemoteFormatException("Zip response has no usable Content-Length")
            RemoteInfo(stamp, etag, size)
        }
    }

    /**
     * Downloads the zip into [dest]. Resumes a partial [dest] when the server honours Range with 206
     * for the same ETag; otherwise restarts at byte 0. Throws [IOException] (keeping the partial file)
     * if the final length differs from [RemoteInfo.sizeBytes].
     */
    suspend fun download(info: RemoteInfo, dest: File, onProgress: (bytes: Long, total: Long) -> Unit) {
        val total = info.sizeBytes
        var existing = if (dest.isFile) dest.length() else 0L
        if (existing == total) {
            onProgress(total, total)
            return
        }
        if (existing > total) {
            dest.delete()
            existing = 0L
        }
        val resumeFrom = if (existing in 1 until total) existing else 0L
        if (!fetch(info, dest, resumeFrom, onProgress)) {
            // The server did not offer the same file at the offset we asked for: start over.
            check(fetch(info, dest, 0L, onProgress)) { "A download from byte 0 never asks to restart" }
        }
    }

    /** Returns false when a resumed attempt must be redone from byte 0 (nothing was written). */
    private suspend fun fetch(info: RemoteInfo, dest: File, offset: Long, onProgress: (Long, Long) -> Unit): Boolean {
        val total = info.sizeBytes
        val request = Request.Builder().url(zipUrl).get()
            // Byte-exact transfer: no transparent decompression of the zip.
            .header("Accept-Encoding", "identity")
            .apply {
                if (offset > 0) {
                    header("Range", "bytes=$offset-")
                    header("If-Range", info.etag)
                }
            }
            .build()
        return call(request) { response ->
            val append: Boolean
            when {
                response.code == 206 && offset > 0 -> {
                    val range = response.header("Content-Range")?.let { CONTENT_RANGE.matchEntire(it.trim()) }
                    val sameFile = response.header("ETag").let { it == null || it == info.etag }
                    if (range == null || !sameFile ||
                        range.groupValues[1].toLongOrNull() != offset || range.groupValues[3].toLongOrNull() != total
                    ) {
                        return@call false
                    }
                    append = true
                }
                response.code == 200 -> append = false // Range ignored or If-Range failed: whole body from 0.
                response.code == 416 && offset > 0 -> return@call false
                else -> throw IOException("Download failed: HTTP ${response.code}")
            }

            val start = if (append) offset else 0L
            var written = start
            var lastReported = start
            val buffer = ByteArray(BUFFER_BYTES)
            FileOutputStream(dest, append).use { out ->
                val source = response.body.byteStream()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = source.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    written += n
                    if (written - lastReported >= PROGRESS_STEP_BYTES) {
                        lastReported = written
                        onProgress(written, total)
                    }
                }
            }
            if (dest.length() != total) {
                throw IOException("Download incomplete: ${dest.length()} of $total bytes")
            }
            onProgress(total, total)
            true
        }
    }

    private fun requireSuccess(response: Response) {
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${response.request.url}")
    }

    /** Runs the blocking call on IO; cancelling the coroutine cancels the OkHttp call. */
    private suspend fun <T> call(request: Request, block: suspend (Response) -> T): T = withContext(Dispatchers.IO) {
        val call = http.newCall(request)
        coroutineScope {
            // Suspended until this scope is cancelled; then unblocks the thread stuck in OkHttp I/O.
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                call.execute().use { block(it) }
            } finally {
                watcher.cancel()
            }
        }
    }
}
