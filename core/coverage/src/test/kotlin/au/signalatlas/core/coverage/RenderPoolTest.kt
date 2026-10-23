package au.signalatlas.core.coverage

import android.os.Build
import android.os.PowerManager
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class RenderPoolTest {
    private var pool: RenderPool? = null

    @After fun tearDown() { pool?.shutdown() }

    private fun pool(threads: () -> Int, nanoTime: () -> Long = System::nanoTime) =
        RenderPool(threads, nanoTime).also { pool = it }

    private fun addr(x: Int, z: Int = 10) = TileAddress(z, x, 500)

    @Test fun neverRunsMoreThanTheThreadBudget() {
        val p = pool({ 2 })
        val now = AtomicInteger()
        val max = AtomicInteger()
        val done = AtomicInteger()
        repeat(10) { i ->
            p.submit(addr(i), {
                max.accumulateAndGet(now.incrementAndGet(), ::maxOf)
                Thread.sleep(20)
                now.decrementAndGet()
                done.incrementAndGet()
            }, {})
        }
        waitUntil { done.get() == 10 }
        assertThat(max.get()).isEqualTo(2)
    }

    @Test fun visibleTilesRunClosestToCentreFirstThenOthers() {
        val p = pool({ 1 })
        val order = CopyOnWriteArrayList<Int>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        p.submit(addr(0), { started.countDown(); release.await() }, {})
        started.await()
        // Queued in an order unrelated to the expected one.
        for (x in intArrayOf(510, 500, 501, 900)) p.submit(addr(x), { order += x }, {})
        // Visible centre = mean x of 500, 501, 510 -> 504.2: 501 is nearest, then 500, then 510. 900 is not visible.
        p.setVisible(setOf(addr(500), addr(501), addr(510)))
        // A tile submitted after setVisible and outside it still runs, after every visible one.
        p.submit(addr(901), { order += 901 }, {})
        release.countDown()
        waitUntil { order.size == 4 }
        assertThat(order.toList()).containsExactly(501, 500, 510, 901).inOrder()
    }

    @Test fun lowerPriorityNumbersRunFirstAmongVisibleJobsEvenIfFartherAway() {
        val p = pool({ 1 })
        val order = CopyOnWriteArrayList<String>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        p.submit(addr(0), { started.countDown(); release.await() }, {})
        started.await()
        // A table job on the tile at the viewport centre, then renders on tiles farther out: renders still go first.
        p.submit(addr(500), RenderPool.PRIORITY_TABLE, { order += "table500" }, {})
        p.submit(addr(503), { order += "render503" }, {})
        p.submit(addr(501), { order += "render501" }, {})
        p.setVisible(setOf(addr(500), addr(501), addr(503)))
        release.countDown()
        waitUntil { order.size == 3 }
        assertThat(order.toList()).containsExactly("render501", "render503", "table500").inOrder()
    }

    @Test fun setVisibleCancelsQueuedAndRunningJobsOutsideTheSet() {
        val p = pool({ 1 })
        val started = CountDownLatch(1)
        var runningToken: au.signalatlas.engine.CancelToken? = null
        val dropped = CopyOnWriteArrayList<Int>()
        p.submit(addr(1), { token -> runningToken = token; started.countDown(); while (!token.isCancelled) Thread.sleep(2) }, {})
        started.await()
        p.submit(addr(2), { error("must not run") }, { dropped += 2 })
        p.submit(addr(3), { }, { dropped += 3 })
        p.setVisible(setOf(addr(3)))
        assertThat(runningToken!!.isCancelled).isTrue()
        assertThat(dropped.toList()).containsExactly(2)
        waitUntil { dropped.size == 1 } // 3 is visible and runs once 1 returns
    }

    @Test fun threadBudgetIsReEvaluatedEveryFiveSeconds() {
        var budget = 1
        var clock = 0L
        val p = pool({ budget }, { clock })
        val running = AtomicInteger()
        val gates = List(4) { CountDownLatch(1) }
        for (i in 0 until 4) p.submit(addr(i), { running.incrementAndGet(); gates[i].await(); running.decrementAndGet() }, {})
        waitUntil { running.get() == 1 }

        budget = 3
        clock += 4_000_000_000L // still inside the window: the old budget holds
        gates[0].countDown()
        Thread.sleep(50)
        assertThat(running.get()).isEqualTo(1)

        clock += 2_000_000_000L // now past 5 s
        gates[1].countDown()
        waitUntil { running.get() == 2 } // jobs 2 and 3 both started
        gates[2].countDown(); gates[3].countDown()
    }

    @Test fun thermalRule() {
        assertThat(RenderPool.renderThreads(cpus = 8, throttled = false)).isEqualTo(4)
        assertThat(RenderPool.renderThreads(cpus = 4, throttled = false)).isEqualTo(2)
        assertThat(RenderPool.renderThreads(cpus = 2, throttled = false)).isEqualTo(1)
        assertThat(RenderPool.renderThreads(cpus = 1, throttled = false)).isEqualTo(1)
        assertThat(RenderPool.renderThreads(cpus = 12, throttled = true)).isEqualTo(1)

        val r = Build.VERSION_CODES.R
        assertThat(RenderPool.isThrottled(r, { 0.85f }, { error("unused") })).isTrue()
        assertThat(RenderPool.isThrottled(r, { 0.84f }, { error("unused") })).isFalse()
        assertThat(RenderPool.isThrottled(r, { Float.NaN }, { error("unused") })).isFalse()
        val q = Build.VERSION_CODES.Q
        assertThat(RenderPool.isThrottled(q, { error("unused") }, { PowerManager.THERMAL_STATUS_SEVERE })).isTrue()
        assertThat(RenderPool.isThrottled(q, { error("unused") }, { PowerManager.THERMAL_STATUS_MODERATE })).isFalse()
    }
}
