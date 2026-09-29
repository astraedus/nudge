package com.astraedus.nudge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the two rules that close [#53](https://github.com/astraedus/nudge/issues/53):
 * **a test that deliberately lets a coroutine exception go unhandled must drain the
 * process-global collector before it finishes**, and **a test that installs the Main dispatcher
 * must do it through [MainDispatcherRule]**, which owns the same cleanup.
 *
 * There is no behavioural place to assert this from. The damage a leak does is to whichever test
 * `runTest`s next in the same JVM fork, which is decided by Gradle's class order — so a test that
 * looked for the damage would be asserting on the run order, i.e. it would be the very flake this
 * is fixing. The invariant is a property of the SOURCE, so it is checked in the source, in the
 * shape `BlockOverlayWalkAwayContractTest` already uses here.
 *
 * The handle is `Thread.setDefaultUncaughtExceptionHandler`: a unit test installs one for exactly
 * one reason — it expects an unhandled throwable to arrive there — and that is the same throwable
 * kotlinx's `ExceptionCollector` queues on the way past. The two travel together, so pairing the
 * handler with [LeakedCoroutineExceptions] is a real semantic link and not a keyword coincidence.
 */
class LeakedCoroutineExceptionsContractTest {

    private val testSourceRoot: File by lazy {
        val candidates = listOf(
            File("src/test/java/com/astraedus/nudge"),
            File("app/src/test/java/com/astraedus/nudge")
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("test sources not found from working dir ${File("").absolutePath}")
    }

    private val testSources: List<File> by lazy {
        testSourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /**
     * Comments stripped before matching: this file and [LeakedCoroutineExceptions] both have to
     * name the thing they are about, and a contract test that greps raw source reads its own
     * explanation as code.
     */
    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .lines()
        .joinToString("\n") { line -> line.substringBefore("//") }

    @Test
    fun `the suite has test sources to scan at all`() {
        assertTrue(
            "the scan found nothing, so every assertion below is vacuous",
            testSources.size > 100
        )
    }

    /**
     * The offenders, named. Today there is exactly one and it is `CrashSafeScopeTest`, whose
     * counterfactual cannot be written any other way — the claim IS that the throwable travels the
     * global unhandled path. A second one appearing is not forbidden; it just has to clean up too.
     */
    @Test
    fun `every test that expects an unhandled throwable drains the global collector`() {
        val offenders = testSources
            .map { it to stripComments(it.readText()) }
            .filter { (_, body) -> body.contains("setDefaultUncaughtExceptionHandler") }
            .filterNot { (_, body) -> body.contains("LeakedCoroutineExceptions.drain()") }
            .map { (file, _) -> file.name }

        assertEquals(
            "these tests let a coroutine exception reach the thread's default handler, which means " +
                "kotlinx-coroutines-test's process-global ExceptionCollector queued it too -- the " +
                "next runTest ANYWHERE in this fork then fails with UncaughtExceptionsBeforeTest " +
                "(issue #53). Call LeakedCoroutineExceptions.drain() from @After",
            emptyList<String>(),
            offenders
        )
    }

    /**
     * The SECOND fork-global handle, and the one that reopened this issue on `v1.18.4`.
     *
     * `Dispatchers.setMain` is process-global, and its teardown is a trap rather than a cleanup: in
     * a unit-test fork `Dispatchers.resetMain()` restores a dispatcher whose `isDispatchNeeded`
     * **throws**, so any coroutine of the finished test still alive on `Dispatchers.Main` produces
     * an uncaught `CompletionHandlerException` that lands in the same process-global collector and
     * fails a later, unrelated test. [MainDispatcherRule] resets Main from `finished()` (after the
     * class's own `@After`) and drains immediately afterwards.
     *
     * Checked as an ABSENCE over a discovered set rather than as a list of the six classes that do
     * it today (rule (e) of `docs/testing-strategy.md`): a ViewModel test written next month is
     * covered without anyone remembering to edit this file.
     */
    @Test
    fun `no test installs the Main dispatcher by hand`() {
        val offenders = testSources
            // The rule IS the sanctioned caller, and this file names the call in its own failure
            // message. Matching the receiver too is what keeps `resetMain(` -- which contains
            // `setMain(` -- from reading as an offender; the first version of this test failed on
            // three files for exactly that reason.
            .filterNot { it.name == "MainDispatcherRule.kt" }
            .filterNot { it.name == "LeakedCoroutineExceptionsContractTest.kt" }
            .map { it to stripComments(it.readText()) }
            .filter { (_, body) -> body.contains("Dispatchers.setMain(") }
            .map { (file, _) -> file.name }

        assertEquals(
            "use MainDispatcherRule instead of calling Dispatchers.setMain directly. It resets " +
                "Main AFTER the class's own @After methods and then drains the process-global " +
                "collector -- resetMain() restores a dispatcher that THROWS on dispatch, so a " +
                "coroutine of this test still alive on Dispatchers.Main afterwards queues an " +
                "uncaught CompletionHandlerException and fails an unrelated test later in the " +
                "fork (issue #53, v1.18.4). See MainDispatcherResetLeakTest for the mechanism",
            emptyList<String>(),
            offenders
        )
    }

    /**
     * Guards the fix from being deleted as unused. `drain()` returning `false` forever is the
     * failure mode this cannot see; `CrashSafeScopeTest` asserts the true case.
     */
    @Test
    fun `the drain helper is actually wired to a test`() {
        val users = testSources
            .filterNot { it.name == "LeakedCoroutineExceptions.kt" }
            .filterNot { it.name == "LeakedCoroutineExceptionsContractTest.kt" }
            .count { stripComments(it.readText()).contains("LeakedCoroutineExceptions.drain()") }

        assertTrue("LeakedCoroutineExceptions.drain() is referenced by no test", users > 0)
    }
}
