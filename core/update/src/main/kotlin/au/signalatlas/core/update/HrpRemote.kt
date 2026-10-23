package au.signalatlas.core.update

import okhttp3.OkHttpClient
import java.io.File

/**
 * ACMA's per-transmitter horizontal radiation patterns (one 1.56 GB zip): HEAD for the current
 * version and a resumable download, through the same [ResumableDownload] as [AcmaRemote]. Per
 * Ruling BB the version is the ETag plus Content-Length plus Last-Modified, all recorded in the
 * download's sidecar.
 */
class HrpRemote(http: OkHttpClient, zipUrl: String = ZIP_URL) {
    companion object {
        const val ZIP_URL = "https://cdn.acma.gov.au/rrl/spectra_licence_hrp.zip"
    }

    private val zip = ResumableDownload(http, zipUrl)

    /** HEAD: ETag, size and Last-Modified of the published zip. */
    suspend fun info(): RemoteFile = zip.head()

    /** Downloads (or resumes) [info] into [dest]; see [ResumableDownload.download]. */
    suspend fun download(info: RemoteFile, dest: File, onProgress: (bytes: Long, total: Long) -> Unit) =
        zip.download(info, dest, onProgress)

    /** The version the bytes in [dest] belong to, or null if unknown. */
    fun downloaded(dest: File): RemoteFile? = zip.downloaded(dest)

    /** Deletes [dest] and its sidecar. */
    fun discard(dest: File) = zip.discard(dest)
}
