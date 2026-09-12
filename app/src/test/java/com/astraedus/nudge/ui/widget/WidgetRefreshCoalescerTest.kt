package com.astraedus.nudge.ui.widget

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
