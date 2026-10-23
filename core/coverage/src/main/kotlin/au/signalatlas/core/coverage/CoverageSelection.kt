package au.signalatlas.core.coverage

import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import java.util.zip.CRC32

/** What the coverage layer shows: one network's carriers, optionally narrowed to techs and bands. */
data class CoverageSelection(
    val network: Network,
    val techs: Set<Tech>,
    /** Band labels to include; null = every band. */
    val bands: Set<String>?,
    val indoor: Boolean,
    val view: View,
) {
    /** [segment] is the `<view>` path segment of the tile URL. */
    enum class View(val segment: String) {
        RSRP("rsrp"), PROBABILITY("prob");

        companion object {
            fun fromSegment(s: String): View? = entries.firstOrNull { it.segment == s }
        }
    }

    /**
     * Identifies the tiles this selection renders: URL-path safe (`[a-z0-9_]`), independent of set order, and
     * deliberately excluding [view], because cached tiles hold RSRP, SINR and probability codes, so switching view
     * only re-encodes.
     */
    fun cacheId(): String {
        val techMask = techs.fold(0) { m, t -> m or (1 shl t.ordinal) }
        val bandPart = if (bands == null) "all" else {
            val crc = CRC32().apply { update(bands.sorted().joinToString("\u0000").toByteArray()) }
            "b" + crc.value.toString(16).padStart(8, '0')
        }
        return "${network.name.lowercase()}_t${techMask}_${bandPart}_${if (indoor) "in" else "out"}"
    }
}
