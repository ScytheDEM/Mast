package au.signalatlas.app.packs

import au.signalatlas.core.geo.packs.PackContentSource
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Runs a pack's download: every [PackContentSource] in turn, progress weighted by each source's remaining bytes.
 *
 * Resumable by construction: the sources skip what they already hold, so running a pack again after a failure, a
 * cancel or a process death carries on where it stopped. The pack's state in [repo] is the only thing kept here.
 */
class PackDownloader(
    private val repo: PackRepository,
    private val sources: List<PackContentSource>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Downloads pack [packId]. Returns normally when it is [PackState.READY] (or was deleted meanwhile). On an
     * `IOException` the pack goes back to [PackState.QUEUED] with the reason and the exception propagates, so the
     * caller can retry; cancellation also leaves it queued.
     */
    suspend fun run(packId: String, allowMetered: Boolean, onProgress: (Float) -> Unit = {}) {
        val pack = repo.get(packId) ?: return
        repo.update(packId) { it.copy(state = PackState.DOWNLOADING, allowMetered = allowMetered, error = null) }
        try {
            val weights = sources.map { s -> runCatchingIo { s.estimateBytes(pack.area) }.coerceAtLeast(1L) }
            val total = weights.sum().toFloat()
            var before = 0f
            for ((i, source) in sources.withIndex()) {
                val share = weights[i] / total
                val base = before
                source.download(packId, pack.generation, pack.area, allowMetered) { f ->
                    val overall = (base + share * f.coerceIn(0f, 1f)).coerceIn(0f, 1f)
                    repo.update(packId) { it.copy(progress = overall) }
                    onProgress(overall)
                }
                before += share
            }
            val bytes = sources.sumOf { it.sizeBytes(packId, pack.area) }
            repo.update(packId) {
                it.copy(state = PackState.READY, progress = 1f, bytes = bytes, error = null, updatedAt = clock())
            }
            onProgress(1f)
        } catch (e: CancellationException) {
            repo.update(packId) { it.copy(state = PackState.QUEUED) }
            throw e
        } catch (e: IOException) {
            repo.update(packId) { it.copy(state = PackState.QUEUED, error = e.message ?: e.javaClass.simpleName) }
            throw e
        }
    }

    /** Marks the pack failed for good (the worker gave up), with a reason for the list. */
    fun fail(packId: String, reason: String) {
        repo.update(packId) { it.copy(state = PackState.FAILED, error = reason) }
    }

    private suspend fun runCatchingIo(block: suspend () -> Long): Long = try {
        block()
    } catch (_: IOException) {
        0L
    }
}
