package au.signalatlas.core.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Network side of the ACMA update flow: a cheap "is there newer data?" check and a resumable
 * download of the export zip (through [ResumableDownload]). It never retries (WorkManager owns
 * that) and never splices two different files: any doubt about the remote's identity restarts the
 * download at byte 0.
 */
class AcmaRemote(
    private val http: OkHttpClient,
    zipUrl: String = ZIP_URL,
    private val stampUrl: String = STAMP_URL,
) {
    companion object {
        const val ZIP_URL = "https://cdn.acma.gov.au/rrl/spectra_rrl.zip"
        const val STAMP_URL = "https://backend.acma.gov.au/rrl/v1/extracts/datetime-of-extract.txt"

        private val STAMP = Regex("""^\d{12}$""")
    }

    private val zip = ResumableDownload(http, zipUrl)

    /** Deletes [dest] and its provenance sidecar (call after a successful import). */
    fun discard(dest: File) = zip.discard(dest)

    /** True when [dest] holds the whole file for [info] (right ETag and length). */
    fun isComplete(info: RemoteInfo, dest: File): Boolean = zip.isComplete(info.file, dest)

    /** The ETag the bytes in [dest] belong to, or null if unknown. */
    fun downloadedEtag(dest: File): String? = zip.downloadedEtag(dest)

    /** Fetches the extract stamp (GET) and the zip's ETag and size (HEAD). */
    suspend fun info(): RemoteInfo {
        val stamp = http.cancellableCall(Request.Builder().url(stampUrl).get().build()) { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${response.request.url}")
            response.body.string().trim()
        }
        if (!STAMP.matches(stamp)) throw RemoteFormatException("Unexpected extract stamp: ${stamp.take(40)}")
        val head = zip.head()
        return RemoteInfo(stamp, head.etag, head.sizeBytes)
    }

    /**
     * Downloads the zip into [dest]. Resumes a partial [dest] when the server honours Range with 206
     * for the same ETag; otherwise restarts at byte 0. Throws [IOException] (keeping the partial file)
     * if the final length differs from [RemoteInfo.sizeBytes].
     */
    suspend fun download(info: RemoteInfo, dest: File, onProgress: (bytes: Long, total: Long) -> Unit) =
        zip.download(info.file, dest, onProgress)

    /** The RRL export is identified by ETag and size (its Last-Modified is not tracked). */
    private val RemoteInfo.file get() = RemoteFile(etag, sizeBytes)
}
