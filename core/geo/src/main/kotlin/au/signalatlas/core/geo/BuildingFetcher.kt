package au.signalatlas.core.geo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

/** Web-Mercator quadkeys of the building dataset's partitions (Bing tile system). */
object Quadkey {
    const val LEVEL = 9

    /** The quadkey of z[LEVEL] tile (x, y). */
    fun of(x: Int, y: Int, level: Int = LEVEL): String {
        val sb = StringBuilder(level)
        for (i in level downTo 1) {
            val mask = 1 shl (i - 1)
            var d = 0
            if (x and mask != 0) d += 1
            if (y and mask != 0) d += 2
            sb.append(d)
        }
        return sb.toString()
    }

    fun tileX(lon: Double, level: Int = LEVEL): Int =
        floor((lon + 180.0) / 360.0 * (1 shl level)).toInt().coerceIn(0, (1 shl level) - 1)

    fun tileY(lat: Double, level: Int = LEVEL): Int {
        val l = lat.coerceIn(-85.05112878, 85.05112878)
        return floor((1.0 - asinh(tan(Math.toRadians(l))) / Math.PI) / 2.0 * (1 shl level)).toInt().coerceIn(0, (1 shl level) - 1)
    }

    /** Every z[LEVEL] quadkey whose tile intersects chunk [id]. */
    fun forChunk(id: ChunkId): Set<String> {
        val eps = 1e-9
        val x0 = tileX(id.westDeg + eps)
        val x1 = tileX(id.westDeg + 0.25 - eps)
        val y0 = tileY(id.southDeg + 0.25 - eps) // north edge: smaller y
        val y1 = tileY(id.southDeg + eps)
        val out = LinkedHashSet<String>()
        for (x in x0..x1) for (y in y0..y1) out += of(x, y)
        return out
    }
}

/**
 * Turns the Microsoft Global ML Building Footprints (no account; `dataset-links.csv` lists one or more gz GeoJSON-lines
 * files per z9 quadkey, with `Location,QuadKey,Url,Size,UploadDate` columns, verified 2026-10-25) into
 * [BuildingChunk]s in a [BuildingChunkStore].
 *
 * - The links file (about 7 MB) is downloaded once into [linksFile] (atomically) and parsed in memory to quadkey ->
 *   files. It is refreshed after [LINKS_MAX_AGE_MS] or when a listed file has gone (404).
 * - [fetch] downloads every file of every quadkey the missing chunks touch, streams the gz lines through
 *   [FootprintParser] into a [BuildingRasterizer] (whole-file reads: the files are a few MB), and only then writes the
 *   chunks, so an interrupted fetch leaves no partial chunk. A quadkey the dataset does not list has no buildings: its
 *   cells are data with coverage 0.
 * - Same rules as the DEM: metered networks need `allowMetered`, [DemFetcher.RESERVE_BYTES] of free space on top of
 *   the download, one fetch at a time process-wide.
 */
class BuildingFetcher(
    private val http: OkHttpClient,
    private val store: BuildingChunkStore,
    private val linksUrl: String = DEFAULT_LINKS_URL,
    private val linksFile: File = File(store.dir.parentFile ?: store.dir, "bld-dataset-links.csv"),
    private val freeBytes: () -> Long = { store.dir.usableSpace },
    private val isMetered: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One listed file of a quadkey. */
    class Part(val url: String, val bytes: Long)

    @Volatile private var links: Map<String, List<Part>>? = null

    /** Bytes [fetch] would download for the chunks of [chunks] not stored yet (the listed sizes; reads the links file). */
    suspend fun estimateBytes(chunks: Set<ChunkId>): Long {
        val todo = chunks.filter { !store.has(it) }
        if (todo.isEmpty()) return 0L
        val l = links()
        return todo.flatMap { Quadkey.forChunk(it) }.toSet().sumOf { q -> l[q]?.sumOf { it.bytes } ?: 0L }
    }

    /** The files listed for quadkey [q] (reads the links file). */
    suspend fun partsOf(q: String): List<Part> = links()[q] ?: emptyList()

    /**
     * Makes sure every chunk of [chunks] is stored. [progress] gets 0..1 by bytes. Throws [MeteredNetworkException],
     * [InsufficientStorageException] and [IOException] like [DemFetcher.fetch].
     */
    suspend fun fetch(chunks: Set<ChunkId>, allowMetered: Boolean, progress: (Float) -> Unit) {
        if (!allowMetered && isMetered()) throw MeteredNetworkException()
        lock.withLock {
            val todo = chunks.filter { !store.has(it) }
            if (todo.isEmpty()) {
                progress(1f)
                return
            }
            val l = links()
            val quads = todo.flatMap { Quadkey.forChunk(it) }.toSortedSet()
            val parts = quads.flatMap { l[it] ?: emptyList() }
            val total = parts.sumOf { it.bytes }
            val required = DemFetcher.RESERVE_BYTES + total
            val free = freeBytes()
            if (free < required) throw InsufficientStorageException(required, free)
            val raster = BuildingRasterizer()
            var done = 0L
            progress(0f)
            for (p in parts) {
                currentCoroutineContext().ensureActive()
                done += read(p, raster) { }
                progress(if (total == 0L) 1f else (done.toFloat() / total).coerceIn(0f, 1f))
            }
            withContext(Dispatchers.IO) {
                store.acquire(todo)
                try {
                    for (id in todo) store.put(raster.chunk(id))
                } finally {
                    store.release(todo)
                }
            }
            progress(1f)
        }
    }

    /** Streams one gz GeoJSON-lines file into [raster]; returns its compressed size. */
    private suspend fun read(p: Part, raster: BuildingRasterizer, onBytes: (Long) -> Unit): Long = withContext(Dispatchers.IO) {
        val call = http.newCall(Request.Builder().url(p.url).build())
        call.execute().use { resp ->
            if (resp.code == 404) {
                links = null
                linksFile.delete() // the listing is stale: reread next time
                throw IOException("building file gone: ${p.url}")
            }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for ${p.url}")
            val body = resp.body
            var compressed = 0L
            val counting = object : java.io.FilterInputStream(body.byteStream()) {
                override fun read(): Int = super.read().also { if (it >= 0) compressed++ }
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { compressed += it; onBytes(it.toLong()) } }
            }
            GZIPInputStream(counting, 64 * 1024).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) FootprintParser.parse(line)?.let(raster::add)
            }
            compressed
        }
    }

    private suspend fun links(): Map<String, List<Part>> {
        links?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = linksFile
            if (!f.isFile || clock() - f.lastModified() > LINKS_MAX_AGE_MS) download(f)
            parseLinks(f.readLines()).also { links = it }
        }
    }

    private fun download(target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        http.newCall(Request.Builder().url(linksUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $linksUrl")
            FileOutputStream(tmp).use { out ->
                resp.body.byteStream().copyTo(out)
                out.fd.sync()
            }
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val DEFAULT_LINKS_URL = "https://minedbuildings.z5.web.core.windows.net/global-buildings/dataset-links.csv"
        const val LINKS_MAX_AGE_MS = 30L * 24 * 3600 * 1000
        private val lock = Mutex()

        /** quadkey -> its files, from `Location,QuadKey,Url,Size,UploadDate` lines (header and malformed lines skipped). */
        internal fun parseLinks(lines: List<String>): Map<String, List<Part>> {
            val out = HashMap<String, MutableList<Part>>()
            for (line in lines) {
                val c = line.split(',')
                if (c.size < 4 || c[1] == "QuadKey" || c[1].any { it !in '0'..'3' } || !c[2].startsWith("http")) continue
                out.getOrPut(c[1]) { ArrayList() } += Part(c[2], parseSize(c[3]))
            }
            return out
        }

        /** "74.7KB" / "13.0MB" / "1.1GB" / "512B" -> bytes (0 when unreadable). */
        internal fun parseSize(s: String): Long {
            val m = Regex("""^([0-9.]+)\s*([KMG]?B)$""").find(s.trim()) ?: return 0L
            val v = m.groupValues[1].toDoubleOrNull() ?: return 0L
            val mult = when (m.groupValues[2]) { "KB" -> 1024.0; "MB" -> 1024.0 * 1024; "GB" -> 1024.0 * 1024 * 1024; else -> 1.0 }
            return (v * mult).toLong()
        }
    }
}
