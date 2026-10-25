package au.signalatlas.core.roads

import au.signalatlas.core.model.LatLon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** Road classes in increasing importance; [rank] orders them at junctions. */
enum class RoadClass(val rank: Int) { MINOR(1), MEDIUM(2), MAJOR(3), HIGHWAY(4) }

/** One drivable road line (a Protomaps `roads` feature) with its vertices in degrees. */
class RoadWay(val roadClass: RoadClass, val name: String?, val points: List<LatLon>)

/**
 * Where road geometry comes from: the z14 tile at [x]/[y]. Null means unavailable right now (offline and not cached),
 * an empty list means the tile has no drivable roads. Offline packs implement this to serve pre-fetched tiles.
 */
interface RoadSource {
    suspend fun roads(x: Int, y: Int): List<RoadWay>?
}

object RoadTiles {
    const val ZOOM = 14
    private const val N = 1 shl ZOOM

    fun x(lon: Double): Int = floor((lon + 180.0) / 360.0 * N).toInt().coerceIn(0, N - 1)

    fun y(lat: Double): Int {
        val r = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * N).toInt().coerceIn(0, N - 1)
    }

    fun lon(x: Double): Double = x / N * 360.0 - 180.0
    fun lat(y: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / N))))

    /** Tile coordinates of every z14 tile touching the square of half-side [radiusM] around the point. */
    fun around(lat: Double, lon: Double, radiusM: Double): List<Pair<Int, Int>> {
        val dLat = Math.toDegrees(radiusM / 6_371_000.0)
        val dLon = dLat / cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val x0 = x(lon - dLon)
        val x1 = x(lon + dLon)
        val y0 = y(lat + dLat)
        val y1 = y(lat - dLat)
        return (y0..y1).flatMap { ty -> (x0..x1).map { tx -> tx to ty } }
    }
}

/** Decodes the `roads` layer of a z14 MVT into drivable [RoadWay]s (kind highway / major_road / medium_road / minor_road). */
object RoadTileDecoder {
    fun decode(mvt: ByteArray, x: Int, y: Int): List<RoadWay> {
        val layer = Mvt.decode(mvt, setOf("roads")).firstOrNull() ?: return emptyList()
        val out = ArrayList<RoadWay>()
        for (f in layer.features) {
            if (f.type != 2) continue
            val cls = when (f.properties["kind"]) {
                "highway" -> RoadClass.HIGHWAY
                "major_road" -> RoadClass.MAJOR
                "medium_road" -> RoadClass.MEDIUM
                "minor_road" -> RoadClass.MINOR
                else -> continue
            }
            val name = f.properties["name"] as? String
            for (line in f.geometry) {
                if (line.size < 4) continue
                val pts = ArrayList<LatLon>(line.size / 2)
                for (i in line.indices step 2) {
                    pts += LatLon(
                        RoadTiles.lat(y + line[i + 1].toDouble() / layer.extent),
                        RoadTiles.lon(x + line[i].toDouble() / layer.extent),
                    )
                }
                out += RoadWay(cls, name, pts)
            }
        }
        return out
    }
}

/**
 * Roads from a PMTiles archive (Protomaps v4) by HTTP range reads, with the decompressed z14 tiles kept on disk in
 * [cacheDir] (typically `cacheDir/roads`) for [ttlMs]; an empty file records a tile with no roads. [openReader] is tried
 * again after [RETRY_MS] when it fails, and a reader that errors is dropped so the next call reopens the archive.
 */
class PmtilesRoadSource(
    private val openReader: () -> PmtilesReader,
    private val cacheDir: File?,
    private val ttlMs: Long = 30L * 24 * 3600 * 1000,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : RoadSource {
    private val lock = Any()
    private var reader: PmtilesReader? = null
    private var failedAtMs = Long.MIN_VALUE / 2

    override suspend fun roads(x: Int, y: Int): List<RoadWay>? = withContext(Dispatchers.IO) {
        val file = cacheDir?.let { File(it, "${RoadTiles.ZOOM}_${x}_$y.mvt") }
        if (file != null && file.isFile && clockMs() - file.lastModified() < ttlMs) {
            try {
                return@withContext RoadTileDecoder.decode(file.readBytes(), x, y)
            } catch (e: IllegalArgumentException) {
                file.delete()
            } catch (e: IOException) {
                file.delete()
            }
        }
        val r = reader() ?: return@withContext null
        val bytes = try {
            r.tile(RoadTiles.ZOOM, x, y) ?: ByteArray(0)
        } catch (e: IOException) {
            drop(r)
            return@withContext null
        }
        if (file != null) {
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) tmp.delete()
            } catch (e: IOException) {
                // an uncached tile is only slower next time
            }
        }
        if (bytes.isEmpty()) emptyList() else try {
            RoadTileDecoder.decode(bytes, x, y)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun reader(): PmtilesReader? = synchronized(lock) {
        reader?.let { return it }
        if (clockMs() - failedAtMs < RETRY_MS) return null
        try {
            openReader().also { reader = it }
        } catch (e: IOException) {
            failedAtMs = clockMs()
            null
        }
    }

    private fun drop(r: PmtilesReader) = synchronized(lock) {
        if (reader === r) reader = null
        failedAtMs = clockMs()
    }

    companion object {
        const val RETRY_MS = 60_000L
    }
}

/**
 * Keeps a [RoadGraph] around the vehicle for the trip's look-ahead without ever blocking it: [graphNear] returns what is
 * loaded now (null until the first load finishes, or when nothing is reachable) and starts a background reload of the
 * z14 tiles within [radiusM] when none exists or the vehicle has moved [reloadM] from the graph's centre.
 */
class RoadGraphProvider(
    private val source: RoadSource,
    private val scope: CoroutineScope,
    private val radiusM: Double = 15_000.0,
    private val reloadM: Double = 5_000.0,
    private val parallelism: Int = 6,
) {
    @Volatile private var current: RoadGraph? = null
    @Volatile private var loading: Job? = null
    @Volatile private var failedAtMs = Long.MIN_VALUE / 2

    fun graphNear(lat: Double, lon: Double, nowMs: Long = System.currentTimeMillis()): RoadGraph? {
        val g = current
        val stale = g == null || g.distanceFromOriginM(lat, lon) > reloadM
        if (stale && loading?.isActive != true && nowMs - failedAtMs > RETRY_MS) {
            loading = scope.launch {
                val loaded = load(source, lat, lon, radiusM, parallelism)
                if (loaded != null) current = loaded else failedAtMs = nowMs
            }
        }
        return g
    }

    companion object {
        private const val RETRY_MS = 60_000L

        /** Loads every z14 tile within [radiusM] and builds the graph centred on the point; null when no tile was available. */
        suspend fun load(source: RoadSource, lat: Double, lon: Double, radiusM: Double, parallelism: Int = 6): RoadGraph? {
            val gate = Semaphore(parallelism)
            val tiles = coroutineScope {
                RoadTiles.around(lat, lon, radiusM).map { (x, y) -> async { gate.withPermit { source.roads(x, y) } } }.awaitAll()
            }
            if (tiles.all { it == null }) return null
            return RoadGraph.build(lat, lon, tiles.filterNotNull().flatten())
        }
    }
}
