package com.astraedus.nudge.ui.redirect

import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.RedirectAppRepository
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The block screen's first frame must not wait on the redirect app's lookup past a small budget.
 *
 * Two Play ANRs (moto g32, Android 13, 1.31.0 and 1.31.2) had the main thread parked in the block
 * screen's `runBlocking` while a worker listed every installed app for the redirect bubble. The slow
 * part is a blocking PackageManager binder call, which cancellation cannot interrupt, so the lookup
 * here is modelled the same way: a thread blocked on a latch, inside `withContext(Dispatchers.IO)`.
 */
class FirstFrameLookupTest {

    private val scope = CoroutineScope(SupervisorJob())
    private val stuck = CountDownLatch(1)

    /** A lookup stuck in an uninterruptible blocking call, as `PackageManagerAppsSource` is. */
    private suspend fun stuckLookup(): String =
        withContext(Dispatchers.IO) {
            stuck.await(10, TimeUnit.SECONDS)
            "late"
        }

    @After
    fun tearDown() {
        stuck.countDown()
        scope.cancel()
    }

    private inline fun elapsedMs(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun `a stuck lookup does not hold the first frame past the budget`() {
        val lookup = FirstFrameLookup(scope, Dispatchers.IO) { stuckLookup() }
        var ready: FirstFrameLookup.Ready<String>? = FirstFrameLookup.Ready("unset")
        val waited = elapsedMs { ready = runBlocking { lookup.awaitWithin(100) } }

        assertNull("not ready: the first frame goes out without it", ready)
        assertTrue("the caller waited ${waited}ms for a 100ms budget", waited < 1_000)
    }

    @Test
    fun `the late answer still arrives once the lookup finishes`() {
        val lookup = FirstFrameLookup(scope, Dispatchers.IO) { stuckLookup() }
        assertNull(runBlocking { lookup.awaitWithin(50) })
        stuck.countDown()
        assertEquals("late", runBlocking { withTimeout(5_000) { lookup.await() } })
    }

    @Test
    fun `a fast answer is on the first frame, and a fast null is ready, not missing`() {
        val found = FirstFrameLookup(scope, Dispatchers.IO) { "wikipedia" }
        assertEquals(FirstFrameLookup.Ready("wikipedia"), runBlocking { found.awaitWithin(2_000) })

        val nothingSaved = FirstFrameLookup<String?>(scope, Dispatchers.IO) { null }
        assertEquals(FirstFrameLookup.Ready(null), runBlocking { nothingSaved.awaitWithin(2_000) })
    }

    /**
     * Why the wait is on a Deferred: the obvious fix, a timeout around the CALL, fires on time and
     * still waits the whole blocking call out, because `withContext` returns only when its block does.
     */
    @Test
    fun `a timeout around the blocking call itself does not bound the wait`() {
        Thread { Thread.sleep(800); stuck.countDown() }.start()
        val waited = elapsedMs { runBlocking { withTimeoutOrNull(100) { stuckLookup() } } }
        assertTrue("expected the naive timeout to wait the call out, waited ${waited}ms", waited >= 700)
    }

    @Test
    fun `the bubble fills in with the late answer`() {
        val controller = RedirectAppController(mockk<RedirectAppRepository>(relaxed = true), scope)
        val app = InstalledAppsRepository.AppInfo("org.wikipedia", "Wikipedia", null)
        val answered = CountDownLatch(1)
        controller.showWhenResolved { app.also { answered.countDown() } }
        assertTrue(answered.await(5, TimeUnit.SECONDS))
        waitUntil { controller.target == app }
        assertEquals(app, controller.target)
    }

    @Test
    fun `a pick or removal made while waiting is not overwritten by the late answer`() {
        val controller = RedirectAppController(mockk<RedirectAppRepository>(relaxed = true), scope)
        val app = InstalledAppsRepository.AppInfo("org.wikipedia", "Wikipedia", null)
        val answered = CountDownLatch(1)
        controller.showWhenResolved {
            withContext(Dispatchers.IO) { stuck.await(10, TimeUnit.SECONDS) }
            app.also { answered.countDown() }
        }
        controller.remove()
        stuck.countDown()
        assertTrue(answered.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertNull("the user removed it here; the late answer must not bring it back", controller.target)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }
}
