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
    val engineVersion: Int = ENGINE_VERSION,
) {
    companion object {
        const val ENGINE_VERSION = 1
    }
}

/** A parsed tile URL: `/v1/<selectionId>/<view>/<towerStamp>/<patternsEtag>/<z>/<x>/<y>.png`. */
internal data class TileUrl(
    val selectionId: String,
    val view: CoverageSelection.View,
    val towerStamp: String,
    val patternsEtag: String,
    val z: Int,
    val x: Int,
    val y: Int,
) {
    companion object {
        /** Null when the path is not a well-formed tile path (including x or y outside the zoom's grid). */
        fun parse(url: HttpUrl): TileUrl? {
            val s = url.pathSegments
            if (s.size != 8 || s[0] != "v1" || !s[7].endsWith(".png")) return null
            val view = CoverageSelection.View.fromSegment(s[2]) ?: return null
            val z = s[5].toIntOrNull()?.takeIf { it in 0..30 } ?: return null
            val x = s[6].toIntOrNull() ?: return null
            val y = s[7].removeSuffix(".png").toIntOrNull() ?: return null
            val n = 1L shl z
            if (x < 0 || y < 0 || x >= n || y >= n || s[1].isEmpty() || s[3].isEmpty() || s[4].isEmpty()) return null
            return TileUrl(s[1], view, s[3], s[4], z, x, y)
        }
    }
}
