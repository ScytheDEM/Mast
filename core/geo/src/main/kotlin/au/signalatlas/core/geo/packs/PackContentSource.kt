package au.signalatlas.core.geo.packs

import au.signalatlas.core.geo.ChunkId
import au.signalatlas.core.geo.DemChunkStore
import au.signalatlas.core.geo.DemFetcher
import java.io.IOException

/**
 * One kind of data a pack holds (base map, elevation; buildings and roads plug in later). A pack download runs every
 * source in turn. Every method is idempotent: a download that was interrupted is resumed by calling [download] again.
 */
interface PackContentSource {
    val id: String

    /** Bytes still to download for [area] (what is already stored is not counted). */
    suspend fun estimateBytes(area: PackArea): Long

    /**
     * Makes sure [area]'s data is stored and protected from eviction for [packId]. [generation] changes on every
     * update, so sources that version their data can tell stale copies from a partial download to resume.
     * [progress] gets 0..1.
     */
    suspend fun download(packId: String, generation: Long, area: PackArea, allowMetered: Boolean, progress: (Float) -> Unit)

    /** Bytes the pack holds on disk. */
    suspend fun sizeBytes(packId: String, area: PackArea): Long

    /** Drops the pack's claim on its data. */
    suspend fun remove(packId: String, area: PackArea)
}

/** Elevation: DEM chunks fetched by [DemFetcher] and pinned in the [DemChunkStore] under `pack:<packId>`. */
class DemPackContent(
    private val store: DemChunkStore,
    private val estimate: suspend (Set<ChunkId>) -> Long,
    private val fetch: suspend (Set<ChunkId>, Boolean, (Float) -> Unit) -> Unit,
) : PackContentSource {
    constructor(store: DemChunkStore, fetcher: DemFetcher) :
        this(store, fetcher::estimateBytes, { chunks, metered, progress -> fetcher.fetch(chunks, metered, progress) })

    override val id = "dem"

    override suspend fun estimateBytes(area: PackArea): Long {
        val chunks = area.demChunks()
        return try {
            estimate(chunks)
        } catch (e: IOException) {
            // Offline: a rough figure from the chunk count beats none.
            chunks.count { !store.has(it) } * FALLBACK_CHUNK_BYTES
        }
    }

    override suspend fun download(packId: String, generation: Long, area: PackArea, allowMetered: Boolean, progress: (Float) -> Unit) {
        val chunks = area.demChunks()
        // Pin first: chunks fetched so far stay put if the download is interrupted, so it resumes where it stopped.
        store.pin(pinKey(packId), chunks)
        fetch(chunks, allowMetered, progress)
    }

    override suspend fun sizeBytes(packId: String, area: PackArea): Long = store.bytesOf(area.demChunks())

    override suspend fun remove(packId: String, area: PackArea) = store.unpin(pinKey(packId))

    companion object {
        const val FALLBACK_CHUNK_BYTES = 90_000L
        fun pinKey(packId: String) = "pack:$packId"
    }
}

/** The figures shown before a download starts. */
object PackSizes {
    /** Mean size of one Protomaps vector tile over z0..14 where there is data. */
    const val AVG_TILE_BYTES = 12_000L

    fun basemapBytes(def: PackDef): Long = (TileCounts.total(def.area.basemapBoxes()) * def.basemapFill * AVG_TILE_BYTES).toLong()
}
