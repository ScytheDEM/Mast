package au.signalatlas.core.coverage

import au.signalatlas.engine.TerrainTable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

/**
 * Identity of one terrain table: the tower extract it was built for ([stamp]), the site (position in 1e-5 degrees,
 * about 1 m), the Terrain rule 6 frequency key (MHz, 10 MHz steps) and antenna height key (m), and [demId], which
 * names the DEM rectangle the table was computed from (the table's mask depends on it).
 */
internal data class TableKey(
    val stamp: String, val latE5: Int, val lonE5: Int, val fkey: Int, val hkey: Int, val demId: Long,
) {
    val siteId: String get() = "${latE5}x$lonE5"
    val fileName: String get() = "${siteId}_${fkey}_$hkey.tbl"
}

/**
 * Terrain tables of the current tower extract: a memory LRU of [memoryTables] tables (about 100 KiB each) over a disk
 * cache `dir/<stamp>/<siteId>_<fkey>_<hkey>.tbl`, kept under [diskBytes] by evicting the least recently used file.
 *
 * File layout (little-endian): magic `"SATB"`, version int, demId long, the 92,160 table bytes, the 11,520 mask
 * bytes, CRC32 of everything before it. A file that fails magic, version, length or CRC is a miss and is deleted; one
 * written for another DEM rectangle ([TableKey.demId]) is a miss too and is replaced when the table is rebuilt.
 * Writes are atomic (temp file, rename). The first use of a stamp deletes the directories of other stamps. Thread-safe;
 * file IO happens outside the locks.
 */
class TerrainTableStore(
    private val dir: File,
    private val diskBytes: Long = 128L shl 20,
    private val memoryTables: Int = 256,
) {
    private val memory = object : LinkedHashMap<TableKey, TerrainTable>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TableKey, TerrainTable>) = size > memoryTables
    }

    private val diskLock = Any()
    /** `<stamp dir>/<file>` -> size, least recently used first. Built lazily from the directory. */
    private var diskIndex: LinkedHashMap<String, Long>? = null
    private var diskUsed = 0L
    private var prunedFor: String? = null
    private val tempCounter = AtomicLong()

    internal val memorySize: Int get() = synchronized(memory) { memory.size }
    internal val diskUsage: Long get() = synchronized(diskLock) { index(); diskUsed }

    /** The table for [key] from memory or disk, or null. May read a file: call off the main thread. */
    internal fun get(key: TableKey): TerrainTable? {
        synchronized(memory) { memory[key] }?.let { return it }
        val rel = relPath(key)
        synchronized(diskLock) {
            prune(key.stamp)
            if (rel !in index()) return null
            index()[rel] // touch
        }
        val file = File(dir, rel)
        file.setLastModified(System.currentTimeMillis()) // keeps the order across restarts
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            return null
        }
        val table = decode(key.demId, bytes)
        if (table == null) {
            if (!demMismatch(bytes, key.demId)) drop(rel, file)
            return null
        }
        synchronized(memory) { memory[key] = table }
        return table
    }

    /** Stores [table] in memory and on disk (a disk failure leaves it memory-only). */
    internal fun put(key: TableKey, table: TerrainTable) {
        synchronized(memory) { memory[key] = table }
        val rel = relPath(key)
        val bytes = encode(key.demId, table)
        val target = File(dir, rel)
        val tmp = File(target.parentFile, "${target.name}.${tempCounter.incrementAndGet()}.tmp")
        try {
            synchronized(diskLock) { prune(key.stamp) }
            target.parentFile.mkdirs()
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return
            }
            synchronized(diskLock) {
                val idx = index()
                diskUsed += bytes.size - (idx.put(rel, bytes.size.toLong()) ?: 0L)
                trim(idx)
            }
        } catch (e: IOException) {
            tmp.delete()
        }
    }

    private fun relPath(key: TableKey) = "${dirName(key.stamp)}/${key.fileName}"

    private fun dirName(stamp: String) = stamp.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** Caller holds [diskLock]. */
    private fun index(): LinkedHashMap<String, Long> {
        diskIndex?.let { return it }
        val idx = LinkedHashMap<String, Long>(64, 0.75f, true)
        val files = ArrayList<Pair<String, File>>()
        dir.listFiles()?.forEach { sub ->
            if (sub.isDirectory) sub.listFiles()?.forEach { f ->
                if (f.name.endsWith(".tmp")) f.delete() else if (f.name.endsWith(".tbl")) files += "${sub.name}/${f.name}" to f
            }
        }
        for ((rel, f) in files.sortedBy { it.second.lastModified() }) {
            idx[rel] = f.length()
            diskUsed += f.length()
        }
        diskIndex = idx
        return idx
    }

    /** Deletes the directories of every stamp but [stamp], once per stamp. Caller holds [diskLock]. */
    private fun prune(stamp: String) {
        if (prunedFor == stamp) return
        prunedFor = stamp
        val keep = dirName(stamp)
        val idx = index()
        dir.listFiles()?.forEach { sub -> if (sub.isDirectory && sub.name != keep) sub.deleteRecursively() }
        val it = idx.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (!e.key.startsWith("$keep/")) { it.remove(); diskUsed -= e.value }
        }
    }

    /** Caller holds [diskLock]. */
    private fun trim(idx: LinkedHashMap<String, Long>) {
        val it = idx.entries.iterator()
        while (diskUsed > diskBytes && it.hasNext()) {
            val e = it.next()
            it.remove()
            diskUsed -= e.value
            File(dir, e.key).delete()
        }
    }

    private fun drop(rel: String, file: File) {
        synchronized(diskLock) { index().remove(rel)?.let { diskUsed -= it } }
        file.delete()
    }

    internal companion object {
        const val FILE_BYTES = 8 + 8 + TerrainTable.CELLS + TerrainTable.MASK_BYTES + 4
        private const val MAGIC = 0x42544153 // "SATB" little-endian
        private const val VERSION = 1

        fun encode(demId: Long, t: TerrainTable): ByteArray {
            val buf = ByteBuffer.allocate(FILE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(MAGIC).putInt(VERSION).putLong(demId)
            buf.put(prefix(t.table, TerrainTable.CELLS))
            buf.put(prefix(t.mask, TerrainTable.MASK_BYTES))
            val crc = CRC32().apply { update(buf.array(), 0, FILE_BYTES - 4) }
            buf.putInt(crc.value.toInt())
            return buf.array()
        }

        private fun prefix(b: ByteBuffer, n: Int): ByteBuffer = b.duplicate().also { it.clear(); it.limit(n) }

        /** Null when [bytes] is not a valid entry for [demId]. */
        fun decode(demId: Long, bytes: ByteArray): TerrainTable? {
            if (bytes.size != FILE_BYTES) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.getInt() != MAGIC || buf.getInt() != VERSION || buf.getLong() != demId) return null
            val crc = CRC32().apply { update(bytes, 0, FILE_BYTES - 4) }
            if (buf.getInt(FILE_BYTES - 4) != crc.value.toInt()) return null
            val table = ByteBuffer.allocateDirect(TerrainTable.CELLS)
            val mask = ByteBuffer.allocateDirect(TerrainTable.MASK_BYTES)
            table.put(bytes, 16, TerrainTable.CELLS).rewind()
            mask.put(bytes, 16 + TerrainTable.CELLS, TerrainTable.MASK_BYTES).rewind()
            return TerrainTable(table, mask)
        }

        /** True when [bytes] is a well-formed header for another DEM rectangle (a stale file, not a corrupt one). */
        private fun demMismatch(bytes: ByteArray, demId: Long): Boolean {
            if (bytes.size != FILE_BYTES) return false
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return buf.getInt() == MAGIC && buf.getInt() == VERSION && buf.getLong() != demId &&
                buf.getInt(FILE_BYTES - 4) == CRC32().apply { update(bytes, 0, FILE_BYTES - 4) }.value.toInt()
        }
    }
}
