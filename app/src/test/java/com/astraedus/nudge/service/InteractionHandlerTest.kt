package com.astraedus.nudge.service

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.domain.logging.NudgeLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InteractionHandlerTest {

    private lateinit var tracker: InteractionTracker
    private lateinit var overlayManager: FakeCounterOverlayManager
    private lateinit var inAppDetector: FakeInAppDetector
    private lateinit var timeRemainingHandler: FakeTimeRemainingHandler
    private lateinit var counterCache: CounterCacheRefresher
    private lateinit var handler: InteractionHandler
    private val capturedIntents = mutableListOf<Intent>()

    @Before
    fun setUp() {
        tracker = InteractionTracker()
        overlayManager = FakeCounterOverlayManager()
        inAppDetector = FakeInAppDetector()
        timeRemainingHandler = FakeTimeRemainingHandler()
        counterCache = CounterCacheRefresher()
        capturedIntents.clear()

        handler = InteractionHandler(
            interactionTracker = tracker,
            counterOverlayManager = overlayManager,
            inAppDetector = inAppDetector,
            timeRemainingHandler = timeRemainingHandler,
            counterCache = counterCache,
            logger = NudgeLog.NoOp,
            startActivity = { capturedIntents.add(it) }
        )
    }

    private fun enablePackage(
        packageName: String,
        autoKickAfter: Int? = null,
        showTimeRemaining: Boolean = false,
        dailyLimitMinutes: Int? = null,
        autoKickCooldownSeconds: Int = 60
    ) {
        val entry = CounterCacheEntry(
            autoKickAfter = autoKickAfter,
            showTimeRemaining = showTimeRemaining,
            dailyLimitMinutes = dailyLimitMinutes,
            autoKickCooldownSeconds = autoKickCooldownSeconds
        )
        enablePackages(mapOf(packageName to entry))
    }

    private fun enablePackages(packages: Map<String, CounterCacheEntry>) {
        kotlinx.coroutines.runBlocking {
            counterCache.forceRefresh { packages }
        }
    }

    // --- handleViewClicked tests ---

    @Test
    fun `handleViewClicked increments session count for non-supported packages`() {
        enablePackage("com.example.notes")

        handler.handleViewClicked("com.example.notes")

        assertEquals(1, tracker.getSessionCount("com.example.notes"))
        assertEquals(1, overlayManager.lastSessionCount)
        assertEquals("taps", overlayManager.lastShowLabel)
    }

    @Test
    fun `handleViewClicked skips supported packages - Instagram`() {
        enablePackage("com.instagram.android")

        handler.handleViewClicked("com.instagram.android")

        assertEquals(0, tracker.getSessionCount("com.instagram.android"))
        assertNull(overlayManager.lastShowLabel)
    }

    @Test
    fun `handleViewClicked skips supported packages - YouTube`() {
        enablePackage("com.google.android.youtube")

        handler.handleViewClicked("com.google.android.youtube")

        assertEquals(0, tracker.getSessionCount("com.google.android.youtube"))
    }

    @Test
    fun `handleViewClicked skips supported packages - TikTok`() {
        enablePackage("com.zhiliaoapp.musically")

        handler.handleViewClicked("com.zhiliaoapp.musically")

        assertEquals(0, tracker.getSessionCount("com.zhiliaoapp.musically"))
    }

    @Test
    fun `handleViewClicked respects debounce - rapid clicks ignored`() {
        enablePackage("com.example.notes")

        // First click goes through
        handler.handleViewClicked("com.example.notes")
        // Second click immediately after (within 300ms debounce) -- ignored
        handler.handleViewClicked("com.example.notes")

        assertEquals(1, tracker.getSessionCount("com.example.notes"))
        assertEquals(1, overlayManager.lastSessionCount)
    }

    @Test
    fun `handleViewClicked does nothing when package not in cache`() {
        handler.handleViewClicked("com.example.notes")

        assertEquals(0, tracker.getSessionCount("com.example.notes"))
        assertNull(overlayManager.lastShowLabel)
    }

    @Test
    fun `handleViewClicked shows overlay on first interaction`() {
        enablePackage("com.example.notes")

        handler.handleViewClicked("com.example.notes")

        assertTrue(overlayManager.visible)
        assertEquals("taps", overlayManager.lastShowLabel)
    }

    @Test
    fun `handleViewClicked calls timeRemainingHandler maybeUpdate`() {
        enablePackage("com.example.notes")

        handler.handleViewClicked("com.example.notes")

        assertEquals("com.example.notes", timeRemainingHandler.lastMaybeUpdatePackage)
    }

    // --- handleViewScrolled tests ---

    @Test
    fun `handleViewScrolled increments count for supported packages with detected feature`() {
        enablePackage("com.instagram.android")
        // Pre-set activeReelLabel to skip AccessibilityNodeInfo detection in JVM tests
        handler.activeReelLabel = "reels"

        handler.handleViewScrolled("com.instagram.android") { null }

        assertEquals(1, tracker.getSessionCount("com.instagram.android"))
        assertEquals("reels", overlayManager.lastShowLabel)
    }

    @Test
    fun `handleViewScrolled skips non-supported packages`() {
        enablePackage("com.example.notes")

        handler.handleViewScrolled("com.example.notes") { null }

        assertEquals(0, tracker.getSessionCount("com.example.notes"))
        assertNull(overlayManager.lastShowLabel)
    }

    @Test
    fun `handleViewScrolled does nothing when no feature detected`() {
        enablePackage("com.instagram.android")
        inAppDetector.featureToReturn = null

        handler.handleViewScrolled("com.instagram.android") { null }

        assertEquals(0, tracker.getSessionCount("com.instagram.android"))
    }

    @Test
    fun `handleViewScrolled returns early for EXPLORE feature`() {
        enablePackage("com.instagram.android")
        inAppDetector.featureToReturn = InAppDetector.Feature.EXPLORE

        handler.handleViewScrolled("com.instagram.android") { null }

        assertEquals(0, tracker.getSessionCount("com.instagram.android"))
    }

    @Test
    fun `handleViewScrolled detects YouTube Shorts via cached label`() {
        enablePackage("com.google.android.youtube")
        handler.activeReelLabel = "shorts"

        handler.handleViewScrolled("com.google.android.youtube") { null }

        assertEquals(1, tracker.getSessionCount("com.google.android.youtube"))
        assertEquals("shorts", overlayManager.lastShowLabel)
    }

    @Test
    fun `handleViewScrolled detects TikTok feed via cached label`() {
        enablePackage("com.zhiliaoapp.musically")
        handler.activeReelLabel = "videos"

        handler.handleViewScrolled("com.zhiliaoapp.musically") { null }

        assertEquals(1, tracker.getSessionCount("com.zhiliaoapp.musically"))
        assertEquals("videos", overlayManager.lastShowLabel)
    }

    @Test
    fun `activeReelLabel caches feature label and skips detection on subsequent scrolls`() {
        enablePackage("com.instagram.android")

        // Simulate that feature was previously detected by setting the cache
        handler.activeReelLabel = "reels"

        // Scroll should use cached label without calling detectFeature
        handler.handleViewScrolled("com.instagram.android") { null }

        assertEquals("reels", handler.activeReelLabel)
        assertEquals(1, tracker.getSessionCount("com.instagram.android"))
        // Detector was never called (rootNodeProvider returns null but label was cached)
        assertEquals(0, inAppDetector.detectCallCount)
    }

    @Test
    fun `handleViewScrolled does nothing when package not in cache`() {
        handler.handleViewScrolled("com.instagram.android") { null }

        assertEquals(0, tracker.getSessionCount("com.instagram.android"))
    }

    // --- onAppChanged tests ---

    @Test
    fun `onAppChanged resets interaction tracker state`() {
        enablePackage("com.example.notes")
        handler.handleViewClicked("com.example.notes")
        assertEquals(1, tracker.getSessionCount("com.example.notes"))

        handler.onAppChanged("com.example.other")

        assertEquals(0, tracker.getSessionCount("com.example.other"))
    }

    // --- hideCounter tests ---

    @Test
    fun `hideCounter hides overlay when visible`() {
        enablePackage("com.example.notes")
        handler.handleViewClicked("com.example.notes")
        assertTrue(overlayManager.visible)

        handler.hideCounter()

        assertFalse(overlayManager.visible)
    }

    @Test
    fun `hideCounter does nothing when overlay not visible`() {
        handler.hideCounter()
        assertFalse(overlayManager.visible)
    }

    // --- Test doubles ---

    private class FakeCounterOverlayManager : CounterOverlayManagerApi {
        var visible = false
        var lastShowLabel: String? = null
        var lastSessionCount: Int = 0
        var lastDailyTotal: Int = 0
        var hideCount = 0

        override fun isVisible(): Boolean = visible
        override fun show(label: String) {
            visible = true
            lastShowLabel = label
        }
        override fun updateCount(sessionCount: Int, dailyTotal: Int) {
            lastSessionCount = sessionCount
            lastDailyTotal = dailyTotal
        }
        override fun hide() {
            visible = false
            hideCount++
        }
    }

    private class FakeInAppDetector : InAppDetectorApi {
        var featureToReturn: InAppDetector.Feature? = null
        var detectCallCount: Int = 0

        override fun detectFeature(
            packageName: String,
            rootNode: AccessibilityNodeInfo?
        ): InAppDetector.Feature? {
            detectCallCount++
            return featureToReturn
        }
    }

    private class FakeTimeRemainingHandler : TimeRemainingHandlerApi {
        var lastMaybeUpdatePackage: String? = null
        var resetDebounceCalled = false
        var hideCalled = false

        override fun maybeUpdate(packageName: String) {
            lastMaybeUpdatePackage = packageName
        }
        override fun resetDebounce() { resetDebounceCalled = true }
        override fun hide() { hideCalled = true }
    }
}
