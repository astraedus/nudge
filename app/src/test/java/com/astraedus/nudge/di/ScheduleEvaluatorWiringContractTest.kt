package com.astraedus.nudge.di

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L5, source-level: the real app must get the FIRST-UNLOCK AWARE evaluator.
 *
 * `ScheduleEvaluator` takes its anchor through a constructor parameter, so a bare
 * `ScheduleEvaluator()` is a valid object that answers "no first-unlock window is ever active". That
 * is the right default for a pure test and the wrong one for production, and review caught exactly
 * that: an explicit `@Provides fun provideScheduleEvaluator() = ScheduleEvaluator()` in this module
 * won over the `@Inject` constructor, so the service, `BlockEngine` and `HomeViewModel` all got the
 * anchorless object and every first-unlock rule was inert in the shipped app while the unit tests
 * (which pass a fake state) stayed green.
 *
 * The graph cannot be built on the JVM, so the contract is pinned over the sources: nothing under
 * `main` may construct the evaluator itself, and the module may not provide one.
 */
class ScheduleEvaluatorWiringContractTest {

    private val srcDir: File by lazy {
        listOf(File("app/src"), File("src")).firstOrNull { File(it, "main").isDirectory }
            ?: error("app/src not found from ${File("").absolutePath}")
    }

    private fun kotlinSources(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `nothing under main constructs a bare ScheduleEvaluator`() {
        val offenders = kotlinSources(File(srcDir, "main"))
            .filter { it.readText().contains("ScheduleEvaluator()") }
            .map { it.relativeTo(srcDir).path }

        assertTrue(
            "these construct the anchorless evaluator, so first-unlock rules would be inert: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `the repository module does not provide an evaluator`() {
        val module = File(srcDir, "main/java/com/astraedus/nudge/di/RepositoryModule.kt").readText()

        assertTrue(
            "an explicit provider wins over the @Inject constructor, bypassing FirstUnlockState",
            !module.contains("provideScheduleEvaluator")
        )
        assertTrue(
            "the graph must use ScheduleEvaluator's @Inject constructor",
            !module.contains("ScheduleEvaluator()")
        )
    }
}
