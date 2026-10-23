package au.signalatlas.core.coverage

import android.content.Context
import android.os.Build
import android.os.PowerManager
import au.signalatlas.engine.CancelToken
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

typealias TileAddress = Triple<Int, Int, Int>

/**
 * Runs tile renders on a dedicated, bounded set of daemon threads, never more than `threads()` at once.
 *
 * Which queued job runs next is decided when a slot frees: tiles in the visible set first, closest to the viewport
 * centre first, then everything else in submission order (until the first [setVisible], every job counts as
 * visible). [setVisible] cancels the [CancelToken] of running jobs outside the set and drops queued ones
 * (calling their `onCancelled`).
 *
 * The thread budget is re-read at most every 5 s ([REEVALUATE_NANOS]), at job boundaries, so a thermal change takes
 * effect while rendering.
 */
class RenderPool internal constructor(
    private val threads: () -> Int,
    private val nanoTime: () -> Long,
) {
    constructor(threads: () -> Int) : this(threads, System::nanoTime)

    private class Job(
        val tile: TileAddress,
        val seq: Long,
        val work: (CancelToken) -> Unit,
        val onCancelled: () -> Unit,
    ) {
        val cancel = CancelToken()
    }

    private val lock = Any()
    private val pending = ArrayList<Job>()
    private val running = HashSet<Job>()
    private var visible: Set<TileAddress>? = null
    private var centreX = 0.0
    private var centreY = 0.0
    private var seq = 0L
    private var limit = 1
    private var limitAt = Long.MIN_VALUE
    private var shutDown = false

    private val executor = ThreadPoolExecutor(
        MAX_THREADS, MAX_THREADS, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(),
        { r -> Thread(r, "coverage-render").apply { isDaemon = true } },
    ).apply { allowCoreThreadTimeOut(true) }

    /**
     * Queues [work] for [tile]. [work] runs on a pool thread with the job's token and must complete whatever
     * waits on it, also when the token fires. [onCancelled] runs instead if the job is dropped before it starts.
     */
    fun submit(tile: TileAddress, work: (CancelToken) -> Unit, onCancelled: () -> Unit) {
        val job = synchronized(lock) {
            if (shutDown) null else Job(tile, seq++, work, onCancelled).also { pending += it }
        }
        if (job == null) onCancelled() else pump()
    }

    /** Sets the tiles the viewport needs; everything running or queued that is not among them is cancelled. */
    fun setVisible(tiles: Set<TileAddress>) {
        val dropped = ArrayList<Job>()
        synchronized(lock) {
            visible = tiles.toSet()
            if (tiles.isNotEmpty()) {
                centreX = tiles.sumOf { (z, x, _) -> (x + 0.5) / (1L shl z) } / tiles.size
                centreY = tiles.sumOf { (z, _, y) -> (y + 0.5) / (1L shl z) } / tiles.size
            }
            val it = pending.iterator()
            while (it.hasNext()) {
                val j = it.next()
                if (j.tile !in tiles) { it.remove(); dropped += j }
            }
            running.forEach { if (it.tile !in tiles) it.cancel.cancel() }
        }
        dropped.forEach { it.cancel.cancel(); it.onCancelled() }
        pump()
    }

    /** Cancels everything and stops accepting work. */
    fun shutdown() {
        val dropped = synchronized(lock) {
            shutDown = true
            running.forEach { it.cancel.cancel() }
            pending.toList().also { pending.clear() }
        }
        dropped.forEach { it.onCancelled() }
        executor.shutdown()
    }

    private fun pump() {
        while (true) {
            val job = synchronized(lock) {
                if (running.size >= currentLimit()) return
                val best = pending.minWithOrNull(::compareJobs) ?: return
                pending.remove(best)
                running += best
                best
            }
            try {
                executor.execute {
                    try {
                        if (!job.cancel.isCancelled) job.work(job.cancel) else job.onCancelled()
                    } catch (t: Throwable) {
                        // work() owns its failures; this only keeps the thread alive.
                    } finally {
                        synchronized(lock) { running -= job }
                        pump()
                    }
                }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                synchronized(lock) { running -= job }
                job.onCancelled()
            }
        }
    }

    /** Caller holds [lock]. */
    private fun currentLimit(): Int {
        val now = nanoTime()
        if (limitAt == Long.MIN_VALUE || now - limitAt >= REEVALUATE_NANOS) {
            limit = threads().coerceIn(1, MAX_THREADS)
            limitAt = now
        }
        return limit
    }

    /** Caller holds [lock]. */
    private fun compareJobs(a: Job, b: Job): Int {
        val va = isVisible(a)
        val vb = isVisible(b)
        if (va != vb) return if (va) -1 else 1
        if (va && visible != null) {
            val c = distance(a).compareTo(distance(b))
            if (c != 0) return c
        }
        return a.seq.compareTo(b.seq)
    }

    private fun isVisible(j: Job) = visible?.contains(j.tile) ?: true

    private fun distance(j: Job): Double {
        val (z, x, y) = j.tile
        val dx = (x + 0.5) / (1L shl z) - centreX
        val dy = (y + 0.5) / (1L shl z) - centreY
        return dx * dx + dy * dy
    }

    companion object {
        const val MAX_THREADS = 8
        const val REEVALUATE_NANOS = 5_000_000_000L
        private const val THROTTLE_HEADROOM = 0.85f

        /** The thermal rule: 1 thread when [throttled], else `min(4, cpus − 2)` and at least 1. */
        internal fun renderThreads(cpus: Int, throttled: Boolean): Int =
            if (throttled) 1 else (cpus - 2).coerceAtMost(4).coerceAtLeast(1)

        /**
         * Thermal pressure: `getThermalHeadroom(10)` >= 0.85 on API 30+ (NaN = unsupported = not throttled); on API 29,
         * which has no headroom, a thermal status of SEVERE or worse (ruling Z).
         */
        internal fun isThrottled(sdk: Int, headroom: () -> Float, status: () -> Int): Boolean =
            if (sdk >= Build.VERSION_CODES.R) headroom() >= THROTTLE_HEADROOM
            else status() >= PowerManager.THERMAL_STATUS_SEVERE

        /** A pool following the device's thermal state. */
        fun forDevice(context: Context): RenderPool {
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val cpus = Runtime.getRuntime().availableProcessors()
            return RenderPool({
                renderThreads(cpus, isThrottled(
                    Build.VERSION.SDK_INT,
                    { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) pm.getThermalHeadroom(10) else 0f },
                    { pm.currentThermalStatus },
                ))
            })
        }
    }
}
