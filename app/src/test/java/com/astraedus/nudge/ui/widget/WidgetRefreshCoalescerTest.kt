package com.astraedus.nudge.ui.widget

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget refresh rate limit, on a virtual clock.
 *
 * The property that matters is asymmetric, and it is why this replaced a leading-edge debounce:
 * refreshing more often than necessary wastes a RemoteViews build, while refreshing *fewer* times
 * than necessary leaves the Protection widget - which is push-only - misrepresenting live state
 * with no later tick to correct it. So every case below is really asking one question: **can the
 * last request ever be lost?**
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetRefreshCoalescerTest {

    private val cooldown = 10_000L

    @Test
    fun `the first request refreshes immediately`() = runTest {
        var refreshes = 0
        val coalescer = WidgetRefreshCoalescer(cooldown) { refreshes++ }
        val loop = launch { coalescer.run() }

        coalescer.request()
        advanceTimeBy(1)

        assertEquals(1, refreshes)
        loop.cancel()
    }

    @Test
    fun `a burst inside one cooldown yields exactly two refreshes, not one and not N`() = runTest {
        var refreshes = 0
        val coalescer = WidgetRefreshCoalescer(cooldown) { refreshes++ }
        val loop = launch { coalescer.run() }

        // One leading refresh, then twenty more requests well inside the cooldown.
        coalescer.request()
        advanceTimeBy(1)
        assertEquals("the first request must not wait", 1, refreshes)

        repeat(20) {
            coalescer.request()
            advanceTimeBy(100)
        }
        assertEquals("everything inside the cooldown must coalesce", 1, refreshes)

        // The trailing one carries the final state. THIS is the case the old debounce dropped.
        advanceUntilIdle()
        assertEquals(2, refreshes)

        loop.cancel()
    }

    @Test
    fun `a request arriving during the cooldown is never lost`() = runTest {
        val seen = mutableListOf<Int>()
        var state = 0
        val coalescer = WidgetRefreshCoalescer(cooldown) { seen += state }
        val loop = launch { coalescer.run() }

        state = 1
        coalescer.request()
        advanceTimeBy(1)

        // The write that the leading-edge version threw away: a state change landing inside a
        // window that some unrelated event opened.
        state = 2
        coalescer.request()
        advanceUntilIdle()

        assertEquals("the final state must reach the widgets", listOf(1, 2), seen)
        loop.cancel()
    }

    @Test
    fun `requests spaced beyond the cooldown each refresh`() = runTest {
        var refreshes = 0
        val coalescer = WidgetRefreshCoalescer(cooldown) { refreshes++ }
        val loop = launch { coalescer.run() }

        repeat(3) {
            coalescer.request()
            advanceTimeBy(cooldown + 1)
        }
        advanceUntilIdle()

        assertEquals(3, refreshes)
        loop.cancel()
    }

    @Test
    fun `a request made before the loop starts is still served`() = runTest {
        var refreshes = 0
        val coalescer = WidgetRefreshCoalescer(cooldown) { refreshes++ }

        // A conflated CHANNEL buffers with no receiver attached; a MutableSharedFlow with
        // replay = 0 would drop this, and the sources emit their current value the instant they
        // are collected, so that race is real rather than theoretical.
        coalescer.request()
        val loop = launch { coalescer.run() }
        advanceUntilIdle()

        assertEquals(1, refreshes)
        loop.cancel()
    }

    @Test
    fun `a slow refresh does not drop the request that arrived during it`() = runTest {
        var refreshes = 0
        val coalescer = WidgetRefreshCoalescer(cooldown) {
            refreshes++
            delay(5_000)
        }
        val loop: Job = launch { coalescer.run() }

        coalescer.request()
        advanceTimeBy(1_000)
        coalescer.request() // mid-refresh
        advanceUntilIdle()

        assertEquals(2, refreshes)
        loop.cancel()
    }

    @Test
    fun `quiet periods cost nothing`() = runTest {
        var refreshes = 0
        val coalescer = WidgetRefreshCoalescer(cooldown) { refreshes++ }
        val loop = launch { coalescer.run() }

        advanceTimeBy(cooldown * 100)

        assertEquals("no request means no refresh", 0, refreshes)
        loop.cancel()
    }

    /**
     * A pass arriving while another is IN FLIGHT must be QUEUED, never swallowed.
     *
     * `pushAll` is serialised so two `updateAll` calls for one widget id cannot overlap - device
     * QA showed the later one losing the race and putting a stale frame back on screen. But
     * serialising is only safe if a pass that shows up mid-flight waits its turn. A gate that
     * *skipped* instead would re-create the exact bug the mutex was added to fix, one layer down.
     *
     * The protection path is what makes this sharp, and it is why the first version of this test
     * was worthless. Event refreshes go through the conflated channel, which re-delivers on its
     * own - so a swallowing gate still LOOKS fine through that path, and the test passed when the
     * gate was mutated to swallow. Protection refreshes call `pushAll` directly, with no channel
     * behind them to try again. If the gate drops one, nothing ever retries it, and the widget is
     * wrong until something unrelated happens to refresh it.
     *
     * So this asserts on the protection pass specifically.
     */
    @Test
    fun `a protection pass arriving mid-flight is queued, not swallowed`() = runTest {
        val lock = Mutex()
        var state = 0
        val reads = mutableListOf<Pair<String, Int>>()

        suspend fun pass(source: String) {
            lock.withLock {
                reads += source to state
                delay(2_000)
            }
        }

        val coalescer = WidgetRefreshCoalescer(cooldown) { pass("events") }
        val loop = launch { coalescer.run() }

        state = 1
        coalescer.request()
        advanceTimeBy(500) // an events pass is in flight, holding the lock, having read 1

        // The master toggle flips while that pass is still rendering. This is the real sequence
        // from the device: a block event opened the pass, the toggle landed inside it.
        state = 2
        val protectionPass = launch { pass("protection") }
        advanceUntilIdle()

        val protectionReads = reads.filter { it.first == "protection" }
        assertEquals(
            "the protection pass must run exactly once - swallowed means the widget keeps a " +
                "stale frame with nothing left to retry it: $reads",
            1,
            protectionReads.size
        )
        assertEquals(
            "and it must read the state as of when it RAN, not when it was queued: $reads",
            2,
            protectionReads.single().second
        )
        assertEquals("the in-flight pass read the old state", "events" to 1, reads.first())

        protectionPass.cancel()
        loop.cancel()
    }

    /** A mid-pass EVENT burst still collapses to one further pass rather than storming. */
    @Test
    fun `mid-pass event requests coalesce to a single further pass`() = runTest {
        val lock = Mutex()
        var passes = 0

        suspend fun pass() {
            lock.withLock {
                passes++
                delay(2_000)
            }
        }

        val coalescer = WidgetRefreshCoalescer(cooldown) { pass() }
        val loop = launch { coalescer.run() }

        coalescer.request()
        advanceTimeBy(500)
        repeat(10) { coalescer.request() } // all arrive while the first pass is rendering
        advanceUntilIdle()

        assertEquals("one leading pass plus one trailing pass, never eleven", 2, passes)
        loop.cancel()
    }
}
