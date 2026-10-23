package au.signalatlas.core.update

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Which version of a remote file some bytes belong to: its ETag (opaque, quotes kept), length and,
 * when the server sends one, `Last-Modified`. Ruling BB: an ETag alone is not trusted to identify
 * the bytes (the ACMA CDN has served two different zips under one ETag).
 */
data class RemoteFile(val etag: String, val sizeBytes: Long, val lastModified: String? = null)

/**
 * A resumable, byte-exact download of one URL into a local file, shared by [AcmaRemote] and
 * [HrpRemote]. It never retries (WorkManager owns that) and never splices two versions: the bytes in
 * `dest` are only resumed when the sidecar `<dest>.etag` records exactly the [RemoteFile] asked for,
 * and the server confirms it. A resumed request is pinned (`If-Range`, `If-Match`,
 * `If-Unmodified-Since`); a 412 discards the partial file and restarts from byte 0. Any answer
 * whose ETag, Last-Modified or length differs from the [RemoteFile] discards [dest] and throws
 * [RemoteChangedException] (the CDN's edges have served two versions at once): the caller re-reads
 * the version (HEAD) and starts again from 0.
 *
 * Sidecar format: three lines, ETag, size, Last-Modified (empty when unknown). It is written and
 * synced before the first byte, so the provenance of a partial file is never lost.
 */
internal class ResumableDownload(private val http: OkHttpClient, private val url: String) {

    /** Deletes [dest] and its provenance sidecar. */
    fun discard(dest: File) {
        dest.delete()
        sidecarOf(dest).delete()
    }

    /** The version the bytes in [dest] belong to, or null if unknown. */
    fun downloaded(dest: File): RemoteFile? {
        val sidecar = sidecarOf(dest)
        if (!sidecar.isFile) return null
        val lines = sidecar.readText().split('\n')
        val size = lines.getOrNull(1)?.toLongOrNull() ?: return null
        return RemoteFile(lines[0], size, lines.getOrNull(2)?.takeIf { it.isNotEmpty() })
    }

    /** The ETag recorded for [dest] (also for an older one-line sidecar), or null. */
    fun downloadedEtag(dest: File): String? =
        sidecarOf(dest).takeIf { it.isFile }?.readText()?.substringBefore('\n')

    /** True when [dest] holds the whole of [file]. */
    fun isComplete(file: RemoteFile, dest: File): Boolean =
        dest.isFile && dest.length() == file.sizeBytes && downloaded(dest) == file

    /** HEAD: the current version. Throws [RemoteFormatException] without a usable ETag or Content-Length. */
    suspend fun head(): RemoteFile = http.cancellableCall(Request.Builder().url(url).head().build()) { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${response.request.url}")
        val etag = response.header("ETag") ?: throw RemoteFormatException("Zip response has no ETag")
        val size = response.header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw RemoteFormatException("Zip response has no usable Content-Length")
        RemoteFile(etag, size, response.header("Last-Modified"))
    }

    /**
     * Downloads [file] into [dest], resuming a partial [dest] of exactly this version when the
     * server honours Range with 206; otherwise from byte 0. Throws [RemoteChangedException] (and
     * discards [dest]) when the server answers with another version, and [IOException] (keeping the
     * partial file) if the final length differs.
     */
    suspend fun download(file: RemoteFile, dest: File, onProgress: (bytes: Long, total: Long) -> Unit) {
        val total = file.sizeBytes
        var existing = if (dest.isFile) dest.length() else 0L
        // A partial (or finished) file is only trusted if it provably came from this exact version.
        if (existing > 0 && downloaded(dest) != file) {
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
        if (!fetch(file, dest, resumeFrom, onProgress)) {
            // Same version but not the byte range we asked for: start over.
            check(fetch(file, dest, 0L, onProgress)) { "A download from byte 0 never asks to restart" }
        }
    }

    /** Returns false when a resumed attempt must be redone from byte 0 (nothing was written). */
    private suspend fun fetch(file: RemoteFile, dest: File, offset: Long, onProgress: (Long, Long) -> Unit): Boolean {
        val total = file.sizeBytes
        val request = Request.Builder().url(url).get()
            // Byte-exact transfer: no transparent decompression of the zip.
            .header("Accept-Encoding", "identity")
            .apply {
                if (offset > 0) {
                    header("Range", "bytes=$offset-")
                    header("If-Range", file.etag)
                    // Pin the version: an edge serving another one answers 412 instead of a mixed range.
                    header("If-Match", file.etag)
                    file.lastModified?.let { header("If-Unmodified-Since", it) }
                }
            }
            .build()
        return http.cancellableCall(request) { response ->
            if (response.code == 412 && offset > 0) {
                discard(dest) // the partial bytes may belong to another version: restart from 0
                return@cancellableCall false
            }
            if (response.code != 200 && !(response.code == 206 && offset > 0) && !(response.code == 416 && offset > 0)) {
                throw IOException("Download failed: HTTP ${response.code}")
            }
            if (response.code != 416) {
                val served = response.header("ETag")
                if (served != null && served != file.etag) {
                    discard(dest)
                    throw RemoteChangedException("Remote file changed during download (ETag $served, expected ${file.etag})")
                }
                val modified = response.header("Last-Modified")
                if (file.lastModified != null && modified != null && modified != file.lastModified) {
                    discard(dest)
                    throw RemoteChangedException(
                        "Remote file changed during download (Last-Modified $modified, expected ${file.lastModified})",
                    )
                }
            }
            val append: Boolean
            when (response.code) {
                206 -> {
                    val range = response.header("Content-Range")?.let { CONTENT_RANGE.matchEntire(it.trim()) }
                    if (range == null ||
                        range.groupValues[1].toLongOrNull() != offset || range.groupValues[3].toLongOrNull() != total
                    ) {
                        return@cancellableCall false
                    }
                    append = true
                }
                200 -> {
                    append = false // Range ignored or If-Range failed: whole body from 0.
                    val length = response.header("Content-Length")?.trim()?.toLongOrNull()
                    if (length != null && length != total) {
                        discard(dest)
                        throw RemoteChangedException("Remote file changed during download (length $length, expected $total)")
                    }
                }
                else -> return@cancellableCall false // 416
            }
            if (!append) writeSidecar(dest, file)

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
                        written += n
                        if (written > total) break
                        out.write(buffer, 0, n)
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
            if (written > total) {
                discard(dest)
                throw RemoteChangedException("Remote file changed during download (more than $total bytes)")
            }
            if (dest.length() != total) {
                throw IOException("Download incomplete: ${dest.length()} of $total bytes")
            }
            onProgress(total, total)
            true
        }
    }

    private fun sidecarOf(dest: File) = File(dest.path + ".etag")

    /** Records which version the bytes in [dest] belong to, durably, before any byte is written. */
    private fun writeSidecar(dest: File, file: RemoteFile) {
        FileOutputStream(sidecarOf(dest)).use { out ->
            out.write("${file.etag}\n${file.sizeBytes}\n${file.lastModified ?: ""}".toByteArray())
            out.flush()
            out.fd.sync()
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_STEP_BYTES = 256L * 1024
        val CONTENT_RANGE = Regex("""^bytes (\d+)-(\d+)/(\d+)$""")
    }
}

/** Runs the blocking call on IO; cancelling the coroutine cancels the OkHttp call. */
internal suspend fun <T> OkHttpClient.cancellableCall(request: Request, block: suspend (Response) -> T): T =
    withContext(Dispatchers.IO) {
        val call = newCall(request)
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
