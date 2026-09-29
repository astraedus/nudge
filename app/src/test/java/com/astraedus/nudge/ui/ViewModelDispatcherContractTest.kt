package com.astraedus.nudge.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **No ViewModel names a dispatcher.** The off-main dispatcher arrives as an injected parameter
 * (`di/DispatcherModule`, `@IoDispatcher`), never as `Dispatchers.IO` written into the class.
 *
 * ## The defect class ([#53](https://github.com/astraedus/nudge/issues/53))
 *
 * A `ViewModel` that writes `flowOn(Dispatchers.IO)` or `withContext(Dispatchers.IO)` into its own
 * chain puts part of that chain on a real thread pool. `viewModelScope` is not a child of any
 * `TestScope`, so `runTest` returns without waiting for it; the test's teardown then calls
 * `Dispatchers.resetMain()`, which in a unit-test fork restores a dispatcher whose
 * `isDispatchNeeded` **throws**; and the pool thread, finishing a moment later, takes that throw
 * with no `CoroutineExceptionHandler` in its context. `kotlinx-coroutines-test`'s process-global
 * `ExceptionCollector` queues it and the next `runTest` ANYWHERE in the fork fails instead —
 * `MainDispatcherResetLeakTest` reproduces exactly that, deterministically.
 *
 * `HomeViewModel` had two such steps and `HomeBlockedTileTest` lost the race on ~4% of CI runs, for
 * two releases, with a different victim each time. Injected, the test hands in its own test
 * dispatcher, the whole chain runs on the test scheduler on the test's own thread, and there is no
 * background worker left to lose the race to. That is the fix; the rest is cleanup.
 *
 * ## Why this shape of test
 *
 * It is an ABSENCE over a DISCOVERED set (rule (e) of `docs/testing-strategy.md`): every
 * `*ViewModel.kt` under `ui/` is found by walking the tree, and none of them may contain the token,
 * so a ViewModel added tomorrow is covered without anyone editing a list here. The alternative —
 * running the whole suite through `JUnitCore` and checking the global collector between classes —
 * was built and measured first: it found ZERO leakers across all 158 test classes even with a 40
 * second settle, because the defect is a timing race that only loses on a slow runner. A gate that
 * cannot see the bug it is named after is not a gate, so the invariant is enforced where it is
 * decidable: in the source.
 */
class ViewModelDispatcherContractTest {

    private val uiSourceRoot: File by lazy {
        listOf(
            File("src/main/java/com/astraedus/nudge/ui"),
            File("app/src/main/java/com/astraedus/nudge/ui")
        ).firstOrNull { it.isDirectory }
            ?: error("ui sources not found from working dir ${File("").absolutePath}")
    }

    private val viewModels: List<File> by lazy {
        uiSourceRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith("ViewModel.kt") }
            .sortedBy { it.name }
            .toList()
    }

    /** A contract test that greps raw source reads its own explanation as code. */
    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .lines()
        .joinToString("\n") { line -> line.substringBefore("//") }

    @Test
    fun `the scan finds the ViewModels at all`() {
        assertTrue(
            "found ${viewModels.size} ViewModel files, so the assertion below is vacuous",
            viewModels.size >= 8
        )
    }

    @Test
    fun `no ViewModel names a dispatcher of its own`() {
        val offenders = viewModels
            .filter { stripComments(it.readText()).contains("Dispatchers.") }
            .map { it.name }

        assertEquals(
            "a ViewModel must take its off-main dispatcher as an @IoDispatcher constructor " +
                "parameter, not name Dispatchers.* itself: a hard-coded dispatcher puts part of " +
                "the viewModelScope chain on a real thread pool that no TestScope waits for, and " +
                "the resume that lands after the test's Dispatchers.resetMain() throws into " +
                "kotlinx's process-global ExceptionCollector -- failing an unrelated test later in " +
                "the fork (issue #53). See di/DispatcherModule and MainDispatcherResetLeakTest",
            emptyList<String>(),
            offenders
        )
    }

    /**
     * The injection is only real if it reaches the call sites. `HomeViewModel` is the one this was
     * found on, and both of its steps have to be on the parameter.
     */
    @Test
    fun `the ViewModel that shipped the defect routes its off-main work through the parameter`() {
        val source = stripComments(
            viewModels.single { it.name == "HomeViewModel.kt" }.readText()
        )
        assertTrue(
            "HomeViewModel must declare the injected dispatcher",
            source.contains("@IoDispatcher private val ioDispatcher: CoroutineDispatcher")
        )
        assertEquals(
            "both of HomeViewModel's off-main steps must use the injected dispatcher -- one of " +
                "the two being left behind is the whole bug, since either is enough to start a " +
                "real pool worker",
            2,
            Regex("""flowOn\(ioDispatcher\)""").findAll(source).count()
        )
    }
}
