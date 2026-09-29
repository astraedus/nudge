package com.astraedus.nudge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Installs a [TestDispatcher] as `Dispatchers.Main` for one test and takes it back down again —
 * **including the process-global residue that taking it down can produce.**
 *
 * ## Why a rule and not two lines in `@Before`/`@After` ([#53](https://github.com/astraedus/nudge/issues/53))
 *
 * `Dispatchers.setMain` writes state the whole JVM fork shares, and the teardown half is sharper
 * than it looks. In a unit-test fork there is no Android main looper, so the dispatcher
 * `Dispatchers.resetMain()` restores is `MissingMainCoroutineDispatcher`, and that one does not
 * merely refuse work — its `isDispatchNeeded` **throws**. So `resetMain()` is not a neutral
 * cleanup: it arms a trap for any coroutine of this test that is still alive on `Dispatchers.Main`
 * one microsecond later.
 *
 * When such a coroutine (a `viewModelScope` chain with a step on a real thread pool, say) then
 * completes on that pool thread and resumes its `Dispatchers.Main` continuation, the throw is
 * wrapped as a `CompletionHandlerException` with no `CoroutineExceptionHandler` in its context.
 * `kotlinx-coroutines-test`'s process-global `ExceptionCollector` queues it, nobody is listening
 * while no test is running, and the next `runTest` **anywhere in the fork** dies at its first line
 * with `UncaughtExceptionsBeforeTest` — a random victim, decided only by Gradle's class order.
 *
 * That is what reopened #53 on `v1.18.4`: `HomeBlockedTileTest` lost that race on a CI runner
 * (~4% of suite runs) and the reported failure was the *next* test in the class.
 *
 * ## What this rule guarantees
 *
 * 1. `resetMain()` runs from [finished], i.e. **after** the class's own `@After` methods, so a
 *    teardown that needs a working main dispatcher still has one.
 * 2. [LeakedCoroutineExceptions.drain] runs immediately after it, so anything that lost the race
 *    inside that window is taken back out of the global queue by the class that created it — rule
 *    (f) of `docs/testing-strategy.md`, applied to the `setMain` case.
 *
 * The drain is a sweep of a race window, not a deliberate leak, so unlike `CrashSafeScopeTest` it
 * does **not** assert that it found something: on a healthy run it finds nothing. The reason it
 * finds nothing is the real fix — no ViewModel names a real dispatcher any more
 * (`di/DispatcherModule`, pinned by [ViewModelDispatcherContractTest]), so a test that hands in its
 * own test dispatcher has no background worker to lose the race to. This rule is the second layer.
 *
 * `LeakedCoroutineExceptionsContractTest` asserts no test file calls `Dispatchers.setMain` itself,
 * so the pairing cannot be forgotten by writing the two lines out by hand instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
        LeakedCoroutineExceptions.drain()
    }
}
