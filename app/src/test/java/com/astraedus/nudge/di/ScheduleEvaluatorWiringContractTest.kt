package com.astraedus.nudge.di

import com.astraedus.nudge.testing.SourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L5, source-level: the real app must get the FIRST-UNLOCK AWARE evaluator.
 *
 * `ScheduleEvaluator` takes its anchor through a constructor parameter, so a bare
 * `ScheduleEvaluator()` and a `FirstUnlockState.NONE` are both valid objects that answer "no
 * first-unlock window is ever active". That is the right default for a pure test and the wrong one
 * for production, and review caught exactly that: an explicit
 * `@Provides fun provideScheduleEvaluator() = ScheduleEvaluator()` in `RepositoryModule` won over
 * the `@Inject` constructor, so the service, `BlockEngine` and `HomeViewModel` all got the
 * anchorless object and every first-unlock rule was inert in the shipped app while the unit tests
 * (which pass a fake state) stayed green.
 *
 * The graph cannot be built on the JVM, so the contract is pinned over the sources: nothing under
 * `main` may construct the evaluator itself or name the anchorless state, and no `di` module may
 * provide one. A scan test is for a shape the compiler cannot see; this is that shape.
 */
class ScheduleEvaluatorWiringContractTest {

    private fun offenders(needle: String, subdir: String = ""): List<String> =
        SourceScan.kotlinSources(subdir)
            .filter { SourceScan.code(it).contains(needle) }
            .map { it.relativeTo(SourceScan.projectRoot).path }

    @Test
    fun `nothing under main constructs a bare ScheduleEvaluator`() {
        val found = offenders("ScheduleEvaluator()")

        assertTrue(
            "these construct the anchorless evaluator, so first-unlock rules would be inert: $found",
            found.isEmpty()
        )
    }

    @Test
    fun `nothing under main names the anchorless state`() {
        val found = offenders("FirstUnlockState.NONE")

        assertTrue(
            "production must get FirstUnlockStateCache from Hilt, never NONE: $found",
            found.isEmpty()
        )
    }

    @Test
    fun `no di module provides an evaluator`() {
        val found = offenders("provideScheduleEvaluator", subdir = "com/astraedus/nudge/di")

        assertTrue(
            "an explicit provider wins over the @Inject constructor, bypassing FirstUnlockState: $found",
            found.isEmpty()
        )
    }
}
