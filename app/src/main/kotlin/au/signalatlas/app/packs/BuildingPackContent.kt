package au.signalatlas.app.packs

import au.signalatlas.core.geo.BuildingChunkStore
import au.signalatlas.core.geo.BuildingFetcher
import au.signalatlas.core.geo.ChunkId
import au.signalatlas.core.geo.packs.PackArea
import au.signalatlas.core.geo.packs.PackContentSource
import au.signalatlas.core.model.DataSettings
import au.signalatlas.core.model.GeoBounds
import au.signalatlas.engine.BuildingGrid
import java.io.IOException
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Building footprints for a pack, when Settings → Tower patterns includes terrain and buildings (or quality is
 * Detailed). Only the built-up parts of the area: the z10 tiles holding at least [MIN_SITES] tower sites, since
 * buildings matter where towers are dense and a whole state at 0.01° would be far too many chunks. Pinned under
 * `pack:<packId>` like the elevation. Off: nothing to download, and an earlier claim is dropped.
 */
class BuildingPackContent(
    private val store: BuildingChunkStore,
    private val fetcher: BuildingFetcher,
    /** Tower sites inside a box (the live tower DB). */
    private val siteCount: (GeoBounds) -> Int,
) : PackContentSource {
    override val id = "bld"

    private fun wanted() = DataSettings.patternTerrain || DataSettings.quality == au.signalatlas.core.model.CoverageQuality.DETAILED

    private fun chunks(area: PackArea): Set<ChunkId> {
        if (!wanted()) return emptySet()
        val b = area.bounds
        val x0 = tileX(b.west)
        val x1 = tileX(b.east)
        val y0 = tileY(b.north)
        val y1 = tileY(b.south)
        val out = LinkedHashSet<ChunkId>()
        for (x in x0..x1) for (y in y0..y1) {
            val t = tileBounds(x, y)
            if (siteCount(t) >= MIN_SITES) out += BuildingGrid.chunksFor(t)
        }
        return out
    }

    override suspend fun estimateBytes(area: PackArea): Long = try {
        fetcher.estimateBytes(chunks(area))
    } catch (e: IOException) {
        0L
    }

    override suspend fun download(packId: String, generation: Long, area: PackArea, allowMetered: Boolean, progress: (Float) -> Unit) {
        val chunks = chunks(area)
        if (chunks.isEmpty()) {
            store.unpin(pinKey(packId))
            progress(1f)
            return
        }
        store.pin(pinKey(packId), chunks)
        fetcher.fetch(chunks, allowMetered || DataSettings.mobileDataUnmetered, progress)
    }

    override suspend fun sizeBytes(packId: String, area: PackArea): Long = store.bytesOf(chunks(area))

    override suspend fun remove(packId: String, area: PackArea) = store.unpin(pinKey(packId))

    private companion object {
        const val Z = 10
        const val MIN_SITES = 8
        fun pinKey(packId: String) = "pack:$packId"
        val n = (1 shl Z).toDouble()
        fun tileX(lon: Double) = floor((lon + 180.0) / 360.0 * n).toInt()
        fun tileY(lat: Double): Int {
            val r = Math.toRadians(lat)
            return floor((1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * n).toInt()
        }
        fun lonOf(x: Double) = x / n * 360.0 - 180.0
        fun latOf(y: Double) = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / n))))
        fun tileBounds(x: Int, y: Int) = GeoBounds(latOf(y + 1.0), lonOf(x.toDouble()), latOf(y.toDouble()), lonOf(x + 1.0))
    }
}
