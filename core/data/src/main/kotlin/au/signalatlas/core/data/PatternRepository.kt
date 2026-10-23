package au.signalatlas.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single owner of the loaded [PatternStore] for the installed `patterns.db`. [patterns] is null
 * until [start] has run and whenever no usable patterns are installed; the engine then uses the
 * standard parametric patterns.
 *
 * Lifetime is a plain swap: a [PatternStore] holds no open DB (everything is in memory), so a
 * replaced store is closed at once and readers that still hold it keep working. No reference
 * counting as in [TowerRepository] is needed.
 */
class PatternRepository(
    private val installer: PatternInstaller,
    private val opener: (File) -> PatternStore = { PatternStore.open(it) },
    /** Told why an installed `patterns.db` could not be loaded (it is then ignored). */
    private val onLoadError: (Throwable) -> Unit = {},
) {
    private val _patterns = MutableStateFlow<PatternStore?>(null)
    val patterns: StateFlow<PatternStore?> = _patterns.asStateFlow()

    private val mutex = Mutex()
    private var started = false

    /** Recovers a crash-interrupted install, then loads the live `patterns.db` if there is one. Idempotent. */
    suspend fun start() {
        mutex.withLock {
            if (started) return
            publish(withContext(Dispatchers.IO) { installer.recover(); loadOrNull() })
            started = true
        }
    }

    /** Loads the current live `patterns.db` and publishes it (null if none or unreadable). */
    suspend fun reload() {
        mutex.withLock {
            publish(withContext(Dispatchers.IO) { loadOrNull() })
            started = true
        }
    }

    fun close() {
        publish(null)
    }

    private fun loadOrNull(): PatternStore? {
        val live = installer.live
        if (!live.isFile) return null
        return try {
            opener(live)
        } catch (e: Exception) {
            onLoadError(e)
            null
        }
    }

    private fun publish(fresh: PatternStore?) {
        val old = synchronized(this) { _patterns.value.also { _patterns.value = fresh } }
        if (old != null && old !== fresh) old.close()
    }
}
