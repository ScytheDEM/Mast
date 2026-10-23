package au.signalatlas.core.update

import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide lock shared by every update worker (tower download/import and antenna patterns), so
 * a 1.56 GB pattern job and a tower job never compete for the same disk at once. Waiting workers
 * publish [UpdateWork.PHASE_QUEUED].
 */
internal object UpdateLocks {
    val running = Mutex()
}
