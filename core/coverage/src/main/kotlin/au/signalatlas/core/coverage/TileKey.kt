package au.signalatlas.core.coverage

import okhttp3.HttpUrl

/**
 * Identity of one cached tile. A difference in any field is a miss, so tiles of different data versions are never
 * mixed. [view] is deliberately not part of it (tiles hold every code); [engineVersion] must be bumped whenever the
 * coverage model changes.
 */
data class TileKey(
    val z: Int,
    val x: Int,
    val y: Int,
    val selectionId: String,
    val towerStamp: String,
    /** The pattern store's ETag, or "none" without patterns. */
    val patternsEtag: String,
    /**
     * Version of the terrain data the tile was rendered with: 0 when its region has no complete elevation data (quick
     * model only), else a hash of the region's chunk set and of the terrain tables that were ready (see
     * [TerrainCoordinator]). A change in either is a miss, so quick and terrain tiles, or tiles built from different
     * table sets, are never mixed.
     */
    val terrainVersion: Long = 0L,
    /**
     * Calibration version of the tile's z8 region ([CalibrationView.regionVersion]): 0 when no sector estimate or
     * residual cell applies there, else a hash of those that do. Tiles of regions a new snapshot does not touch keep
     * their keys.
     */
    val calibrationVersion: Long = 0L,
    val engineVersion: Int = ENGINE_VERSION,
) {
    companion object {
        const val ENGINE_VERSION = 2
    }
}

/**
 * A parsed tile URL: `/v1/<selectionId>/<view>/<towerStamp>/<patternsEtag>/<terrainEpoch>/<z>/<x>/<y>.png`. The terrain
 * segment (`t<epoch>`) changes whenever any region's terrain version does, which makes MapLibre refetch its tiles.
 */
internal data class TileUrl(
    val selectionId: String,
    val view: CoverageSelection.View,
    val towerStamp: String,
    val patternsEtag: String,
    val terrainSegment: String,
    val z: Int,
    val x: Int,
    val y: Int,
) {
    companion object {
        /** Null when the path is not a well-formed tile path (including x or y outside the zoom's grid). */
        fun parse(url: HttpUrl): TileUrl? {
            val s = url.pathSegments
            if (s.size != 9 || s[0] != "v1" || !s[8].endsWith(".png")) return null
            val view = CoverageSelection.View.fromSegment(s[2]) ?: return null
            val z = s[6].toIntOrNull()?.takeIf { it in 0..30 } ?: return null
            val x = s[7].toIntOrNull() ?: return null
            val y = s[8].removeSuffix(".png").toIntOrNull() ?: return null
            val n = 1L shl z
            if (x < 0 || y < 0 || x >= n || y >= n || s[1].isEmpty() || s[3].isEmpty() || s[4].isEmpty() || s[5].isEmpty()) {
                return null
            }
            return TileUrl(s[1], view, s[3], s[4], s[5], z, x, y)
        }
    }
}
