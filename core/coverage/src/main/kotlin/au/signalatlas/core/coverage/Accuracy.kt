package au.signalatlas.core.coverage

import au.signalatlas.engine.RenderedTile

/**
 * How a tile (or a viewport of tiles) was predicted. A single tile is [QUICK] or [TERRAIN]; [PARTIAL] is only the
 * summary of a viewport in which some, but not all, tiles are [TERRAIN].
 */
enum class Accuracy {
    QUICK, PARTIAL, TERRAIN;

    companion object {
        /**
         * Terrain rule 6 for one tile: [TERRAIN] when its region had complete elevation data (`terrainVersion != 0`)
         * and at least 95% of its covered pixels had full terrain profiles ([RenderedTile.isTerrainAccurate]); a tile
         * with no covered pixel at all has nothing the quick model could have got wrong, so it counts as [TERRAIN]
         * too. Otherwise [QUICK]. The inputs are persisted with the tile, so the label survives the disk cache.
         */
        fun of(key: TileKey, tile: RenderedTile): Accuracy = when {
            key.terrainVersion == 0L -> QUICK
            tile.coveredPixels == 0 || tile.isTerrainAccurate -> TERRAIN
            else -> QUICK
        }

        /** The viewport summary: [TERRAIN] only if every tile is, [PARTIAL] if some are, else [QUICK] (also when empty). */
        fun summary(tiles: Collection<Accuracy>): Accuracy = when {
            tiles.isEmpty() || tiles.none { it == TERRAIN } -> QUICK
            tiles.all { it == TERRAIN } -> TERRAIN
            else -> PARTIAL
        }
    }
}

/** Progress of [CoverageTileService.increaseResolution]. */
sealed interface FetchProgress {
    /** The download size (bytes) of the elevation data still missing for the view; sent once before fetching. */
    data class Estimating(val bytes: Long) : FetchProgress

    /** [fraction] 0..1 by bytes. */
    data class Fetching(val fraction: Float) : FetchProgress

    /** Every chunk of the view is stored (also when none was missing). */
    data object Done : FetchProgress

    /** [reason] is a short machine-friendly tag or message: "offline", "metered", "no view", "storage", or the I/O error. */
    data class Failed(val reason: String) : FetchProgress
}
