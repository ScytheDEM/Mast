package au.signalatlas.core.geo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The on-device elevation cache: one `<latIdx>_<lonIdx>.dem` file per [ChunkId] in [dir] (see [DemChunk] for the
 * format), kept under [budgetBytes] by evicting the least recently used chunks.
 *
 * - Writes are atomic: build in memory, write `<name>.tmp`, fsync, rename. Leftover `.tmp` files are removed on open.
 * - A chunk that fails validation (CRC, header, length) when read is deleted and reported as a miss.
 * - Recency is the time of the last [get] / [put], kept in memory and mirrored to the file's mtime (so it survives a
 *   restart without an index file).
 * - Chunks of a [pin] (a future region pack) and chunks between [acquire] and [release] (a table build reading them)
 *   are never evicted. Pins persist in `pins.json` in [dir]; in-use counts do not (they belong to live code).
 * - The budget is read on every [put] and by [trim]; if only pinned / in-use chunks remain, the cache stays over it.
 *
 * Thread-safe. Reads of chunk files happen outside the lock.
 */
class DemChunkStore(
    val dir: File,
    private val budgetBytes: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val sizes = HashMap<ChunkId, Long>()
    private val lastAccess = HashMap<ChunkId, Long>()
    private val inUse = HashMap<ChunkId, Int>()
    private val pins = LinkedHashMap<String, Set<ChunkId>>()
    private var lastTick = 0L
    private val _bytesUsed = MutableStateFlow(0L)

    /** Total size of the chunk files. */
    val bytesUsed: StateFlow<Long> = _bytesUsed.asStateFlow()

    init {
        dir.mkdirs()
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".tmp")) {
                f.delete()
            } else {
                parseName(f.name)?.let { id ->
                    sizes[id] = f.length()
                    lastAccess[id] = f.lastModified()
                }
            }
        }
        loadPins()
        _bytesUsed.value = sizes.values.sum()
    }

    fun has(latIdx: Int, lonIdx: Int): Boolean = has(ChunkId(latIdx, lonIdx))

    /** True if a chunk file is present (its content is validated by [get]). */
    fun has(id: ChunkId): Boolean = synchronized(lock) { id in sizes }

    fun get(latIdx: Int, lonIdx: Int): DemChunk? = get(ChunkId(latIdx, lonIdx))

    /** The chunk, or null if absent or invalid (an invalid file is deleted). A hit counts as a use for the LRU. */
    fun get(id: ChunkId): DemChunk? {
        if (!has(id)) return null
        val file = fileOf(id)
        val chunk = try {
            DemChunk.decode(file.readBytes())?.takeIf { it.id == id }
        } catch (_: IOException) {
            null
        }
        if (chunk == null) {
            drop(id)
            return null
        }
        synchronized(lock) {
            if (id in sizes) {
                lastAccess[id] = tick()
                file.setLastModified(lastAccess.getValue(id))
            }
        }
        return chunk
    }

    /** Stores [chunk] (replacing any older copy), then evicts down to the budget. */
    fun put(chunk: DemChunk) {
        val bytes = chunk.encode()
        val file = fileOf(chunk.id)
        val tmp = File(dir, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        synchronized(lock) {
            val now = tick()
            file.setLastModified(now)
            sizes[chunk.id] = bytes.size.toLong()
            lastAccess[chunk.id] = now
            _bytesUsed.value = sizes.values.sum()
            evictLocked()
        }
    }

    /** Evicts down to the current budget (call after the budget was lowered). */
    fun trim() = synchronized(lock) { evictLocked() }

    /** Protects [chunks] from eviction under [key] (replacing the set pinned under that key); persisted. */
    fun pin(key: String, chunks: Set<ChunkId>) = synchronized(lock) {
        pins[key] = chunks.toSet()
        savePins()
    }

    /** Removes the pin [key]; its chunks become evictable again unless another pin or a use holds them. */
    fun unpin(key: String) = synchronized(lock) {
        if (pins.remove(key) != null) savePins()
    }

    /** True if some pin holds [id]. */
    fun isPinned(id: ChunkId): Boolean = synchronized(lock) { pins.values.any { id in it } }

    /** Protects [ids] from eviction until a matching [release]. Counted: nested acquires need as many releases. */
    fun acquire(ids: Collection<ChunkId>) = synchronized(lock) {
        for (id in ids) inUse[id] = (inUse[id] ?: 0) + 1
    }

    fun release(ids: Collection<ChunkId>) {
        synchronized(lock) {
            for (id in ids) {
                val n = (inUse[id] ?: 0) - 1
                if (n > 0) inUse[id] = n else inUse.remove(id)
            }
            evictLocked()
        }
    }

    /** Runs [block] with [ids] protected from eviction. */
    inline fun <T> use(ids: Collection<ChunkId>, block: () -> T): T {
        acquire(ids)
        try {
            return block()
        } finally {
            release(ids)
        }
    }

    /** Reads [id] and runs [block] on it (null when absent) with the chunk protected from eviction. */
    inline fun <T> use(id: ChunkId, block: (DemChunk?) -> T): T = use(listOf(id)) { block(get(id)) }

    /** Deletes every chunk that is neither pinned nor in use. */
    fun clear() = synchronized(lock) {
        for (id in sizes.keys.toList()) if (!protectedLocked(id)) deleteLocked(id)
        _bytesUsed.value = sizes.values.sum()
    }

    private fun evictLocked() {
        val budget = budgetBytes()
        var used = sizes.values.sum()
        if (used > budget) {
            val order = sizes.keys.filter { !protectedLocked(it) }
                .sortedWith(compareBy({ lastAccess[it] ?: 0L }, { it.latIdx }, { it.lonIdx }))
            for (id in order) {
                if (used <= budget) break
                used -= sizes.getValue(id)
                deleteLocked(id)
            }
        }
        _bytesUsed.value = sizes.values.sum()
    }

    private fun protectedLocked(id: ChunkId) = id in inUse || pins.values.any { id in it }

    private fun deleteLocked(id: ChunkId) {
        fileOf(id).delete()
        sizes.remove(id)
        lastAccess.remove(id)
    }

    private fun drop(id: ChunkId) = synchronized(lock) {
        deleteLocked(id)
        _bytesUsed.value = sizes.values.sum()
    }

    /** Strictly increasing wall-clock milliseconds, so two uses in one millisecond keep their order. */
    private fun tick(): Long {
        lastTick = maxOf(clock(), lastTick + 1)
        return lastTick
    }

    private fun fileOf(id: ChunkId) = File(dir, "${id.key}.dem")

    private fun parseName(name: String): ChunkId? {
        val m = NAME.matchEntire(name) ?: return null
        return ChunkId(m.groupValues[1].toIntOrNull() ?: return null, m.groupValues[2].toIntOrNull() ?: return null)
    }

    private val pinsFile get() = File(dir, PINS_FILE)

    private fun loadPins() {
        val f = pinsFile
        if (!f.isFile) return
        try {
            val root = JSONObject(f.readText())
            for (key in root.keys()) {
                val arr = root.getJSONArray(key)
                pins[key] = (0 until arr.length()).map { i ->
                    arr.getJSONArray(i).let { ChunkId(it.getInt(0), it.getInt(1)) }
                }.toSet()
            }
        } catch (_: Exception) {
            // An unreadable pins file is dropped: the packs that wrote it re-pin when they next run.
            pins.clear()
        }
    }

    private fun savePins() {
        val root = JSONObject()
        for ((key, set) in pins) {
            root.put(key, JSONArray().also { arr -> set.sortedWith(compareBy({ it.latIdx }, { it.lonIdx })).forEach { arr.put(JSONArray(listOf(it.latIdx, it.lonIdx))) } })
        }
        val tmp = File(dir, "$PINS_FILE.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(root.toString().toByteArray())
            out.flush()
            out.fd.sync()
        }
        Files.move(tmp.toPath(), pinsFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        const val PINS_FILE = "pins.json"
        val NAME = Regex("""(\d+)_(\d+)\.dem""")
    }
}
