package com.astraedus.nudge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The mechanism that reopened [#53](https://github.com/astraedus/nudge/issues/53), as a
 * deterministic pair. This is the test the first fix did not have: it pins WHY
 * [MainDispatcherRule] exists, instead of pinning that some class remembered to call a drain.
 *
 * ## The mechanism
 *
 * In a JVM unit-test fork there is no Android main looper, so `Dispatchers.Main` is a
 * `TestMainDispatcher` whose default delegate is `MissingMainCoroutineDispatcher` — and that one's
 * `isDispatchNeeded` **throws** rather than returning false. `Dispatchers.resetMain()` therefore
 * does not restore a neutral dispatcher; it arms a trap. Any coroutine of the finished test that is
 * still alive on `Dispatchers.Main` and later resumes — because a step of it was running on a real
 * thread pool, outside every `TestScope` — takes that throw, and with no `CoroutineExceptionHandler`
 * in its context it reaches `handleCoroutineException`, where `kotlinx-coroutines-test`'s
 * process-global `ExceptionCollector` queues it for whichever `runTest` starts next in the fork.
 *
 * On `v1.18.4` that was `HomeBlockedTileTest`, whose `HomeViewModel` chain had two
 * `flowOn(Dispatchers.IO)` steps. It lost the race on ~4% of CI runs (1 of 12 parallel runs of both
 * variants, measured) and the reported failure was the NEXT test method in the same class.
 *
 * ## What is asserted, and what is deliberately not
 *
 * Asserted: the throwable **carries the missing-Main-dispatcher failure** and **lands in the
 * process-global collector**. Those are the two properties that damage another test.
 *
 * Not asserted: which internal route kotlinx took to get there. Depending on whether the outer job
 * is completing or cancelling, the same trap surfaces as a `CompletionHandlerException` (what CI
 * captured) or as a `CoroutinesInternalError` from `DispatchedTask.handleFatalException`. Both call
 * `handleCoroutineException`, both queue, both ruin the next test — so pinning the branch would be
 * pinning which internal path ran, which this repo has learned not to do.
 *
 * The second test runs the identical coroutine but lets it FINISH before Main is reset, and asserts
 * nothing is queued — so the pair is about the ORDER (rule (d) of `docs/testing-strategy.md`), not
 * about coroutines in general. Without it the first test would pass on a build where every resume
 * leaks.
 *
 * The real fix is upstream of both: `di/DispatcherModule` makes the off-main dispatcher an injected
 * parameter, so a test hands in its own test dispatcher and no pool worker exists to lose the race
 * to ([ViewModelDispatcherContractTest] holds that). [MainDispatcherRule] is the second layer, and
 * this file is the proof that the layer is load-bearing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherResetLeakTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private var previousDefaultHandler: Thread.UncaughtExceptionHandler? = null
    private val handled = CountDownLatch(1)
    private val handledThrowable = AtomicReference<Throwable?>(null)

    /**
     * The thread's default handler is the LAST thing `handleCoroutineException` calls, after every
     * `ServiceLoader` handler — so `ExceptionCollector` has already queued the throwable by the time
     * this runs. Recording it therefore gives a **deterministic** "the leak has happened now"
     * signal, which is what makes the assertion below race-free.
     *
     * Polling `LeakedCoroutineExceptions.drain()` in a loop instead does NOT work, and the first
     * version of this test flaked on exactly that: each poll is a live `runTest`, and an exception
     * arriving while one is active is reported INTO it rather than queued, so the poll that was
     * meant to observe the leak becomes its victim.
     *
     * It also keeps a 15-line "Fatal exception in coroutines machinery" stack off stderr on every
     * run, which reads like a broken build.
     */
    @Before
    fun recordTheFinalResort() {
        previousDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            handledThrowable.set(throwable)
            handled.countDown()
        }
    }

    @After
    fun restore() {
        Thread.setDefaultUncaughtExceptionHandler(previousDefaultHandler)
        // The rule drains too, but this class deliberately produces the leak, so it owns the
        // cleanup even if the rule is ever changed (rule (f) of docs/testing-strategy.md).
        LeakedCoroutineExceptions.drain()
    }

    /**
     * Not `Dispatchers.Main.immediate` only because `viewModelScope` happens to use that: the point
     * is that the continuation's dispatcher IS `Dispatchers.Main`, whatever its immediacy.
     */
    private fun mainScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Test
    fun `a Main-dispatched coroutine that outlives resetMain leaks into the global collector`() {
        // Arm kotlinx's collector and start from empty, so what is measured below is OUR leak
        // wherever this class lands in the run order.
        LeakedCoroutineExceptions.drain()

        val scope = mainScope()
        val releaseTheWorker = CountDownLatch(1)
        // A real thread pool -- which is what `flowOn(Dispatchers.IO)` inside a ViewModel was. The
        // coroutine parks OUTSIDE any TestScope, so no test cleanup waits for it.
        scope.launch { withContext(Dispatchers.IO) { releaseTheWorker.await() } }

        Dispatchers.resetMain()
        // ...and only now does the pool thread finish and try to resume on Dispatchers.Main.
        releaseTheWorker.countDown()

        assertTrue(
            "the resume onto a reset Dispatchers.Main must fail and reach handleCoroutineException",
            handled.await(10, TimeUnit.SECONDS)
        )
        assertTrue(
            "the failure must be the missing main dispatcher, not something incidental, got: " +
                causeChain(handledThrowable.get()),
            causeChain(handledThrowable.get()).contains("Main dispatcher")
        )
        assertTrue(
            "...and it must be sitting in kotlinx's process-global ExceptionCollector, which is " +
                "what makes the NEXT runTest anywhere in this fork fail with " +
                "UncaughtExceptionsBeforeTest. If this ever reads false the mechanism behind #53 " +
                "has changed and MainDispatcherRule may no longer be needed for the reason it " +
                "documents",
            LeakedCoroutineExceptions.drain()
        )
        scope.cancel()
    }

    @Test
    fun `the same coroutine, finished before resetMain, leaks nothing`() {
        LeakedCoroutineExceptions.drain()

        val scope = mainScope()
        val releaseTheWorker = CountDownLatch(1)
        val job = scope.launch { withContext(Dispatchers.IO) { releaseTheWorker.await() } }

        releaseTheWorker.countDown()
        // The resume onto Dispatchers.Main happens while Main is still a working test dispatcher.
        runBlocking { job.join() }
        Dispatchers.resetMain()

        assertNull(
            "nothing should have reached the uncaught path at all",
            handledThrowable.get()
        )
        assertFalse(
            "the ORDER is what leaks, not the coroutine: finished first, this must be clean",
            LeakedCoroutineExceptions.drain()
        )
        scope.cancel()
    }

    private fun causeChain(t: Throwable?): String = buildString {
        var current = t
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            append(current::class.java.simpleName).append(": ").append(current.message).append(" | ")
            current = current.cause
        }
    }
}
