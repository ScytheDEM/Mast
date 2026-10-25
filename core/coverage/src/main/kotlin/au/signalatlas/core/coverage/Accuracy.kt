package au.signalatlas.core.coverage

import au.signalatlas.engine.RenderedTile

/**
 * How a tile (or a viewport of tiles) was predicted: [QUICK] = no terrain loss applied anywhere, [TERRAIN] = terrain
 * applied to at least 95% of the covered pixels (Terrain rule 6), [PARTIAL] = in between (a tile rendered with terrain
 * whose other pixels are served by carriers without a table yet, or a viewport mixing such tiles).
 */
enum class Accuracy {
    QUICK, PARTIAL, TERRAIN;

    companion object {
        /**
         * One tile. Rendered without terrain (`terrainVersion == 0`) or with no covered pixel having a complete terrain
         * lookup: [QUICK]. With terrain, a pixel counts as complete only if its *serving* carrier had a table and a full
         * profile (an untabled serving carrier makes it incomplete, M3b): >= 95% complete is [TERRAIN], fewer is
         * [PARTIAL]. A tile with no coverage is [QUICK]; the viewport summary ([CoverageTileService.accuracyFor]) leaves
         * such empty tiles out. The inputs are persisted with the tile, so the label survives the disk cache.
         */
        fun of(key: TileKey, tile: RenderedTile): Accuracy = when {
            key.terrainVersion == 0L || tile.coveredPixels == 0 -> QUICK
            tile.isTerrainAccurate -> TERRAIN
            tile.terrainIncompletePixels < tile.coveredPixels -> PARTIAL
            else -> QUICK
        }

        /** The viewport summary: [TERRAIN] only if every tile is, [QUICK] if every tile is (also when empty), else [PARTIAL]. */
        fun summary(tiles: Collection<Accuracy>): Accuracy = when {
            tiles.isEmpty() || tiles.all { it == QUICK } -> QUICK
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
