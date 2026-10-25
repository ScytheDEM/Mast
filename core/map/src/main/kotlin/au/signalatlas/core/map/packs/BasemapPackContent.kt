package au.signalatlas.core.map.packs

import au.signalatlas.core.geo.packs.PackArea
import au.signalatlas.core.geo.packs.PackContentSource
import au.signalatlas.core.geo.packs.PackSizes
import au.signalatlas.core.model.GeoBounds
import org.json.JSONObject

/** A base map region as the pack code sees it (the MapLibre `OfflineRegion` behind [OfflineBackend]). */
data class BasemapRegion(val id: Long, val metadata: String, val complete: Boolean, val bytes: Long)

/** What [BasemapPackContent] needs of MapLibre's offline database; [MapLibreOfflineBackend] is the real one. */
interface OfflineBackend {
    suspend fun list(): List<BasemapRegion>
    suspend fun create(styleUrl: String, bounds: GeoBounds, maxZoom: Int, metadata: String): BasemapRegion

    /** Downloads the region until it is complete (resuming what is stored); throws `IOException` on failure. */
    suspend fun download(id: Long, progress: (Float) -> Unit)
    suspend fun status(id: Long): BasemapRegion?
    suspend fun delete(id: Long)
}

/**
 * The base map (the pmtiles clean style, z0 to [MAX_ZOOM]) as MapLibre offline regions: one per box of the pack's area,
 * tagged with `{pack, gen, box}` in the region metadata.
 *
 * Regions of the current generation are reused, so a download that was interrupted resumes (MapLibre keeps what it
 * fetched). Regions of an older generation (an update) are deleted only after the new ones are complete, so the old
 * map keeps working until then.
 */
class BasemapPackContent(
    private val backend: OfflineBackend,
    private val styleUrl: suspend () -> String,
) : PackContentSource {
    override val id = "basemap"

    override suspend fun estimateBytes(area: PackArea): Long = PackSizes.basemapBytes(area)

    override suspend fun download(packId: String, generation: Long, area: PackArea, allowMetered: Boolean, progress: (Float) -> Unit) {
        val boxes = area.basemapBoxes()
        val mine = regionsOf(packId)
        val current = mine.filter { Meta.parse(it.metadata)?.gen == generation }.associateBy { Meta.parse(it.metadata)!!.box }
        var url: String? = null // resolved on first use: a fully downloaded pack needs no style (or network)
        for ((i, box) in boxes.withIndex()) {
            val region = current[i] ?: backend.create(url ?: styleUrl().also { url = it }, box, MAX_ZOOM, Meta(packId, generation, i).toString())
            if (!region.complete) {
                backend.download(region.id) { progress((i + it) / boxes.size) }
            }
            progress((i + 1f) / boxes.size)
        }
        for (old in mine) if (Meta.parse(old.metadata)?.gen != generation) backend.delete(old.id)
    }

    override suspend fun sizeBytes(packId: String, area: PackArea): Long = regionsOf(packId).sumOf { backend.status(it.id)?.bytes ?: it.bytes }

    override suspend fun remove(packId: String, area: PackArea) {
        for (r in regionsOf(packId)) backend.delete(r.id)
    }

    private suspend fun regionsOf(packId: String) = backend.list().filter { Meta.parse(it.metadata)?.pack == packId }

    internal data class Meta(val pack: String, val gen: Long, val box: Int) {
        override fun toString(): String = JSONObject().put("pack", pack).put("gen", gen).put("box", box).toString()

        companion object {
            fun parse(s: String): Meta? = try {
                JSONObject(s).let { Meta(it.getString("pack"), it.getLong("gen"), it.getInt("box")) }
            } catch (_: Exception) {
                null
            }
        }
    }

    companion object {
        const val MAX_ZOOM = 14
    }
}
