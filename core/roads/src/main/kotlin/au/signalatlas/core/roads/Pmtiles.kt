package au.signalatlas.core.roads

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/** Blocking random access to a PMTiles archive. May return fewer bytes than asked at the end of the archive. */
interface RangeSource {
    @Throws(IOException::class)
    fun read(offset: Long, length: Int): ByteArray
}

/** The archive changed on the server (ETag mismatch) or is not a PMTiles v3 file. */
class PmtilesException(message: String) : IOException(message)

class PmtilesHeader(
    val rootOffset: Long, val rootLength: Long,
    val leafOffset: Long, val leafLength: Long,
    val dataOffset: Long, val dataLength: Long,
    val internalCompression: Int, val tileCompression: Int,
    val minZoom: Int, val maxZoom: Int,
) {
    companion object {
        const val SIZE = 127
        const val COMPRESSION_NONE = 1
        const val COMPRESSION_GZIP = 2

        fun parse(b: ByteArray): PmtilesHeader {
            if (b.size < SIZE || String(b, 0, 7, Charsets.US_ASCII) != "PMTiles") throw PmtilesException("not a PMTiles archive")
            if (b[7].toInt() != 3) throw PmtilesException("unsupported PMTiles version ${b[7]}")
            fun u64(o: Int): Long {
                var v = 0L
                for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
                return v
            }
            return PmtilesHeader(
                u64(8), u64(16), u64(40), u64(48), u64(56), u64(64),
                b[97].toInt() and 0xFF, b[98].toInt() and 0xFF, b[100].toInt() and 0xFF, b[101].toInt() and 0xFF,
            )
        }
    }
}

/** One directory entry: tiles [tileId, tileId + runLength) share the data at [offset]/[length]; runLength 0 points at a leaf directory. */
class PmtilesEntry(val tileId: Long, val runLength: Int, val length: Int, val offset: Long)

object PmtilesIds {
    /** The Hilbert-curve tile id: all lower zoom levels first, then the position on the curve at [z]. */
    fun tileId(z: Int, x: Int, y: Int): Long {
        require(z in 0..26 && x >= 0 && y >= 0 && x < (1 shl z) && y < (1 shl z)) { "tile $z/$x/$y out of range" }
        var acc = 0L
        for (i in 0 until z) acc += 1L shl (2 * i)
        val n = 1L shl z
        var tx = x.toLong()
        var ty = y.toLong()
        var d = 0L
        var s = n / 2
        while (s > 0) {
            val rx = if (tx and s > 0) 1L else 0L
            val ry = if (ty and s > 0) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) { tx = n - 1 - tx; ty = n - 1 - ty }
                val t = tx; tx = ty; ty = t
            }
            s /= 2
        }
        return acc + d
    }
}

internal fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }

/**
 * Reads tiles from a PMTiles v3 archive: the header and root directory come from the first read, leaf directories
 * (kept in a small LRU) and tile data from further range reads. Directories and tiles are gzip or uncompressed.
 */
class PmtilesReader private constructor(private val source: RangeSource, val header: PmtilesHeader, private val root: List<PmtilesEntry>) {
    private val leafCache = object : LinkedHashMap<Long, List<PmtilesEntry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<PmtilesEntry>>?) = size > LEAF_CACHE
    }

    /** The decompressed tile at z/x/y, or null when the archive has none there. */
    @Throws(IOException::class)
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < header.minZoom || z > header.maxZoom) return null
        val id = PmtilesIds.tileId(z, x, y)
        var dir = root
        repeat(MAX_DEPTH) {
            val e = find(dir, id) ?: return null
            if (e.runLength > 0) {
                val raw = readExactly(header.dataOffset + e.offset, e.length)
                return decompress(raw, header.tileCompression)
            }
            dir = leaf(e)
        }
        throw PmtilesException("directories nested too deeply")
    }

    private fun leaf(e: PmtilesEntry): List<PmtilesEntry> {
        val key = e.offset
        synchronized(leafCache) { leafCache[key]?.let { return it } }
        val dir = parseDirectory(decompress(readExactly(header.leafOffset + e.offset, e.length), header.internalCompression))
        synchronized(leafCache) { leafCache[key] = dir }
        return dir
    }

    private fun readExactly(offset: Long, length: Int): ByteArray {
        val b = source.read(offset, length)
        if (b.size != length) throw PmtilesException("short read at $offset: ${b.size} of $length")
        return b
    }

    companion object {
        const val FIRST_READ = 16_384
        private const val LEAF_CACHE = 64
        private const val MAX_DEPTH = 4

        @Throws(IOException::class)
        fun open(source: RangeSource): PmtilesReader {
            val first = source.read(0, FIRST_READ)
            val header = PmtilesHeader.parse(first)
            val rootBytes = if (header.rootOffset + header.rootLength <= first.size) {
                first.copyOfRange(header.rootOffset.toInt(), (header.rootOffset + header.rootLength).toInt())
            } else {
                source.read(header.rootOffset, header.rootLength.toInt())
            }
            return PmtilesReader(source, header, parseDirectory(decompress(rootBytes, header.internalCompression)))
        }

        private fun decompress(b: ByteArray, compression: Int): ByteArray = when (compression) {
            PmtilesHeader.COMPRESSION_NONE, 0 -> b
            PmtilesHeader.COMPRESSION_GZIP -> gunzip(b)
            else -> throw PmtilesException("unsupported compression $compression")
        }

        /** The last entry whose tileId is <= [id] that covers it (a run, or a leaf directory pointer). */
        internal fun find(dir: List<PmtilesEntry>, id: Long): PmtilesEntry? {
            var lo = 0
            var hi = dir.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val e = dir[mid]
                if (e.tileId <= id) lo = mid + 1 else hi = mid - 1
            }
            val e = dir.getOrNull(hi) ?: return null
            return when {
                e.runLength == 0 -> e
                id - e.tileId < e.runLength -> e
                else -> null
            }
        }

        /** Directory: count, delta tile ids, run lengths, lengths, then offsets (0 = directly after the previous entry, else offset + 1). */
        internal fun parseDirectory(b: ByteArray): List<PmtilesEntry> {
            val r = ProtoReader(b)
            val n = r.varint().toInt()
            if (n < 0 || n > b.size) throw PmtilesException("bad directory")
            val ids = LongArray(n)
            var last = 0L
            for (i in 0 until n) { last += r.varint(); ids[i] = last }
            val runs = IntArray(n) { r.varint().toInt() }
            val lens = IntArray(n) { r.varint().toInt() }
            val out = ArrayList<PmtilesEntry>(n)
            for (i in 0 until n) {
                val o = r.varint()
                val offset = if (o == 0L && i > 0) out[i - 1].offset + out[i - 1].length else o - 1
                out += PmtilesEntry(ids[i], runs[i], lens[i], offset)
            }
            return out
        }
    }
}
