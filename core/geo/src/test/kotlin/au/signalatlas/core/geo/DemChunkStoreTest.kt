package au.signalatlas.core.geo

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Random

@RunWith(RobolectricTestRunner::class)
class DemChunkStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dir: File
    private var now = 1_700_000_000_000L
    private var budget = Long.MAX_VALUE

    private val a = ChunkId(10, 10)
    private val b = ChunkId(10, 11)
    private val c = ChunkId(10, 12)
    private val d = ChunkId(10, 13)

    @Before fun setUp() {
        dir = File(tmp.root, "dem90")
    }

    private fun newStore() = DemChunkStore(dir, { budget }, { now += 10_000; now })

    /** Incompressible samples, so every chunk has about the same file size. */
    private fun chunk(id: ChunkId): DemChunk {
        val rnd = Random(id.hashCode().toLong())
        return DemChunk(id, ShortArray(301 * 301) { rnd.nextInt(20000).toShort() })
    }

    private fun size(id: ChunkId) = File(dir, "${id.key}.dem").length()

    @Test fun `put then get returns the chunk and counts the bytes`() {
        val store = newStore()
        assertThat(store.get(a)).isNull()
        assertThat(store.has(a)).isFalse()
        store.put(chunk(a))
        assertThat(store.has(10, 10)).isTrue()
        assertThat(store.get(10, 10)).isEqualTo(chunk(a))
        assertThat(store.bytesUsed.value).isEqualTo(size(a))
        assertThat(File(dir, "10_10.dem").exists()).isTrue()
        assertThat(dir.listFiles()!!.filter { it.name.endsWith(".tmp") }).isEmpty()
    }

    @Test fun `evicts the least recently used first`() {
        val store = newStore()
        for (id in listOf(a, b, c)) store.put(chunk(id))
        store.get(a) // a is now newer than b and c
        budget = size(a) * 3 - 1
        store.put(chunk(d))
        // over by one chunk-and-a-bit: the two oldest (b, c) go, in that order, until it fits.
        assertThat(store.has(b)).isFalse()
        assertThat(store.has(a)).isTrue()
        assertThat(store.has(d)).isTrue()
        assertThat(store.bytesUsed.value).isAtMost(budget)
        assertThat(File(dir, "10_11.dem").exists()).isFalse()
    }

    @Test fun `lowering the budget and trimming evicts oldest first`() {
        val store = newStore()
        for (id in listOf(a, b, c)) store.put(chunk(id))
        budget = size(a) * 2 + 10
        store.trim()
        assertThat(store.has(a)).isFalse()
        assertThat(store.has(b)).isTrue()
        assertThat(store.has(c)).isTrue()
        assertThat(store.bytesUsed.value).isEqualTo(size(b) + size(c))
    }

    @Test fun `recency survives a restart through the file times`() {
        val first = newStore()
        for (id in listOf(a, b, c)) first.put(chunk(id))
        first.get(a)
        val second = newStore()
        assertThat(second.bytesUsed.value).isEqualTo(size(a) + size(b) + size(c))
        budget = size(a) * 2 + 10
        second.trim()
        assertThat(second.has(b)).isFalse()
        assertThat(second.has(a)).isTrue()
        assertThat(second.has(c)).isTrue()
    }

    @Test fun `pinned chunks are never evicted and pins persist`() {
        val store = newStore()
        for (id in listOf(a, b, c)) store.put(chunk(id))
        store.pin("pack-1", setOf(a))
        store.pin("pack-2", setOf(a, b))
        budget = size(a) // room for one
        store.trim()
        assertThat(store.has(a)).isTrue()
        assertThat(store.has(b)).isTrue()
        assertThat(store.has(c)).isFalse()
        assertThat(store.bytesUsed.value).isEqualTo(size(a) + size(b)) // over budget: nothing evictable

        val reopened = newStore()
        assertThat(reopened.isPinned(a)).isTrue()
        assertThat(reopened.isPinned(b)).isTrue()
        reopened.unpin("pack-2") // a is still held by pack-1
        reopened.trim()
        assertThat(reopened.has(a)).isTrue()
        assertThat(reopened.has(b)).isFalse()
        reopened.unpin("pack-1")
        budget = 0
        reopened.trim()
        assertThat(reopened.has(a)).isFalse()
        assertThat(File(dir, "pins.json").readText()).isEqualTo("{}")
    }

    @Test fun `a new put does not evict pinned chunks to make room`() {
        val store = newStore()
        store.put(chunk(a))
        store.pin("pack", setOf(a))
        budget = size(a) + 10
        store.put(chunk(b))
        assertThat(store.has(a)).isTrue()
        assertThat(store.has(b)).isFalse() // the only evictable chunk was the new one
    }

    @Test fun `chunks in use are never evicted until released`() {
        val store = newStore()
        for (id in listOf(a, b, c)) store.put(chunk(id))
        budget = size(a)
        store.acquire(setOf(a))
        store.acquire(setOf(a, b))
        store.trim()
        assertThat(store.has(a)).isTrue()
        assertThat(store.has(b)).isTrue()
        assertThat(store.has(c)).isFalse()
        store.release(setOf(a, b)) // a is still held once; b is free and evicted (budget fits one chunk, a)
        assertThat(store.has(a)).isTrue()
        assertThat(store.has(b)).isFalse()
        store.release(setOf(a))
        assertThat(store.has(a)).isTrue() // within budget now
        budget = 0
        store.trim()
        assertThat(store.has(a)).isFalse()
    }

    @Test fun `use protects the chunk while the block runs`() {
        val store = newStore()
        store.put(chunk(a))
        store.put(chunk(b))
        budget = size(a)
        val seen = store.use(a) { got ->
            store.trim()
            assertThat(store.has(a)).isTrue()
            got
        }
        assertThat(seen).isEqualTo(chunk(a))
        store.use(setOf(a, b)) { assertThat(store.has(a)).isTrue() }
        // after the block both are evictable again
        store.trim()
        assertThat(store.bytesUsed.value).isAtMost(budget)
    }

    @Test fun `a failed CRC is a miss and the file is deleted`() {
        val store = newStore()
        store.put(chunk(a))
        store.put(chunk(b))
        val file = File(dir, "10_10.dem")
        val bytes = file.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        file.writeBytes(bytes)
        assertThat(store.get(a)).isNull()
        assertThat(store.has(a)).isFalse()
        assertThat(file.exists()).isFalse()
        assertThat(store.bytesUsed.value).isEqualTo(size(b))
        assertThat(store.get(b)).isEqualTo(chunk(b))
    }

    @Test fun `a chunk file holding another id is a miss`() {
        val store = newStore()
        store.put(chunk(a))
        File(dir, "10_10.dem").copyTo(File(dir, "10_11.dem"))
        assertThat(newStore().get(b)).isNull()
    }

    @Test fun `leftover temp files are removed when the store opens`() {
        dir.mkdirs()
        File(dir, "10_10.dem.tmp").writeBytes(ByteArray(100))
        val store = newStore()
        assertThat(dir.listFiles()!!.map { it.name }).doesNotContain("10_10.dem.tmp")
        assertThat(store.bytesUsed.value).isEqualTo(0L)
    }

    @Test fun `clear removes everything but pinned and in-use chunks`() {
        val store = newStore()
        for (id in listOf(a, b, c)) store.put(chunk(id))
        store.pin("pack", setOf(a))
        store.acquire(setOf(b))
        store.clear()
        assertThat(store.has(a)).isTrue()
        assertThat(store.has(b)).isTrue()
        assertThat(store.has(c)).isFalse()
        assertThat(store.bytesUsed.value).isEqualTo(size(a) + size(b))
    }

    @Test fun `putting a chunk again replaces it`() {
        val store = newStore()
        store.put(chunk(a))
        val replacement = DemChunk(a, ShortArray(301 * 301) { 5 })
        store.put(replacement)
        assertThat(store.get(a)).isEqualTo(replacement)
        assertThat(store.bytesUsed.value).isEqualTo(size(a))
    }

    @Test fun `concurrent puts gets and evictions, and two writers of one chunk, stay consistent`() {
        budget = 3 * size0() // room for about three; constant eviction
        val store = DemChunkStore(dir, { budget })
        val ids = (0 until 6).map { ChunkId(5, it) }
        val shared = ChunkId(9, 9)
        val variants = listOf(DemChunk(shared, ShortArray(301 * 301) { 1 }), DemChunk(shared, ShortArray(301 * 301) { (it % 7).toShort() }))
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val deadline = System.nanoTime() + 2_000_000_000L
        val tasks = (0 until 8).map { t ->
            pool.submit {
                try {
                    var n = 0
                    while (System.nanoTime() < deadline) {
                        val id = ids[(n + t) % ids.size]
                        when ((n + t) % 4) {
                            0 -> store.put(chunk(id))
                            1 -> store.get(id)?.let { check(it == chunk(id)) { "wrong content for $id" } }
                            2 -> store.put(variants[t % 2])
                            else -> store.get(shared)?.let { check(it == variants[0] || it == variants[1]) { "torn shared chunk" } }
                        }
                        n++
                    }
                } catch (e: Throwable) {
                    errors += e
                }
            }
        }
        tasks.forEach { it.get() }
        pool.shutdown()
        assertThat(errors.toList()).isEmpty()
        store.put(variants[0])
        store.put(variants[1])
        assertThat(store.get(shared)).isEqualTo(variants[1])
        assertThat(dir.listFiles()!!.filter { it.name.endsWith(".tmp") }).isEmpty()
        // The index agrees with the disk.
        val onDisk = dir.listFiles()!!.filter { it.name.endsWith(".dem") }.sumOf { it.length() }
        assertThat(store.bytesUsed.value).isEqualTo(onDisk)
        assertThat(newStore().bytesUsed.value).isEqualTo(onDisk)
    }

    private fun size0() = chunk(a).encode().size.toLong()
}
