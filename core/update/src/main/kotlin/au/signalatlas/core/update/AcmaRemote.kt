package au.signalatlas.core.update

import kotlinx.coroutines.CoroutineStart
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

    /** Deletes [dest] and its provenance sidecar (call after a successful import). */
    fun discard(dest: File) {
        dest.delete()
        sidecarOf(dest).delete()
    }

    /** True when [dest] holds the whole file for [info] (right ETag and length). */
    fun isComplete(info: RemoteInfo, dest: File): Boolean =
        dest.isFile && dest.length() == info.sizeBytes && downloadedEtag(dest) == info.etag

    /** The ETag the bytes in [dest] belong to, or null if unknown. */
    fun downloadedEtag(dest: File): String? = sidecarOf(dest).takeIf { it.isFile }?.readText()

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
        val sidecar = sidecarOf(dest)
        var existing = if (dest.isFile) dest.length() else 0L
        // A partial (or finished) file is only trusted if it provably came from this exact ETag.
        if (existing > 0 && !(sidecar.isFile && sidecar.readText() == info.etag)) {
            discard(dest)
            existing = 0L
        }
        if (existing == total) {
            onProgress(total, total)
            return
        }
        if (existing > total) {
            discard(dest)
            existing = 0L
        }
        val resumeFrom = if (existing in 1 until total) existing else 0L
        if (!fetch(info, dest, resumeFrom, onProgress)) {
            // Same ETag but not the byte range we asked for: start over.
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
            if (response.code != 200 && !(response.code == 206 && offset > 0) && !(response.code == 416 && offset > 0)) {
                throw IOException("Download failed: HTTP ${response.code}")
            }
            if (response.code != 416) {
                val served = response.header("ETag")
                if (served != null && served != info.etag) {
                    discard(dest)
                    throw RemoteChangedException("Remote file changed during download (ETag $served, expected ${info.etag})")
                }
            }
            val append: Boolean
            when (response.code) {
                206 -> {
                    val range = response.header("Content-Range")?.let { CONTENT_RANGE.matchEntire(it.trim()) }
                    if (range == null ||
                        range.groupValues[1].toLongOrNull() != offset || range.groupValues[3].toLongOrNull() != total
                    ) {
                        return@call false
                    }
                    append = true
                }
                200 -> append = false // Range ignored or If-Range failed: whole body from 0.
                else -> return@call false // 416
            }
            if (!append) writeSidecar(dest, info.etag)

            val start = if (append) offset else 0L
            var written = start
            var lastReported = start
            val buffer = ByteArray(BUFFER_BYTES)
            try {
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
            } catch (e: IOException) {
                // call.cancel() surfaces as IOException("Canceled"); report the cancellation instead.
                currentCoroutineContext().ensureActive()
                throw e
            }
            if (dest.length() != total) {
                throw IOException("Download incomplete: ${dest.length()} of $total bytes")
            }
            onProgress(total, total)
            true
        }
    }

    private fun sidecarOf(dest: File) = File(dest.path + ".etag")

    /** Records which ETag the bytes in [dest] belong to, durably, before any byte is written. */
    private fun writeSidecar(dest: File, etag: String) {
        FileOutputStream(sidecarOf(dest)).use { out ->
            out.write(etag.toByteArray())
            out.flush()
            out.fd.sync()
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
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                try {
                    call.execute().use { block(it) }
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                }
            } finally {
                watcher.cancel()
            }
        }
    }
}
