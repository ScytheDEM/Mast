package au.signalatlas.core.map.packs

import android.content.Context
import au.signalatlas.core.model.GeoBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** [OfflineBackend] on MapLibre's [OfflineManager]. Its API is main-thread only, so every call hops there. */
class MapLibreOfflineBackend(context: Context, private val pixelRatio: Float = 1f) : OfflineBackend {
    private val app = context.applicationContext

    private suspend fun <T> main(block: suspend (OfflineManager) -> T): T = withContext(Dispatchers.Main.immediate) {
        MapLibre.getInstance(app)
        block(OfflineManager.getInstance(app))
    }

    private fun OfflineRegion.toRegion(s: OfflineRegionStatus?) =
        BasemapRegion(id, String(metadata, Charsets.UTF_8), s?.isComplete == true, s?.completedResourceSize ?: 0L)

    private suspend fun statusOf(r: OfflineRegion): OfflineRegionStatus? = suspendCancellableCoroutine { c ->
        r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) = c.resume(status)
            override fun onError(error: String?) = c.resume(null)
        })
    }

    private suspend fun regions(m: OfflineManager): List<OfflineRegion> = suspendCancellableCoroutine { c ->
        m.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) = c.resume(offlineRegions?.toList().orEmpty())
            override fun onError(error: String) = c.resumeWithException(IOException("Listing offline regions failed: $error"))
        })
    }

    override suspend fun list(): List<BasemapRegion> = main { m -> regions(m).map { it.toRegion(statusOf(it)) } }

    override suspend fun create(styleUrl: String, bounds: GeoBounds, maxZoom: Int, metadata: String): BasemapRegion = main { m ->
        val def = OfflineTilePyramidRegionDefinition(
            styleUrl, LatLngBounds.from(bounds.north, bounds.east, bounds.south, bounds.west), 0.0, maxZoom.toDouble(), pixelRatio,
        )
        val region = suspendCancellableCoroutine { c ->
            m.createOfflineRegion(def, metadata.toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) = c.resume(offlineRegion)
                override fun onError(error: String) = c.resumeWithException(IOException("Creating the offline region failed: $error"))
            })
        }
        region.toRegion(null)
    }

    override suspend fun download(id: Long, progress: (Float) -> Unit) = main { m ->
        val region = regions(m).firstOrNull { it.id == id } ?: throw IOException("Offline region $id is gone")
        suspendCancellableCoroutine { c ->
            region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) {
                    val required = status.requiredResourceCount.coerceAtLeast(1)
                    progress((status.completedResourceCount.toFloat() / required).coerceIn(0f, 1f))
                    if (status.isComplete) {
                        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        region.setObserver(null)
                        if (c.isActive) c.resume(Unit)
                    }
                }

                override fun onError(error: OfflineRegionError) {
                    // Transient errors (a lost connection) are retried by MapLibre; the worker's constraints decide
                    // when to give up, so only a fatal reason ends the download here.
                    if (error.reason == "REASON_NOT_FOUND" || error.reason == "REASON_SERVER") return
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    region.setObserver(null)
                    if (c.isActive) c.resumeWithException(IOException("${error.reason}: ${error.message}"))
                }

                override fun mapboxTileCountLimitExceeded(limit: Long) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    if (c.isActive) c.resumeWithException(IOException("Tile count limit $limit exceeded"))
                }
            })
            // Cancelling (the worker was stopped) pauses the region; its progress stays for the next attempt.
            c.invokeOnCancellation {
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                region.setObserver(null)
            }
            region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        }
    }

    override suspend fun status(id: Long): BasemapRegion? = main { m ->
        regions(m).firstOrNull { it.id == id }?.let { it.toRegion(statusOf(it)) }
    }

    override suspend fun delete(id: Long) = main { m ->
        val region = regions(m).firstOrNull { it.id == id } ?: return@main
        suspendCancellableCoroutine { c ->
            region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() = c.resume(Unit)
                override fun onError(error: String) = c.resumeWithException(IOException("Deleting the offline region failed: $error"))
            })
        }
    }
}
