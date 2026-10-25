package au.signalatlas.core.geo.packs

import au.signalatlas.core.geo.ChunkId
import au.signalatlas.core.geo.ChunkMath
import au.signalatlas.core.model.GeoBounds
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.tan

data class LatLon(val lat: Double, val lon: Double)

/** The ground an offline pack covers: a box, or a road corridor (a polyline buffered on both sides). */
sealed interface PackArea {
    /** The bounding box of everything the pack covers. */
    val bounds: GeoBounds

    /** The elevation chunks the pack pins: the area plus the terrain margin the coverage engine reads around it. */
    fun demChunks(): Set<ChunkId>

    /**
     * The boxes the base map is downloaded for. One box for [Box]; for a [Corridor] several boxes along the road,
     * so a diagonal road does not download its whole bounding box.
     */
    fun basemapBoxes(): List<GeoBounds>

    /** Share (0..1) of the base map tiles over [bounds] that hold data; only scales the size estimate. */
    val basemapFill: Double

    fun toJson(): JSONObject

    data class Box(val box: GeoBounds, override val basemapFill: Double = 1.0) : PackArea {
        override val bounds get() = box
        override fun demChunks() = ChunkMath.chunksFor(box, DEM_MARGIN_KM)
        override fun basemapBoxes() = listOf(box)
        override fun toJson() = JSONObject().put("type", "box")
            .put("s", box.south).put("w", box.west).put("n", box.north).put("e", box.east).put("fill", basemapFill)
    }

    data class Corridor(val points: List<LatLon>, val bufferKm: Double = CORRIDOR_BUFFER_KM) : PackArea {
        override val basemapFill get() = 1.0

        init { require(points.size >= 2) { "A corridor needs at least two points" } }

        override val bounds: GeoBounds get() = boundsOf(points).grow(bufferKm)

        /** The polyline resampled so no two points are further apart than [stepKm]. */
        fun samples(stepKm: Double = SAMPLE_STEP_KM): List<LatLon> {
            val out = ArrayList<LatLon>()
            out += points.first()
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                val n = max(1, Math.ceil(distanceKm(a, b) / stepKm).toInt())
                for (k in 1..n) out += LatLon(a.lat + (b.lat - a.lat) * k / n, a.lon + (b.lon - a.lon) * k / n)
            }
            return out
        }

        override fun demChunks(): Set<ChunkId> {
            val ids = LinkedHashSet<ChunkId>()
            for (p in samples()) ids += ChunkMath.chunksFor(GeoBounds(p.lat, p.lon, p.lat, p.lon), bufferKm + DEM_MARGIN_KM)
            return ids
        }

        override fun basemapBoxes(): List<GeoBounds> {
            val boxes = ArrayList<GeoBounds>()
            var run = ArrayList<LatLon>()
            for (p in samples()) {
                run += p
                val b = boundsOf(run)
                if (spanKm(b) >= BOX_SPAN_KM) {
                    boxes += b.grow(bufferKm)
                    run = arrayListOf(p) // the next box starts where this one ended: no gap
                }
            }
            if (run.size > 1 || boxes.isEmpty()) boxes += boundsOf(run).grow(bufferKm)
            return boxes
        }

        override fun toJson() = JSONObject().put("type", "corridor").put("buffer", bufferKm)
            .put("points", JSONArray().also { a -> points.forEach { a.put(JSONArray().put(it.lat).put(it.lon)) } })
    }

    companion object {
        const val CORRIDOR_BUFFER_KM = 10.0

        /** Matches the terrain availability margin of the coverage engine. */
        const val DEM_MARGIN_KM = 40.0
        private const val SAMPLE_STEP_KM = 5.0
        private const val BOX_SPAN_KM = 80.0
        private const val KM_PER_DEG = 111.195

        fun fromJson(o: JSONObject): PackArea = when (o.getString("type")) {
            "box" -> Box(GeoBounds(o.getDouble("s"), o.getDouble("w"), o.getDouble("n"), o.getDouble("e")), o.optDouble("fill", 1.0))
            "corridor" -> {
                val a = o.getJSONArray("points")
                Corridor(List(a.length()) { LatLon(a.getJSONArray(it).getDouble(0), a.getJSONArray(it).getDouble(1)) }, o.getDouble("buffer"))
            }
            else -> throw IllegalArgumentException("Unknown pack area ${o.getString("type")}")
        }

        internal fun boundsOf(points: List<LatLon>) = GeoBounds(
            points.minOf { it.lat }, points.minOf { it.lon }, points.maxOf { it.lat }, points.maxOf { it.lon },
        )

        internal fun GeoBounds.grow(km: Double): GeoBounds {
            val dLat = km / KM_PER_DEG
            val dLon = dLat / cos(Math.toRadians(max(abs(south), abs(north))))
            return GeoBounds(south - dLat, west - dLon, north + dLat, east + dLon)
        }

        fun distanceKm(a: LatLon, b: LatLon): Double {
            val dLat = (b.lat - a.lat) * KM_PER_DEG
            val dLon = (b.lon - a.lon) * KM_PER_DEG * cos(Math.toRadians((a.lat + b.lat) / 2))
            return Math.hypot(dLat, dLon)
        }

        internal fun spanKm(b: GeoBounds): Double =
            max((b.north - b.south) * KM_PER_DEG, (b.east - b.west) * KM_PER_DEG * cos(Math.toRadians((b.north + b.south) / 2)))
    }
}

/** Web Mercator tile arithmetic, for sizing the base map download. */
object TileCounts {
    const val MAX_ZOOM = 14

    private fun x(lon: Double, z: Int) = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    private fun y(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }

    /** Tiles at zoom [z] intersecting [b]. */
    fun count(b: GeoBounds, z: Int): Long =
        (x(b.east, z) - x(b.west, z) + 1).toLong() * (y(b.south, z) - y(b.north, z) + 1)

    /** Tiles z0..[MAX_ZOOM] of every box in [boxes]. */
    fun total(boxes: List<GeoBounds>): Long = boxes.sumOf { b -> (0..MAX_ZOOM).sumOf { count(b, it) } }
}
