package com.astraedus.nudge.util

import android.util.Log
import com.astraedus.nudge.testing.FakeNudgePreferences
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * #120: release builds logged every line under `ResultKt` because the tag was read off the call
 * stack and R8 merges classes. The invariant: a tag comes from a string literal handed to
 * `tagged()`, or is the fixed fallback, and NEVER from whichever class happens to be on the stack.
 * (The JVM test cannot run R8, so it pins the mechanism: the caller class here is
 * `NudgeLoggerTagTest`, and it must not appear.)
 */
class NudgeLoggerTagTest {

    private val logger = NudgeLogger(
        FakeNudgePreferences { setDebugLoggingEnabled(true) }
    )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `tagged view logs under its literal tag at every level`() {
        val log = logger.tagged("TabCoverOverlay")
        log.d("a")
        log.i("b")
        log.w("c")
        log.e("d")
        verify(exactly = 1) { Log.d("TabCoverOverlay", "a") }
        verify(exactly = 1) { Log.i("TabCoverOverlay", "b") }
        verify(exactly = 1) { Log.w("TabCoverOverlay", "c") }
        verify(exactly = 1) { Log.e("TabCoverOverlay", "d") }
    }

    @Test
    fun `untagged call uses the fixed fallback, not the calling class`() {
        logger.i("hello")
        verify(exactly = 1) { Log.i(NudgeLogger.DEFAULT_TAG, "hello") }
        verify(exactly = 0) { Log.i("NudgeLoggerTagTest", any<String>()) }
    }

    @Test
    fun `re-tagging a tagged view replaces the tag instead of stacking`() {
        logger.tagged("A").tagged("B").i("x")
        verify(exactly = 1) { Log.i("B", "x") }
        verify(exactly = 0) { Log.i("A", any<String>()) }
    }

    @Test
    fun `explicit tag parameter still wins`() {
        logger.i("trace", tag = "NudgeA11yTrace")
        verify(exactly = 1) { Log.i("NudgeA11yTrace", "trace") }
    }
}
