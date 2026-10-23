package au.signalatlas.app.data

import android.content.ComponentCallbacks2
import au.signalatlas.core.coverage.CoverageTileService
import au.signalatlas.core.coverage.TerrainTableStore
import au.signalatlas.core.coverage.TileCache
import au.signalatlas.core.geo.DemChunkStore
import au.signalatlas.core.geo.GeoPrefs
import java.io.File

/** What the app keeps on the device, in bytes. */
data class StorageSizes(val towers: Long, val patterns: Long, val terrain: Long, val coverageCache: Long)

/** The Storage screen's view of the on-device data (blocking IO: call off the main thread). */
interface Storage {
    fun sizes(): StorageSizes
    /** Deletes the downloaded elevation and the terrain tables built from it. */
    fun clearTerrain()
    /** Deletes the rendered coverage tiles; they are drawn again when needed. */
    fun clearCoverageCache()
    /** The terrain cache limit in MiB: one of [GeoPrefs.BUDGET_OPTIONS_MIB]. */
    var terrainBudgetMib: Int
}

class AppStorage(
    private val towersDb: File,
    private val patternsDb: File,
    private val chunks: DemChunkStore,
    private val tables: TerrainTableStore,
    private val tiles: TileCache,
    private val coverage: CoverageTileService,
    private val prefs: GeoPrefs,
) : Storage {
    override fun sizes() = StorageSizes(
        towers = towersDb.length(),
        patterns = patternsDb.length(),
        terrain = chunks.bytesUsed.value + tables.diskUsage,
        coverageCache = tiles.diskUsage,
    )

    override fun clearTerrain() {
        chunks.clear()
        tables.clear()
        // Regions and mosaics built from the deleted data are let go too.
        coverage.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
    }

    override fun clearCoverageCache() = tiles.clear()

    override var terrainBudgetMib: Int
        get() = prefs.cacheBudgetMib
        set(value) {
            prefs.cacheBudgetMib = value
            chunks.trim() // a lower limit takes effect now, not at the next download
        }
}
