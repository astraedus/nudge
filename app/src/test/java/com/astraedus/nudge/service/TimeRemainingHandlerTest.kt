package com.astraedus.nudge.service

import com.astraedus.nudge.MainDispatcherRule
import com.astraedus.nudge.domain.logging.NudgeLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimeRemainingHandlerTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private lateinit var overlayManager: FakeOverlay
    private lateinit var usageProvider: FakeUsageProvider
    private lateinit var globalEnabledProvider: FakeGlobalEnabledProvider
    private lateinit var counterCache: CounterCacheRefresher
    private lateinit var passthroughManager: PassthroughManager
    private lateinit var testScope: TestScope
    private lateinit var handler: TimeRemainingHandler
    private val timeLimitExceededCalls = mutableListOf<Pair<String, Int>>()

    @Before
    fun setUp() {
        overlayManager = FakeOverlay()
        usageProvider = FakeUsageProvider()
        globalEnabledProvider = FakeGlobalEnabledProvider()
        counterCache = CounterCacheRefresher()
        passthroughManager = PassthroughManager()
        testScope = TestScope(testDispatcher)
        timeLimitExceededCalls.clear()

        handler = TimeRemainingHandler(
            timeRemainingOverlayManager = overlayManager,
            usageRepository = usageProvider,
            preferences = globalEnabledProvider,
            counterCache = counterCache,
            passthroughManager = passthroughManager,
            logger = NudgeLog.NoOp,
            serviceScope = testScope,
            onTimeLimitExceeded = { pkg, limit -> timeLimitExceededCalls.add(pkg to limit) }
        )
    }

    private fun enablePackage(
        packageName: String,
        showTimeRemaining: Boolean = true,
        dailyLimitMinutes: Int? = 60
    ) {
        kotlinx.coroutines.runBlocking {
            counterCache.forceRefresh {
                mapOf(
                    packageName to CounterCacheEntry(
                        showTimeRemaining = showTimeRemaining,
                        dailyLimitMinutes = dailyLimitMinutes
                    )
                )
            }
        }
    }

    // --- showIfNeeded ---

    @Test
    fun `showIfNeeded shows overlay when cache entry has showTimeRemaining and dailyLimit`() {
        enablePackage("com.example.app", showTimeRemaining = true, dailyLimitMinutes = 60)
        globalEnabledProvider.enabled = true

        handler.showIfNeeded("com.example.app")
        testScope.advanceUntilIdle()

        assertTrue(overlayManager.visible)
    }

    @Test
    fun `showIfNeeded does nothing when cache entry missing`() {
        handler.showIfNeeded("com.example.app")
        testScope.advanceUntilIdle()

        assertFalse(overlayManager.visible)
    }

    @Test
    fun `showIfNeeded does nothing when showTimeRemaining is false`() {
        enablePackage("com.example.app", showTimeRemaining = false, dailyLimitMinutes = 60)

        handler.showIfNeeded("com.example.app")
        testScope.advanceUntilIdle()

        assertFalse(overlayManager.visible)
    }

    @Test
    fun `showIfNeeded does nothing when dailyLimitMinutes is null`() {
        enablePackage("com.example.app", showTimeRemaining = true, dailyLimitMinutes = null)

        handler.showIfNeeded("com.example.app")
        testScope.advanceUntilIdle()

        assertFalse(overlayManager.visible)
    }

    @Test
    fun `showIfNeeded does nothing when global is disabled`() {
        enablePackage("com.example.app", showTimeRemaining = true, dailyLimitMinutes = 60)
        globalEnabledProvider.enabled = false

        handler.showIfNeeded("com.example.app")
        testScope.advanceUntilIdle()

        assertFalse(overlayManager.visible)
    }

    // --- maybeUpdate ---

    @Test
    fun `maybeUpdate respects 30s debounce`() {
        enablePackage("com.example.app", showTimeRemaining = true, dailyLimitMinutes = 60)
        usageProvider.foregroundTimeMs = 10 * 60 * 1000L // 10 min used

        // First call goes through
        handler.maybeUpdate("com.example.app")
        testScope.advanceUntilIdle()
        assertEquals(1, overlayManager.updateCount)

        // Second call immediately after -- debounced
        usageProvider.foregroundTimeMs = 20 * 60 * 1000L
        handler.maybeUpdate("com.example.app")
        testScope.advanceUntilIdle()
        assertEquals(1, overlayManager.updateCount) // unchanged
    }

    @Test
    fun `maybeUpdate does nothing when package not in cache`() {
        handler.maybeUpdate("com.example.nonexistent")
        testScope.advanceUntilIdle()

        assertEquals(0, overlayManager.updateCount)
    }

    @Test
    fun `maybeUpdate hides overlay when showTimeRemaining is false`() {
        enablePackage("com.example.app", showTimeRemaining = false, dailyLimitMinutes = 60)

        handler.maybeUpdate("com.example.app")
        testScope.advanceUntilIdle()

        assertTrue(overlayManager.updateCalledWithNulls)
    }

    @Test
    fun `maybeUpdate hides overlay when dailyLimitMinutes is null`() {
        enablePackage("com.example.app", showTimeRemaining = true, dailyLimitMinutes = null)

        handler.maybeUpdate("com.example.app")
        testScope.advanceUntilIdle()

        assertTrue(overlayManager.updateCalledWithNulls)
    }

    @Test
    fun `maybeUpdate calculates remaining time correctly`() {
        enablePackage("com.example.app", showTimeRemaining = true, dailyLimitMinutes = 60)
        usageProvider.foregroundTimeMs = 18 * 60 * 1000L // 18 min used -> 42 min remaining

        handler.maybeUpdate("com.example.app")
        testScope.advanceUntilIdle()

        val expected = 42L * 60L * 1000L
        assertEquals(expected, overlayManager.lastRemainingMs)
        assertEquals(60, overlayManager.lastLimitMinutes)
    }

    @Test
    fun `maybeUpdate triggers time limit exceeded when usage exceeds limit`() {
        enablePackage("com.example.app", showTimeRemaining = true, dailyLimitMinutes = 30)
        usageProvider.foregroundTimeMs = 31 * 60 * 1000L // 31 min used, limit 30

        handler.maybeUpdate("com.example.app")
        testScope.advanceUntilIdle()

        assertEquals(1, timeLimitExceededCalls.size)
        assertEquals("com.example.app" to 30, timeLimitExceededCalls[0])
        assertFalse(overlayManager.visible)
    }

    // --- maybeUpdate: a PLAIN daily limit (no readout) is still enforced, v1.18.4 ---
    //
    // The bug these rows pin: the readout's condition and the ENFORCEMENT's condition were the same
    // condition. `showTimeRemaining && dailyLimitMinutes != null` gated both, so a rule that only
    // set a budget was re-read by nothing on the clock, and the block landed on the user's next app
    // entry instead of when their budget ran out. Measured on the bench: 150s of continuous
    // foreground time on a 1-minute budget, one evaluation, no block.

    @Test
    fun `maybeUpdate hard-blocks a plain daily limit with no readout`() {
        enablePackage("com.example.app", showTimeRemaining = false, dailyLimitMinutes = 30)
        usageProvider.foregroundTimeMs = 31 * 60 * 1000L

        handler.maybeUpdate("com.example.app")
        testScope.advanceUntilIdle()

        assertEquals(1, timeLimitExceededCalls.size)
        assertEquals("com.example.app" to 30, timeLimitExceededCalls[0])
    }

    /**
     * The counterfactual for the row above: enforcing without a readout must not START drawing one.
     *
     * The whole reason the two conditions were entangled is that this class is named after the
     * overlay. If enforcement pulled the overlay along with it, every user who set a budget would
     * get a floating number they never asked for.
     */
    @Test
    fun `enforcing a plain daily limit draws no readout`() {
        enablePackage("com.example.app", showTimeRemaining = false, dailyLimitMinutes = 30)
        usageProvider.foregroundTimeMs = 31 * 60 * 1000L

        handler.maybeUpdate("com.example.app")
        testScope.advanceUntilIdle()

        assertEquals(1, timeLimitExceededCalls.size)
        assertEquals("the readout must never be drawn for a rule that did not ask for it", 0, overlayManager.updateCount)
        assertFalse(overlayManager.visible)
    }

    @Test
    fun `a plain daily limit with budget left blocks nothing`() {
        enablePackage("com.example.app", showTimeRemaining = false, dailyLimitMinutes = 60)
        usageProvider.foregroundTimeMs = 18 * 60 * 1000L

        handler.maybeUpdate("com.example.app")
        testScope.advanceUntilIdle()

        assertTrue(timeLimitExceededCalls.isEmpty())
        assertEquals(0, overlayManager.updateCount)
    }

    @Test
    fun `no daily limit is never enforced, readout or not`() {
        // The clock can reach this for a package tracked only for a time-based auto-kick. Reading a
        // budget that does not exist must not invent one.
        listOf(true, false).forEach { showTimeRemaining ->
            timeLimitExceededCalls.clear()
            handler.resetDebounce()
            enablePackage("com.example.app", showTimeRemaining, dailyLimitMinutes = null)
            usageProvider.foregroundTimeMs = 99 * 60 * 1000L

            handler.maybeUpdate("com.example.app")
            testScope.advanceUntilIdle()

            assertTrue(
                "showTimeRemaining=$showTimeRemaining with no limit must enforce nothing",
                timeLimitExceededCalls.isEmpty()
            )
        }
    }

    /**
     * THE GRANT, and it is the half a "completed delay" would otherwise defeat.
     *
     * A finished delay / hold / breathing grants passthrough, and passthrough suppresses the EVENT
     * path (`shouldSkipForegroundEvaluation`). Nothing on the clock path consults the grant -- so
     * the budget is enforced through it, which is the behaviour -- but the grant must also be DROPPED
     * as the block goes up, or the user walks straight back into the app they were just removed
     * from and the block is cosmetic.
     */
    @Test
    fun `a completed delay grant does not let a user outlive their budget`() {
        enablePackage("com.example.app", showTimeRemaining = false, dailyLimitMinutes = 30)
        usageProvider.foregroundTimeMs = 31 * 60 * 1000L
        passthroughManager.grant("com.example.app")
        assertTrue(
            "precondition: the grant really is suppressing the event path",
            passthroughManager.shouldSkipForegroundEvaluation("com.example.app")
        )

        handler.maybeUpdate("com.example.app")
        testScope.advanceUntilIdle()

        assertEquals("the tick-path block is not suppressed by an active grant", 1, timeLimitExceededCalls.size)
        assertFalse(
            "the grant must be gone, or the user re-enters the app the block just took them out of",
            passthroughManager.shouldSkipForegroundEvaluation("com.example.app")
        )
    }

    // --- resetDebounce ---

    @Test
    fun `resetDebounce clears the debounce timer`() {
        enablePackage("com.example.app", showTimeRemaining = true, dailyLimitMinutes = 60)
        usageProvider.foregroundTimeMs = 10 * 60 * 1000L

        // First call
        handler.maybeUpdate("com.example.app")
        testScope.advanceUntilIdle()
        assertEquals(1, overlayManager.updateCount)

        // Debounced
        handler.maybeUpdate("com.example.app")
        testScope.advanceUntilIdle()
        assertEquals(1, overlayManager.updateCount)

        // Reset debounce
        handler.resetDebounce()

        // Now goes through
        handler.maybeUpdate("com.example.app")
        testScope.advanceUntilIdle()
        assertEquals(2, overlayManager.updateCount)
    }

    // --- hide ---

    @Test
    fun `hide delegates to overlay manager`() {
        overlayManager.visible = true
        handler.hide()
        assertFalse(overlayManager.visible)
    }

    // --- Test doubles ---

    private class FakeOverlay : TimeRemainingOverlayManagerApi {
        var visible = false
        var lastRemainingMs: Long? = null
        var lastLimitMinutes: Int? = null
        var updateCalledWithNulls = false
        var updateCount = 0

        override fun show() { visible = true }
        override fun hide() { visible = false }
        override fun isVisible(): Boolean = visible
        override fun updateTimeRemaining(remainingMs: Long?, limitMinutes: Int?) {
            if (remainingMs == null || limitMinutes == null || limitMinutes <= 0) {
                updateCalledWithNulls = true
                hide()
                return
            }
            updateCount++
            lastRemainingMs = remainingMs
            lastLimitMinutes = limitMinutes
        }
    }

    private class FakeUsageProvider : UsageProvider {
        var foregroundTimeMs: Long = 0L
        override fun getDailyForegroundTimeMs(packageName: String): Long = foregroundTimeMs
    }

    private class FakeGlobalEnabledProvider : GlobalEnabledProvider {
        var enabled = true
        override val isGlobalEnabled: Flow<Boolean>
            get() = MutableStateFlow(enabled)
    }
}
