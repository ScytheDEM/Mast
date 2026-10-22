package au.signalatlas.core.update

import au.signalatlas.core.data.DbInstaller
import au.signalatlas.core.data.TowerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single owner of the open [TowerStore] for the installed `towers.db`. [store] is null until
 * [start] has run, and stays null while no DB is installed.
 */
class TowerRepository(
    private val installer: DbInstaller,
    private val opener: (File) -> TowerStore = { TowerStore.open(it) },
) {
    private val _store = MutableStateFlow<TowerStore?>(null)
    val store: StateFlow<TowerStore?> = _store.asStateFlow()

    private val mutex = Mutex()
    private var started = false

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
     * Opens the current live DB and publishes it, then closes the store it replaces. If opening
     * fails, the old store stays published and the exception propagates.
     */
    suspend fun reload() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val fresh = if (installer.live.isFile) opener(installer.live) else null
                val old = _store.value
                _store.value = fresh
                old?.close()
            }
            started = true
        }
    }
}
