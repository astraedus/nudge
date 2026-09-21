package com.astraedus.nudge.util

import com.astraedus.nudge.domain.logging.NudgeLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Backlog audit F7: one unhandled exception on `NudgeAccessibilityService.serviceScope` used to
 * kill the process, and with it the accessibility service — which is the only thing enforcing any
 * block in this app.
 *
 * These are OUTCOME tests, and the one that matters is the last pair: the same throwing coroutine
 * on a bare `SupervisorJob()` scope reaches the thread's default uncaught-exception handler (which
 * in production is the thing that tears the process down), and on a [CrashSafeScope] it does not.
 * Without that counterfactual this file would assert that a logger got called and prove nothing
 * about the incident it exists to prevent.
 */
class CrashSafeScopeTest {

    private class RecordingLog : NudgeLog {
        val errors = mutableListOf<Pair<String, Throwable?>>()
        override fun d(message: String, throwable: Throwable?) = Unit
        override fun i(message: String, throwable: Throwable?) = Unit
        override fun w(message: String, throwable: Throwable?) = Unit
        override fun e(message: String, throwable: Throwable?) {
            synchronized(errors) { errors += message to throwable }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val log = RecordingLog()

    /** What the platform would do with an unhandled coroutine exception: the crash path. */
    private var uncaught: Throwable? = null
    private var previousDefaultHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun installCrashProbe() {
        previousDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> uncaught = throwable }
    }

    @After
    fun restore() {
        Thread.setDefaultUncaughtExceptionHandler(previousDefaultHandler)
        dispatcher.close()
        executor.shutdownNow()
    }

    private fun scope(): CoroutineScope =
        CrashSafeScope.create(name = SCOPE_NAME, dispatcher = dispatcher, logger = { log })

    /** Waits for everything already queued on the single-thread dispatcher to have run. */
    private fun drain() {
        val done = CountDownLatch(1)
        executor.execute { done.countDown() }
        assertTrue("the dispatcher must drain", done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a throwing child does not cancel its siblings`() {
        val scope = scope()
        val finished = mutableListOf<String>()

        runBlocking {
            scope.launch { finished += "before" }.join()
            scope.launch { throw IllegalStateException("room went down") }.join()
            scope.launch { finished += "after" }.join()
        }
        drain()

        assertEquals(listOf("before", "after"), finished)
    }

    @Test
    fun `a throwing child does not cancel the scope, so later work still runs`() {
        val scope = scope()
        runBlocking { scope.launch { throw IllegalStateException("binder died") }.join() }
        drain()

        assertTrue("the scope must survive a failed child", scope.isActive)

        var ran = false
        runBlocking { scope.launch { ran = true }.join() }
        assertTrue("work launched after the failure must still run", ran)
    }

    @Test
    fun `the failure is logged at error level, naming the scope`() {
        val boom = IllegalStateException("insert failed")
        val scope = scope()
        runBlocking { scope.launch { throw boom }.join() }
        drain()

        assertEquals("exactly one error must be logged", 1, log.errors.size)
        val (message, throwable) = log.errors.single()
        assertTrue(
            "the log line must name the scope so a bug report says WHICH scope died, got: $message",
            message.contains(SCOPE_NAME)
        )
        assertEquals("the original throwable must be logged, not a summary", boom, throwable)
    }

    /**
     * THE INCIDENT. A bare supervisor scope hands the throwable to the thread's default handler,
     * which is how one Room or binder failure took the accessibility service down with the process.
     */
    @Test
    fun `a bare SupervisorJob scope still reaches the crash handler`() {
        val bare = CoroutineScope(SupervisorJob() + dispatcher)
        runBlocking { bare.launch { throw IllegalStateException("the F7 incident") }.join() }
        drain()

        assertNotNull(
            "counterfactual: without a CoroutineExceptionHandler the exception reaches the " +
                "default uncaught handler -- in production that kills the process and stops " +
                "every block this app enforces",
            uncaught
        )
        assertTrue(log.errors.isEmpty())
    }

    @Test
    fun `a CrashSafeScope never reaches the crash handler`() {
        val scope = scope()
        runBlocking { scope.launch { throw IllegalStateException("the F7 incident") }.join() }
        drain()

        assertNull(
            "an unhandled exception on this scope must be logged and swallowed, never propagated " +
                "to the handler that ends the process",
            uncaught
        )
        assertFalse("...and it must not be swallowed silently either", log.errors.isEmpty())
    }

    private companion object {
        const val SCOPE_NAME = "test-scope"
    }
}
