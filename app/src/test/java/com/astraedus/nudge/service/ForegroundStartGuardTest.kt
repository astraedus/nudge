package com.astraedus.nudge.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [tryStartForeground] is the catch around `NudgeMonitorService.onStartCommand`'s one
 * `startForeground` call. Without it, a platform refusal (Play vitals: 5 crashes on 1.31.3,
 * Android 14, `ForegroundServiceStartNotAllowedException` at `NudgeMonitorService.kt:261`) killed
 * the whole process, blocking included.
 */
class ForegroundStartGuardTest {

    /**
     * Stands in for `android.app.ForegroundServiceStartNotAllowedException`, which is API 31+ and
     * only a stub on the JVM. The real one is an `IllegalStateException` subclass too
     * (via `ServiceStartNotAllowedException`), which is the property the guard depends on.
     */
    private class FakeStartNotAllowed : IllegalStateException(
        "Service.startForeground() not allowed due to mAllowStartForeground false"
    )

    @Test
    fun `a refused startForeground is returned, not thrown`() {
        val refusal = FakeStartNotAllowed()
        val denied = tryStartForeground { throw refusal }
        assertSame(
            "the platform's refusal must come back to onStartCommand as a value; thrown, it " +
                "escapes handleServiceArgs and kills the process the accessibility service lives in",
            refusal,
            denied
        )
    }

    @Test
    fun `a plain IllegalStateException is treated as a refusal too`() {
        // minSdk is 26: below API 31 the platform subclass does not exist, so the guard has to
        // work on the parent type.
        val refusal = IllegalStateException("not allowed")
        assertSame(refusal, tryStartForeground { throw refusal })
    }

    @Test
    fun `an allowed startForeground runs once and reports no refusal`() {
        var calls = 0
        val denied = tryStartForeground { calls++ }
        assertNull("an allowed start must not read as refused", denied)
        assertEquals("the startForeground action must run exactly once", 1, calls)
    }

    @Test
    fun `any other exception still propagates`() {
        // A null notification or a bad id is a bug in our code, not the platform saying no;
        // swallowing it would turn a loud crash into a service that silently never shows.
        var thrown: Throwable? = null
        try {
            tryStartForeground { throw IllegalArgumentException("null notification") }
        } catch (e: IllegalArgumentException) {
            thrown = e
        }
        assertTrue("IllegalArgumentException must not be caught by the guard", thrown != null)
    }
}
