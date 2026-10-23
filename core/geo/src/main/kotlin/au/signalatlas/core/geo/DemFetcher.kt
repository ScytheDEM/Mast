package au.signalatlas.core.geo

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.math.floor

/** Free space (less the [DemFetcher] reserve) does not cover the download. */
class InsufficientStorageException(val requiredBytes: Long, val availableBytes: Long) :
    IOException("Need $requiredBytes bytes free for the elevation download, have $availableBytes")

/** An unmetered network was required (`allowMetered = false`) and the active one is metered. */
class MeteredNetworkException : IOException("The active network is metered")

/** A pinned request found another version of the COG (412, a different ETag, length or 404): start that COG over. */
private class CogChangedException(message: String) : IOException(message)

/**
 * Turns Copernicus GLO-90 COGs on S3 into [DemChunk]s in a [DemChunkStore], downloading only what is needed.
 *
 * Per COG: the first 64 KiB (more if the IFD reaches further) is read once and its [CogMeta] cached in memory and in
 * [metaDir] (`<cog name>.json`, holding the URL and ETag it describes). Then only the internal tiles that intersect
 * the requested chunks are fetched, adjacent byte ranges coalesced into one request. Every request after the header is
 * pinned with `If-Match` on the header's ETag. A 412, a changed ETag / length, or a 404 after the header means the file
 * changed under us: the cached header and that COG's partial work are dropped and the COG restarts from its header
 * (3 attempts, then [IOException]); two versions are never spliced. A COG whose header is 404 is ocean: zeros.
 *
 * A chunk is only written once every COG it touches is fully read, so an interrupted fetch leaves no partial chunk.
 * A chunk's first row and last column may lie on a whole degree, where the node belongs to the neighbouring COG
 * (the node on a whole-degree latitude is row 0 of the COG to its south, on a whole-degree longitude column 0 of the
 * COG to its east); a chunk touches up to four COGs. Nodes are exactly the COG's samples (1200 per degree both ways),
 * so the "resampling" to the chunk grid is rounding half away from zero, with NaN / no-data as 0.
 *
 * Fetching is serialised per instance. Decoding runs on [Dispatchers.Default], network and disk on [Dispatchers.IO].
 *
 * @param isMetered whether the active network is metered (see [GeoNetwork.isActiveNetworkMetered]).
 * @param freeBytes usable space of the volume holding the cache; [fetch] needs [RESERVE_BYTES] plus the download.
 */
class DemFetcher(
    private val http: OkHttpClient,
    private val store: DemChunkStore,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val metaDir: File = File(store.dir.parentFile ?: store.dir, "cogmeta"),
    private val freeBytes: () -> Long = { store.dir.usableSpace },
    private val isMetered: () -> Boolean = { false },
) {
    private data class CogKey(val latN: Int, val lonW: Int) {
        /** The file named for its south edge: the COG covering latitudes -36..-35 is `S36`. */
        val name: String
            get() {
                val south = latN - 1
                val ns = if (south < 0) String.format(Locale.ROOT, "S%02d", -south) else String.format(Locale.ROOT, "N%02d", south)
                val ew = if (lonW < 0) String.format(Locale.ROOT, "W%03d", -lonW) else String.format(Locale.ROOT, "E%03d", lonW)
                return "${ns}_00_${ew}_00"
            }
    }

    /** The nodes of one COG a chunk reads: rows / columns of that COG, inclusive. */
    private class Region(val cog: CogKey, val rowMin: Int, val rowMax: Int, val colMin: Int, val colMax: Int)

    /** One COG's decoded samples (only the tiles read are filled). */
    private class Grid(val values: FloatArray, val noData: Float?)

    private val lock = Mutex()
    private val headers = HashMap<CogKey, CogMeta>()
    private val oceanCogs = HashSet<CogKey>()

    /**
     * Bytes [fetch] would download for [chunks] that are not stored yet: the compressed size of the internal tiles
     * they need, each counted once. Reads (and caches) the COG headers; 0 for ocean.
     */
    suspend fun estimateBytes(chunks: Set<ChunkId>): Long = lock.withLock {
        val regions = regionsByCog(chunks.filter { !store.has(it) })
        var total = 0L
        for ((cog, list) in regions) total += withCog(cog) { meta -> if (meta == null) 0L else tilesFor(meta, list).sumOf { meta.byteCounts[it] } }
        total
    }

    /**
     * Makes sure every chunk of [chunks] is in the store, downloading what is missing. [progress] gets 0..1 by bytes.
     * Throws [MeteredNetworkException] on a metered network unless [allowMetered], [InsufficientStorageException] if
     * the free space is short, and [IOException] when the network fails or a COG keeps changing.
     */
    suspend fun fetch(chunks: Set<ChunkId>, allowMetered: Boolean, progress: (Float) -> Unit) {
        if (!allowMetered && isMetered()) throw MeteredNetworkException()
        lock.withLock {
            val todo = chunks.filter { !store.has(it) }
            if (todo.isEmpty()) {
                progress(1f)
                return
            }
            val regions = todo.associateWith { regionsOf(it) }
            val cogs = regions.values.flatten().map { it.cog }.distinct()
            val byCog = regionsByCog(todo)
            val totals = HashMap<CogKey, Long>()
            val done = HashMap<CogKey, Long>()
            for (cog in cogs) {
                totals[cog] = withCog(cog) { meta -> if (meta == null) 0L else tilesFor(meta, byCog.getValue(cog)).sumOf { meta.byteCounts[it] } }
            }
            val required = RESERVE_BYTES + totals.values.sum()
            val free = freeBytes()
            if (free < required) throw InsufficientStorageException(required, free)

            fun report() {
                val total = totals.values.sum()
                progress(if (total == 0L) 0f else (done.values.sum().toFloat() / total).coerceIn(0f, 1f))
            }
            report()
            val grids = HashMap<CogKey, Grid?>()
            val loaded = HashSet<CogKey>()
            val pending = LinkedHashMap(regions)
            for (cog in cogs) {
                currentCoroutineContext().ensureActive()
                grids[cog] = withCog(cog) { meta ->
                    totals[cog] = if (meta == null) 0L else tilesFor(meta, byCog.getValue(cog)).sumOf { meta.byteCounts[it] }
                    done[cog] = 0L
                    if (meta == null) null else loadCog(cog, meta, byCog.getValue(cog)) { bytes -> done[cog] = done.getValue(cog) + bytes; report() }
                }
                loaded += cog
                val ready = pending.filter { (_, rs) -> rs.all { it.cog in loaded } }
                for ((id, _) in ready) {
                    withContext(Dispatchers.Default) {
                        val chunk = buildChunk(id, grids)
                        withContext(Dispatchers.IO) { store.put(chunk) }
                    }
                    pending.remove(id)
                }
                // Free the samples of COGs no pending chunk still reads.
                val stillNeeded = pending.values.flatten().map { it.cog }.toSet()
                for (g in grids.keys.toList()) if (g !in stillNeeded) grids[g] = null
            }
            progress(1f)
        }
    }

    // ---- planning -------------------------------------------------------------------------------------------

    private fun regionsByCog(chunks: Collection<ChunkId>): Map<CogKey, List<Region>> =
        chunks.flatMap { regionsOf(it) }.groupBy { it.cog }

    /** The COGs and node rectangles chunk [id] reads (up to 2 x 2 of them). */
    private fun regionsOf(id: ChunkId): List<Region> {
        val steps = ChunkMath.STEPS
        val perDeg = ChunkMath.NODES_PER_DEGREE
        val south = id.latIdx * steps - 90 * perDeg
        val west = id.lonIdx * steps - 180 * perDeg
        // Latitude nodes south..south+300: latN is ceil(node / 1200), so a whole-degree node is row 0 of the COG south of it.
        val latGroups = ArrayList<Triple<Int, Int, Int>>() // latN, first node, last node
        for (n in south..south + steps) {
            val latN = Math.floorDiv(n + perDeg - 1, perDeg)
            val last = latGroups.lastOrNull()
            if (last != null && last.first == latN) latGroups[latGroups.size - 1] = Triple(latN, last.second, n) else latGroups += Triple(latN, n, n)
        }
        val lonGroups = ArrayList<Triple<Int, Int, Int>>() // lonW, first node, last node
        for (n in west..west + steps) {
            val lonW = Math.floorDiv(n, perDeg)
            val last = lonGroups.lastOrNull()
            if (last != null && last.first == lonW) lonGroups[lonGroups.size - 1] = Triple(lonW, last.second, n) else lonGroups += Triple(lonW, n, n)
        }
        return latGroups.flatMap { (latN, n0, n1) ->
            lonGroups.map { (lonW, c0, c1) ->
                Region(CogKey(latN, lonW), latN * perDeg - n1, latN * perDeg - n0, c0 - lonW * perDeg, c1 - lonW * perDeg)
            }
        }
    }

    /** Indices of the internal tiles of [meta] that [regions] touch, deduplicated. */
    private fun tilesFor(meta: CogMeta, regions: List<Region>): Set<Int> {
        val tiles = LinkedHashSet<Int>()
        for (r in regions) {
            for (ty in r.rowMin / meta.tileHeight..r.rowMax / meta.tileHeight) {
                for (tx in r.colMin / meta.tileWidth..r.colMax / meta.tileWidth) tiles += ty * meta.tilesAcross + tx
            }
        }
        return tiles
    }

    // ---- one COG --------------------------------------------------------------------------------------------

    /**
     * Runs [block] with [cog]'s header (null: ocean). When a pinned request sees another version of the file the
     * cached header is dropped and the block runs again from a fresh header; after [MAX_ATTEMPTS] it gives up.
     */
    private suspend fun <T> withCog(cog: CogKey, block: suspend (CogMeta?) -> T): T {
        var last: CogChangedException? = null
        repeat(MAX_ATTEMPTS) {
            try {
                return block(header(cog))
            } catch (e: CogChangedException) {
                forget(cog)
                last = e
            }
        }
        throw IOException("${cog.name} changed on the server $MAX_ATTEMPTS times in a row: ${last?.message}", last)
    }

    /** Fetches the internal tiles [regions] need into a new [Grid]; adjacent ranges go in one request. */
    private suspend fun loadCog(cog: CogKey, meta: CogMeta, regions: List<Region>, onBytes: (Long) -> Unit): Grid {
        val perDeg = CogMeta.SIZE
        val grid = Grid(FloatArray(perDeg * perDeg), meta.noData)
        val tiles = tilesFor(meta, regions).filter { meta.byteCounts[it] > 0 }.sortedBy { meta.offsets[it] }
        var i = 0
        while (i < tiles.size) {
            var j = i
            var end = meta.offsets[tiles[i]] + meta.byteCounts[tiles[i]]
            while (j + 1 < tiles.size && meta.offsets[tiles[j + 1]] == end) {
                j++
                end += meta.byteCounts[tiles[j]]
            }
            val start = meta.offsets[tiles[i]]
            val bytes = rangeGet(cog, meta.etag, meta.fileLength, start, end - 1)
            onBytes(bytes.size.toLong())
            withContext(Dispatchers.Default) {
                for (k in i..j) {
                    val t = tiles[k]
                    val ty = t / meta.tilesAcross
                    val tx = t % meta.tilesAcross
                    val rows = minOf(meta.tileHeight, perDeg - ty * meta.tileHeight)
                    val cols = minOf(meta.tileWidth, perDeg - tx * meta.tileWidth)
                    val from = (meta.offsets[t] - start).toInt()
                    val decoded = CogReader.decodeTile(meta, bytes.copyOfRange(from, from + meta.byteCounts[t].toInt()), rows)
                    for (r in 0 until rows) {
                        System.arraycopy(decoded, r * meta.tileWidth, grid.values, (ty * meta.tileHeight + r) * perDeg + tx * meta.tileWidth, cols)
                    }
                }
            }
            i = j + 1
        }
        return grid
    }

    private fun buildChunk(id: ChunkId, grids: Map<CogKey, Grid?>): DemChunk {
        val n = ChunkMath.NODES
        val perDeg = ChunkMath.NODES_PER_DEGREE
        val south = id.latIdx * ChunkMath.STEPS - 90 * perDeg
        val west = id.lonIdx * ChunkMath.STEPS - 180 * perDeg
        val lonWs = IntArray(n) { Math.floorDiv(west + it, perDeg) }
        val cols = IntArray(n) { west + it - lonWs[it] * perDeg }
        val samples = ShortArray(n * n)
        for (r in 0 until n) {
            val node = south + r
            val latN = Math.floorDiv(node + perDeg - 1, perDeg)
            val row = latN * perDeg - node
            var curLon = Int.MIN_VALUE
            var grid: Grid? = null
            for (c in 0 until n) {
                if (lonWs[c] != curLon) {
                    curLon = lonWs[c]
                    grid = grids[CogKey(latN, curLon)]
                }
                val g = grid ?: continue // ocean: 0
                samples[r * n + c] = toMetres(g.values[row * perDeg + cols[c]], g.noData)
            }
        }
        return DemChunk(id, samples)
    }

    // ---- headers --------------------------------------------------------------------------------------------

    private fun urlOf(cog: CogKey) = "$baseUrl/Copernicus_DSM_COG_30_${cog.name}_DEM/Copernicus_DSM_COG_30_${cog.name}_DEM.tif"

    private fun metaFile(cog: CogKey) = File(metaDir, "${cog.name}.json")

    /** The header of [cog] (memory, then disk, then the network), or null when the COG does not exist (ocean). */
    private suspend fun header(cog: CogKey): CogMeta? {
        if (cog in oceanCogs) return null
        headers[cog]?.let { return it }
        withContext(Dispatchers.IO) { readMetaFile(cog) }?.let {
            headers[cog] = it
            return it
        }
        return fetchHeader(cog)
    }

    private fun forget(cog: CogKey) {
        headers.remove(cog)
        metaFile(cog).delete()
    }

    private suspend fun fetchHeader(cog: CogKey): CogMeta? {
        val url = urlOf(cog)
        val first = http.cancellableCall(rangeRequest(url, null, 0, HEADER_BYTES - 1L)) { resp ->
            if (resp.code == 404) return@cancellableCall null
            if (resp.code != 206) throw IOException("HTTP ${resp.code} from $url (expected 206)")
            val etag = resp.header("ETag") ?: throw IOException("$url has no ETag, so it cannot be pinned")
            val range = parseContentRange(resp) ?: throw IOException("$url sent no usable Content-Range")
            if (range.first != 0L) throw IOException("$url answered the wrong range")
            Triple(etag, range.third, resp.body.bytes())
        }
        if (first == null) {
            oceanCogs += cog
            return null
        }
        val (etag, length, initial) = first
        var buf = initial
        while (true) {
            try {
                val meta = CogReader.parseHeader(buf, length, etag)
                headers[cog] = meta
                withContext(Dispatchers.IO) { writeMetaFile(cog, meta) }
                return meta
            } catch (e: NeedMoreHeader) {
                val want = maxOf(e.bytesNeeded, buf.size + HEADER_BYTES.toLong()).coerceAtMost(length)
                buf += rangeGet(cog, etag, length, buf.size.toLong(), want - 1)
            }
        }
    }

    private fun readMetaFile(cog: CogKey): CogMeta? {
        val f = metaFile(cog)
        if (!f.isFile) return null
        return try {
            val j = JSONObject(f.readText())
            if (j.getString("url") != urlOf(cog)) return null
            val offsets = j.getJSONArray("offsets")
            val counts = j.getJSONArray("byteCounts")
            CogMeta(
                etag = j.getString("etag"),
                fileLength = j.getLong("fileLength"),
                latNorth = j.getInt("latNorth"),
                lonWest = j.getInt("lonWest"),
                tileWidth = j.getInt("tileWidth"),
                tileHeight = j.getInt("tileHeight"),
                predictor = j.getInt("predictor"),
                noData = if (j.has("noData")) j.getDouble("noData").toFloat() else null,
                offsets = LongArray(offsets.length()) { offsets.getLong(it) },
                byteCounts = LongArray(counts.length()) { counts.getLong(it) },
            )
        } catch (_: Exception) {
            f.delete() // unreadable: refetch
            null
        }
    }

    private fun writeMetaFile(cog: CogKey, meta: CogMeta) {
        try {
            metaDir.mkdirs()
            val j = JSONObject()
                .put("url", urlOf(cog)).put("etag", meta.etag).put("fileLength", meta.fileLength)
                .put("latNorth", meta.latNorth).put("lonWest", meta.lonWest)
                .put("tileWidth", meta.tileWidth).put("tileHeight", meta.tileHeight).put("predictor", meta.predictor)
                .put("offsets", JSONArray().also { a -> meta.offsets.forEach { a.put(it) } })
                .put("byteCounts", JSONArray().also { a -> meta.byteCounts.forEach { a.put(it) } })
            meta.noData?.let { j.put("noData", it.toDouble()) }
            val tmp = File(metaDir, "${cog.name}.json.tmp")
            FileOutputStream(tmp).use { it.write(j.toString().toByteArray()); it.flush(); it.fd.sync() }
            Files.move(tmp.toPath(), metaFile(cog).toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            // The disk copy is an optimisation; the header stays in memory.
        }
    }

    // ---- HTTP -----------------------------------------------------------------------------------------------

    private fun rangeRequest(url: String, etag: String?, start: Long, endInclusive: Long): Request =
        Request.Builder().url(url).get()
            .header("Range", "bytes=$start-$endInclusive")
            .header("Accept-Encoding", "identity") // byte-exact: no transparent compression of the DEFLATE tiles
            .apply { if (etag != null) header("If-Match", etag) }
            .build()

    /** Bytes [start, endInclusive] of [cog]'s file, pinned to [etag]; [CogChangedException] if it is another version. */
    private suspend fun rangeGet(cog: CogKey, etag: String, length: Long, start: Long, endInclusive: Long): ByteArray {
        val url = urlOf(cog)
        return http.cancellableCall(rangeRequest(url, etag, start, endInclusive)) { resp ->
            if (resp.code == 412 || resp.code == 404) throw CogChangedException("HTTP ${resp.code} for ${cog.name}")
            if (resp.code != 206) throw IOException("HTTP ${resp.code} from $url (expected 206)")
            val served = resp.header("ETag")
            if (served != null && served != etag) throw CogChangedException("ETag $served, expected $etag")
            val range = parseContentRange(resp) ?: throw IOException("$url sent no usable Content-Range")
            if (range.third != length) throw CogChangedException("length ${range.third}, expected $length")
            if (range.first != start || range.second != endInclusive) throw IOException("$url answered the wrong range")
            val body = resp.body.bytes()
            if (body.size.toLong() != endInclusive - start + 1) throw IOException("Short read from $url: ${body.size} bytes")
            body
        }
    }

    /** (first, last, total) of a `Content-Range: bytes a-b/total` header. */
    private fun parseContentRange(resp: Response): Triple<Long, Long, Long>? {
        val m = CONTENT_RANGE.matchEntire(resp.header("Content-Range")?.trim() ?: return null) ?: return null
        return Triple(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLong())
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://copernicus-dem-90m.s3.amazonaws.com"

        /** Free space kept for the rest of the app on top of the download. */
        const val RESERVE_BYTES = 200L * 1024 * 1024

        private const val HEADER_BYTES = 64 * 1024
        private const val MAX_ATTEMPTS = 3
        private val CONTENT_RANGE = Regex("""^bytes (\d+)-(\d+)/(\d+)$""")

        /** int16 metres, rounded half away from zero; NaN, infinite and no-data samples are 0. */
        internal fun toMetres(v: Float, noData: Float?): Short {
            if (v.isNaN() || v.isInfinite() || (noData != null && v == noData)) return 0
            val d = v.toDouble()
            val r = if (d >= 0) floor(d + 0.5) else -floor(-d + 0.5)
            return r.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
        }
    }
}

/** Runs the blocking call on IO; cancelling the coroutine cancels the OkHttp call. */
private suspend fun <T> OkHttpClient.cancellableCall(request: Request, block: suspend (Response) -> T): T =
    withContext(Dispatchers.IO) {
        val call = newCall(request)
        coroutineScope {
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                try {
                    call.execute().use { block(it) }
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                }
            } finally {
                watcher.cancel()
            }
        }
    }
