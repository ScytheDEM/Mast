package au.signalatlas.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.IdentityHashMap

/**
 * Single owner of the open [TowerStore] for the installed `towers.db`. [store] is null until
 * [start] has run, and stays null while no (usable) DB is installed.
 *
 * Store lifetime is reference counted: [withStore] pins the current store for the duration of its
 * block. A store replaced by [reload] (or dropped by [close]) is closed as soon as nobody has it
 * pinned, which is immediately when nobody does, otherwise when the last user returns. Readers that
 * just peek at [store] without pinning see `siteDetail` return null once their store is closed.
 *
 * [scope] is the owning component's scope; it is not used to schedule closes (they are
 * deterministic), only kept so the repository's constructor matches its owner's lifetime.
 */
class TowerRepository(
    private val installer: DbInstaller,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
    private val opener: (File) -> TowerStore = { TowerStore.open(it) },
) {
    private val _store = MutableStateFlow<TowerStore?>(null)
    val store: StateFlow<TowerStore?> = _store.asStateFlow()

    private val mutex = Mutex()
    private var started = false
    /** Which file the published store was opened from (null = none), see [identity]. */
    private var openedFrom: String? = null

    /** Guards [pins], [retired] and every swap of [_store], so pinning and retiring cannot interleave. */
    private val lifetime = Any()
    private val pins = IdentityHashMap<TowerStore, Int>()
    /** Replaced stores that still have users; each closes when its count reaches zero. */
    private val retired = mutableSetOf<TowerStore>()

    /** Recovers a crash-interrupted install, then opens the live DB if there is one. Idempotent. */
    suspend fun start() {
        mutex.withLock {
            if (started) return
            withContext(Dispatchers.IO) {
                installer.recover()
                val id = identity(installer.live)
                if (id != null) {
                    try {
                        publish(opener(installer.live))
                    } catch (e: SchemaMismatchException) {
                        // Not a DB this build can read: behave as if none is installed (and remember
                        // the file so refresh() does not retry it until it changes).
                    }
                    openedFrom = id
                }
            }
            started = true
        }
    }

    /**
     * [start]s, then reloads if the installed `towers.db` is not the file the published store was
     * opened from (e.g. another worker installed it but was stopped before its reload).
     */
    suspend fun refresh() {
        start()
        val stale = mutex.withLock { withContext(Dispatchers.IO) { identity(installer.live) } != openedFrom }
        if (stale) reload()
    }

    /**
     * Opens the current live DB and publishes it; the store it replaces closes once its last
     * [withStore] user returns. If opening fails, the old store stays published and the exception
     * propagates.
     */
    suspend fun reload() {
        mutex.withLock {
            val (fresh, id) = withContext(Dispatchers.IO) {
                val id = identity(installer.live)
                (if (id != null) opener(installer.live) else null) to id
            }
            publish(fresh)
            openedFrom = id
            started = true
        }
    }

    /**
     * Pins the current store while [block] runs, so it cannot be closed underneath it even if
     * [reload] or [close] retire it meanwhile. Returns null (without running [block]) when no DB is
     * installed.
     */
    suspend fun <T> withStore(block: suspend (TowerStore) -> T): T? {
        val pinned = synchronized(lifetime) {
            _store.value?.also { pins.merge(it, 1, Int::plus) }
        } ?: return null
        try {
            return block(pinned)
        } finally {
            unpin(pinned)
        }
    }

    /** Unpublishes the current store and closes it, and any retired ones, as soon as they are unpinned. */
    fun close() {
        publish(null)
        val unpinned = synchronized(lifetime) {
            retired.filter { it !in pins }.also { retired.removeAll(it.toSet()) }
        }
        unpinned.forEach { it.close() }
    }

    /** Swaps in [fresh] and retires the store it replaces. */
    private fun publish(fresh: TowerStore?) {
        val closeNow = synchronized(lifetime) {
            val old = _store.value
            _store.value = fresh
            when {
                old == null || old === fresh -> null
                old in pins -> { retired += old; null }
                else -> old
            }
        }
        closeNow?.close()
    }

    private fun unpin(s: TowerStore) {
        val closeNow = synchronized(lifetime) {
            val left = pins.merge(s, -1, Int::plus)!!
            if (left == 0) pins.remove(s)
            left == 0 && retired.remove(s)
        }
        if (closeNow) s.close()
    }

    /** Inode (where the platform reports one), mtime and size; null when [f] is missing. */
    private fun identity(f: File): String? = try {
        val a = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
        if (a.isRegularFile) "${a.fileKey()}/${a.lastModifiedTime().toMillis()}/${a.size()}" else null
    } catch (e: java.io.IOException) {
        null
    }
}
