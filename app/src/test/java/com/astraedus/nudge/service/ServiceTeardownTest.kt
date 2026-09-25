package com.astraedus.nudge.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The teardown path that [NudgeAccessibilityService.onDestroy] runs, exercised without a device
 * ([#57](https://github.com/astraedus/nudge/issues/57)).
 *
 * The service itself is not JVM-constructible (`android.app.Service`'s framework methods are stubs
 * in the unit-test `android.jar`), which is exactly why the step runner was lifted out of it: the
 * behaviour that matters — *a failing step neither propagates nor skips the steps after it* — is
 * pure Kotlin and belongs at L3 in `docs/testing-strategy.md`, not on the bench Pixel.
 *
 * The failure being reproduced is the real one from the device's dropbox: teardown before
 * `onServiceConnected` read an unassigned `lateinit` and threw
 * [kotlin.UninitializedPropertyAccessException] out of `onDestroy`, which Android reports as
 * *"Unable to stop service"* and records as a **crashed** accessibility service — never rebound, so
 * every rule silently stops enforcing. `ServiceTeardownContractTest` owns the other half (that the
 * service's own teardown actually routes through here, and that the `lateinit` reads are guarded).
 */
class ServiceTeardownTest {

    /**
     * The headline case, in the shape it shipped in: the step that reads the uninitialised clock.
     *
     * Note this is the *containment* half — the guard in `stopForegroundTimeTicker` means the throw
     * should not happen at all any more. Both halves are wanted: the guard stops this one field, and
     * the containment stops the next field anyone adds, which is the part a hand-list cannot.
     */
    @Test
    fun `a step that reads an uninitialised lateinit does not escape teardown`() {
        val failures = mutableListOf<String>()
        val teardown = ServiceTeardown { step, _ -> failures += step }

        // No try/catch here on purpose: the assertion is that nothing propagates to the caller,
        // and the caller in production is Android's ActivityThread.handleStopService.
        teardown.step("stop_foreground_clock") {
            throw kotlin.UninitializedPropertyAccessException(
                "lateinit property foregroundClock has not been initialized"
            )
        }

        assertEquals(listOf("stop_foreground_clock"), failures)
    }

    /**
     * The half the original code got wrong quite apart from the crash: the throw was on the third
     * line of `onDestroy`, so the scope was never cancelled, the overlay managers kept a dead
     * service as their context, and grayscale could be left applied.
     */
    @Test
    fun `a failing step does not skip the steps after it`() {
        val ran = mutableListOf<String>()
        val teardown = ServiceTeardown { step, _ -> ran += "failed:$step" }

        teardown.step("first") { ran += "first" }
        teardown.step("boom") { error("this step is broken") }
        teardown.step("last") { ran += "last" }

        assertEquals(listOf("first", "failed:boom", "last"), ran)
    }

    /**
     * `NoSuchMethodError` from an API-level mistake is an [Error], not an [Exception], and this repo
     * has shipped that bug class before (`docs/TESTING.md`). It leaves the service just as crashed,
     * so teardown has to contain it too.
     */
    @Test
    fun `an Error in a step is contained as well as an Exception`() {
        val failures = mutableListOf<Throwable>()
        val teardown = ServiceTeardown { _, error -> failures += error }

        teardown.step("api_level_mistake") { throw NoSuchMethodError("Api29Thing.newMethod") }

        assertEquals(1, failures.size)
        assertTrue("the Error must be reported, not converted", failures.single() is NoSuchMethodError)
    }

    /** A reporter that throws must not become the crash it was reporting. */
    @Test
    fun `a failing failure reporter does not escape either`() {
        val teardown = ServiceTeardown { _, _ -> error("the logger is broken too") }

        teardown.step("boom") { error("the step is broken") }
    }

    /** The ordinary case: a step that works reports nothing. */
    @Test
    fun `a step that succeeds is not reported as a failure`() {
        var reports = 0
        val teardown = ServiceTeardown { _, _ -> reports++ }

        teardown.step("fine") { /* no-op */ }

        assertEquals(0, reports)
    }
}
