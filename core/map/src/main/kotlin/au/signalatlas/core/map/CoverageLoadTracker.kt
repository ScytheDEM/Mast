package au.signalatlas.core.map

/**
 * Whether the coverage overlay is still waiting for tiles: the coverage service renders a tile on request, so a tile
 * of a coverage source that has been requested but not yet parsed (or failed) means "calculating". Feed it the map's
 * tile events; [onIdle] clears it (an idle map has nothing outstanding).
 */
class CoverageLoadTracker {
    private val outstanding = HashSet<Triple<Int, Int, Int>>()

    /** True while at least one coverage tile is outstanding. */
    val loading: Boolean get() = outstanding.isNotEmpty()

    /** Returns the new [loading] value. */
    fun onTile(event: CoverageLayer.TileEvent, z: Int, x: Int, y: Int, source: String?): Boolean {
        if (source == null || !source.startsWith(CoverageLayer.SOURCE_ID)) return loading
        val t = Triple(z, x, y)
        when (event) {
            CoverageLayer.TileEvent.NETWORK_REQUESTED -> outstanding += t
            CoverageLayer.TileEvent.PARSED, CoverageLayer.TileEvent.FAILED -> outstanding -= t
            CoverageLayer.TileEvent.NETWORK_LOADED, CoverageLayer.TileEvent.OTHER -> Unit
        }
        return loading
    }

    fun onIdle(): Boolean {
        outstanding.clear()
        return false
    }

    fun reset() = outstanding.clear()
}
