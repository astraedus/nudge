package com.astraedus.nudge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The mechanism that reopened [#53](https://github.com/astraedus/nudge/issues/53), as a
 * deterministic pair. This is the test the first fix did not have: it pins WHY
 * [MainDispatcherRule] exists, rather than pinning that some class remembered to call a drain.
 *
 * ## The mechanism
 *
 * In a JVM unit-test fork there is no Android main looper, so `Dispatchers.Main` is a
 * `TestMainDispatcher` whose default delegate is `MissingMainCoroutineDispatcher` — and that one's
 * `isDispatchNeeded` **throws** rather than returning false. `Dispatchers.resetMain()` therefore
 * does not restore a neutral dispatcher; it arms a trap. Any coroutine of the finished test that is
 * still alive on `Dispatchers.Main` and later resumes — because a step of it was running on a real
 * thread pool, outside every `TestScope` — takes that throw as a `CompletionHandlerException` with
 * no `CoroutineExceptionHandler` in its context, and `kotlinx-coroutines-test`'s process-global
 * `ExceptionCollector` queues it for whichever `runTest` starts next in the fork.
 *
 * On `v1.18.4` that was `HomeBlockedTileTest`, whose `HomeViewModel` chain had two
 * `flowOn(Dispatchers.IO)` steps: it lost the race on ~4% of CI runs (1 of 12 parallel runs of both
 * variants, measured) and the reported failure was the NEXT test method in the same class.
 *
 * ## The two halves
 *
 * The first test reproduces the leak on purpose. The second runs the identical coroutine but lets
 * it FINISH before Main is reset, and asserts nothing is queued — so the assertion is about the
 * ORDER (rule (d) of `docs/testing-strategy.md`), not about coroutines in general. Without the
 * second half the first would pass on a build where every resume leaks.
 *
 * The real fix is upstream of both: `di/DispatcherModule` makes the off-main dispatcher an injected
 * parameter, so a test hands in its own test dispatcher and no background worker exists to lose the
 * race to ([ViewModelDispatcherContractTest] holds that). [MainDispatcherRule] is the second layer,
 * and this file is the proof that the layer is load-bearing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherResetLeakTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * Not `Dispatchers.Main.immediate` only because `viewModelScope` uses that: the point is that
     * the continuation's dispatcher IS `Dispatchers.Main`, whatever its immediacy.
     */
    private fun mainScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Test
    fun `a Main-dispatched coroutine that outlives resetMain leaks into the global collector`() {
        // Arm kotlinx's collector and start from empty, so what is measured below is OUR leak
        // wherever this class lands in the run order.
        LeakedCoroutineExceptions.drain()

        val scope = mainScope()
        val releaseTheWorker = CountDownLatch(1)
        // A real thread pool, which is what `flowOn(Dispatchers.IO)` inside a ViewModel was: the
        // coroutine is now parked OUTSIDE any TestScope, so no test cleanup waits for it.
        scope.launch { withContext(Dispatchers.IO) { releaseTheWorker.await() } }

        Dispatchers.resetMain()
        // ...and only now does the pool thread finish and try to resume on Dispatchers.Main.
        releaseTheWorker.countDown()

        assertTrue(
            "resetMain() over a live Dispatchers.Main coroutine must put an uncaught " +
                "CompletionHandlerException in kotlinx's process-global ExceptionCollector -- if " +
                "this ever reads false the mechanism behind #53 has changed and MainDispatcherRule " +
                "may no longer be needed for the reason it documents",
            awaitLeak()
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

        assertFalse(
            "the order is what leaks, not the coroutine: finished first, this must be clean",
            LeakedCoroutineExceptions.drain()
        )
        scope.cancel()
    }

    /**
     * The throw happens on a pool thread, so it can land a moment after [CountDownLatch.countDown]
     * returns. Polling the drain rather than sleeping a fixed time keeps the test fast when it is
     * prompt and non-flaky when the machine is loaded.
     */
    private fun awaitLeak(): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (LeakedCoroutineExceptions.drain()) return true
            Thread.sleep(10)
        }
        return false
    }
}
