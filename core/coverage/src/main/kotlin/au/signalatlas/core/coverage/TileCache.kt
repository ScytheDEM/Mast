package au.signalatlas.core.coverage

import au.signalatlas.engine.RenderedTile
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/**
 * Two-level cache of raw rendered tiles (RSRP, SINR and probability codes plus the best-server indices), keyed by
 * [TileKey]. Memory: LRU of at most [memoryTiles] tiles. Disk: LRU by bytes, at most [diskBytes], one file per key
 * under [dir] (name = hash of the key). Disk writes run on a private thread, never on the caller's (render) thread;
 * a read that fails its magic, version, length or CRC check is a miss and deletes the file.
 *
 * File layout (little-endian): magic `"SATL"`, version int (both 8 bytes together), rsrp, sinr, prob (65536 bytes
 * each), best (65536 shorts), CRC32 of everything before it.
 */
class TileCache(
    private val dir: File,
    private val memoryTiles: Int = 96,
    private val diskBytes: Long = 256L shl 20,
) {
    private val memory = object : LinkedHashMap<TileKey, RenderedTile>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, RenderedTile>) = size > memoryTiles
    }

    private val diskLock = Any()
    /** File name -> size, least recently used first. Built lazily from the directory. */
    private var diskIndex: LinkedHashMap<String, Long>? = null
    private var diskUsed = 0L

    private val writer = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(MAX_PENDING_WRITES),
        { r -> Thread(r, "coverage-tile-disk").apply { isDaemon = true } },
        // A dropped write only costs a later re-render.
        ThreadPoolExecutor.DiscardPolicy(),
    )

    val memorySize: Int get() = synchronized(memory) { memory.size }

    /** Bytes the disk entries occupy (after the index has been built by the first disk operation). */
    internal val diskUsage: Long get() = synchronized(diskLock) { index(); diskUsed }

    fun getMemory(key: TileKey): RenderedTile? = synchronized(memory) { memory[key] }

    /** The disk entry for [key] (promoted into memory), or null if absent or corrupt. Blocking file IO. */
    fun getDisk(key: TileKey): RenderedTile? {
        val name = fileName(key)
        val file = File(dir, name)
        synchronized(diskLock) {
            if (name !in index()) return null
            index()[name] // touch: most recently used
        }
        file.setLastModified(System.currentTimeMillis()) // keeps the order across restarts
        val tile = try {
            decode(key, file.readBytes())
        } catch (e: IOException) {
            null
        }
        if (tile == null) {
            drop(name, file)
            return null
        }
        putMemory(key, tile)
        return tile
    }

    /** Memory immediately; the disk copy is written in the background. */
    fun put(key: TileKey, tile: RenderedTile) {
        putMemory(key, tile)
        writer.execute { writeDisk(key, tile) }
    }

    /** Blocks until every write queued so far has finished. */
    internal fun flush() {
        val done = java.util.concurrent.CountDownLatch(1)
        writer.execute { done.countDown() }
        done.await(10, TimeUnit.SECONDS)
    }

    private fun putMemory(key: TileKey, tile: RenderedTile) {
        synchronized(memory) { memory[key] = tile }
    }

    private fun index(): LinkedHashMap<String, Long> {
        diskIndex?.let { return it }
        val idx = LinkedHashMap<String, Long>(64, 0.75f, true)
        dir.listFiles()?.sortedBy { it.lastModified() }?.forEach { f ->
            if (f.name.endsWith(TMP_SUFFIX)) f.delete()
            else if (f.name.endsWith(FILE_SUFFIX)) { idx[f.name] = f.length(); diskUsed += f.length() }
        }
        diskIndex = idx
        return idx
    }

    private fun writeDisk(key: TileKey, tile: RenderedTile) {
        val name = fileName(key)
        val bytes = encode(tile)
        try {
            dir.mkdirs()
            val tmp = File(dir, name + TMP_SUFFIX)
            tmp.writeBytes(bytes)
            val target = File(dir, name)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return
            }
            synchronized(diskLock) {
                val idx = index()
                diskUsed += bytes.size - (idx.put(name, bytes.size.toLong()) ?: 0L)
                trim(idx)
            }
        } catch (e: IOException) {
            // Disk full or unwritable: the tile just stays memory-only.
        }
    }

    /** Removes least recently used files until within [diskBytes]. Caller holds [diskLock]. */
    private fun trim(idx: LinkedHashMap<String, Long>) {
        val it = idx.entries.iterator()
        while (diskUsed > diskBytes && it.hasNext()) {
            val e = it.next()
            it.remove()
            diskUsed -= e.value
            File(dir, e.key).delete()
        }
    }

    private fun drop(name: String, file: File) {
        synchronized(diskLock) { index().remove(name)?.let { diskUsed -= it } }
        file.delete()
    }

    internal companion object {
        const val FILE_BYTES = 8 + 3 * RenderedTile.PIXELS + 2 * RenderedTile.PIXELS + 4
        private const val MAGIC = 0x4C544153 // "SATL" little-endian
        private const val VERSION = 1
        private const val FILE_SUFFIX = ".tile"
        private const val TMP_SUFFIX = ".tmp"
        private const val MAX_PENDING_WRITES = 64

        fun fileName(key: TileKey): String {
            val id = "${key.z}/${key.x}/${key.y}/${key.selectionId}/${key.towerStamp}/${key.patternsEtag}/${key.engineVersion}"
            val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            return digest.take(16).joinToString("") { "%02x".format(it) } + FILE_SUFFIX
        }

        fun encode(t: RenderedTile): ByteArray {
            val buf = ByteBuffer.allocate(FILE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(MAGIC).putInt(VERSION).put(t.rsrp).put(t.sinr).put(t.prob)
            buf.asShortBuffer().put(t.best)
            buf.position(buf.position() + 2 * RenderedTile.PIXELS)
            val crc = CRC32().apply { update(buf.array(), 0, FILE_BYTES - 4) }
            buf.putInt(crc.value.toInt())
            return buf.array()
        }

        /** Null when [bytes] is not a valid entry; the tile's address comes from [key]. */
        fun decode(key: TileKey, bytes: ByteArray): RenderedTile? {
            if (bytes.size != FILE_BYTES) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.getInt() != MAGIC || buf.getInt() != VERSION) return null
            val crc = CRC32().apply { update(bytes, 0, FILE_BYTES - 4) }
            if (buf.getInt(FILE_BYTES - 4) != crc.value.toInt()) return null
            val n = RenderedTile.PIXELS
            val rsrp = ByteArray(n).also { buf.get(it) }
            val sinr = ByteArray(n).also { buf.get(it) }
            val prob = ByteArray(n).also { buf.get(it) }
            val best = ShortArray(n).also { buf.asShortBuffer().get(it) }
            return RenderedTile(key.z, key.x, key.y, rsrp, sinr, prob, best)
        }
    }
}
