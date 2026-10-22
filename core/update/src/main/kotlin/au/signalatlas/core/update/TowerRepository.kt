package au.signalatlas.core.update

import au.signalatlas.core.data.DbInstaller
import au.signalatlas.core.data.TowerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Single owner of the open [TowerStore] for the installed `towers.db`. [store] is null until
 * [start] has run, and stays null while no DB is installed.
 *
 * A store replaced by [reload] stays open for [closeGrace] (closed from [scope]), so readers that
 * fetched it just before the swap finish normally; after that its `siteDetail` returns null.
 */
class TowerRepository(
    private val installer: DbInstaller,
    private val scope: CoroutineScope,
    private val closeGrace: Duration = 10.seconds,
    private val opener: (File) -> TowerStore = { TowerStore.open(it) },
) {
    private val _store = MutableStateFlow<TowerStore?>(null)
    val store: StateFlow<TowerStore?> = _store.asStateFlow()

    private val mutex = Mutex()
    private var started = false
    /** Replaced stores still inside their grace period. */
    private val retiring = mutableSetOf<TowerStore>()

    /** Recovers a crash-interrupted install, then opens the live DB if there is one. Idempotent. */
    suspend fun start() {
        mutex.withLock {
            if (started) return
            withContext(Dispatchers.IO) {
                installer.recover()
                if (installer.live.isFile) _store.value = opener(installer.live)
            }
            started = true
        }
    }

    /**
     * Opens the current live DB and publishes it; the store it replaces is closed after
     * [closeGrace]. If opening fails, the old store stays published and the exception propagates.
     */
    suspend fun reload() {
        mutex.withLock {
            val fresh = withContext(Dispatchers.IO) { if (installer.live.isFile) opener(installer.live) else null }
            val old = _store.value
            _store.value = fresh
            started = true
            if (old != null) retire(old)
        }
    }

    /** Closes the current store and any retiring ones immediately. */
    fun close() {
        val toClose = synchronized(retiring) { retiring.toList().also { retiring.clear() } } + listOfNotNull(_store.value)
        _store.value = null
        toClose.forEach { it.close() }
    }

    private fun retire(old: TowerStore) {
        synchronized(retiring) { retiring += old }
        scope.launch {
            delay(closeGrace)
            val due = synchronized(retiring) { retiring.remove(old) }
            if (due) old.close()
        }
    }
}
