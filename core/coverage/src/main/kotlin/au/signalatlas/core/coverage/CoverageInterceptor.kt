package au.signalatlas.core.coverage

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Answers MapLibre's tile requests for host [CoverageTileService.HOST] from the [service]; every other host passes
 * through. Blocks the calling (OkHttp) thread until the tile is ready, up to [timeoutMs], and gives up early when
 * the call is cancelled.
 *
 * 200 `image/png` (`Cache-Control: no-store`); 204 outside zooms 8..16; 404 for a malformed path or coverage off;
 * 410 for a URL naming a stale selection or data version; 503 when the render was cancelled; 504 on timeout.
 */
class CoverageInterceptor(
    private val service: CoverageTileService,
    private val timeoutMs: Long = 20_000,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != CoverageTileService.HOST) return chain.proceed(request)

        val tile = TileUrl.parse(request.url) ?: return empty(request, 404, "Not Found")
        if (tile.z < CoverageTileService.MIN_ZOOM || tile.z > CoverageTileService.MAX_ZOOM) {
            return empty(request, 204, "No Content")
        }
        val future = service.tileFuture(tile)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            if (chain.call().isCanceled()) throw IOException("Canceled")
            try {
                val png = future.get(POLL_MS, TimeUnit.MILLISECONDS)
                return Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Type", "image/png").header("Cache-Control", "no-store")
                    .body(png.toResponseBody(PNG))
                    .build()
            } catch (e: TimeoutException) {
                if (System.nanoTime() - deadline >= 0) return empty(request, 504, "Gateway Timeout")
            } catch (e: ExecutionException) {
                return when (e.cause) {
                    is CoverageOffException -> empty(request, 404, "Not Found")
                    is StaleTileException -> empty(request, 410, "Gone")
                    is TileCancelledException -> empty(request, 503, "Service Unavailable")
                    else -> empty(request, 500, "Internal Server Error")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("interrupted while waiting for a coverage tile")
            }
        }
    }

    private fun empty(request: Request, code: Int, message: String): Response =
        Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(code).message(message)
            .header("Cache-Control", "no-store")
            .body(ByteArray(0).toResponseBody(null))
            .build()

    private companion object {
        val PNG = "image/png".toMediaType()
        const val POLL_MS = 100L
    }
}
