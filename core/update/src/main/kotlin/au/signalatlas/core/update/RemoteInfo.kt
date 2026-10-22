package au.signalatlas.core.update

/**
 * What ACMA currently publishes.
 *
 * @property extractStamp 12-digit `yyyyMMddHHmm` extract time from the stamp file.
 * @property etag the zip's ETag exactly as sent (opaque; quotes kept if present), echoed back in `If-Range`.
 * @property sizeBytes the zip's `Content-Length`.
 */
data class RemoteInfo(val extractStamp: String, val etag: String, val sizeBytes: Long)

/** The server answered, but not in the shape we rely on. */
class RemoteFormatException(msg: String) : java.io.IOException(msg)

/** The remote file changed (different ETag) between info() and the download; re-run info(). */
class RemoteChangedException(msg: String) : java.io.IOException(msg)
