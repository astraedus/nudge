package com.astraedus.nudge.service

import com.astraedus.nudge.domain.logging.NudgeLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The usage reminder ([#77](https://github.com/astraedus/nudge/issues/77)) through
 * [AutoKickTimeHandler.tick], against the real [InteractionTracker] and [CounterCacheRefresher] with
 * only the clocks faked: the same harness as `AutoKickTimeHandlerTest`, because the reminder's whole
 * promise is that it lives on the kick's session clock and resets exactly when the kick's does.
 */
class UseReminderTickTest {

    private val pkg = "com.example.video"
    private val other = "com.example.other"

    private lateinit var tracker: InteractionTracker
    private lateinit var counterCache: CounterCacheRefresher
    private lateinit var usage: FakeUsageProvider
    private lateinit var handler: AutoKickTimeHandler
    private var fakeTime = 1_000_000L

    @Before
    fun setUp() {
        fakeTime = 1_000_000L
        tracker = InteractionTracker()
        tracker.clock = { fakeTime }
        counterCache = CounterCacheRefresher()
        usage = FakeUsageProvider()
        handler = AutoKickTimeHandler(
            counterCache = counterCache,
            interactionTracker = tracker,
            usageProvider = usage,
            logger = NudgeLog.NoOp
        )
    }

    private fun cache(vararg entries: Pair<String, CounterCacheEntry>) {
        runBlocking { counterCache.forceRefresh { CounterCacheRefresher.mergeEntries(entries.toList()) } }
    }

    private fun cache(entry: CounterCacheEntry) = cache(pkg to entry)

    private fun minutes(n: Long): Long = n * 60_000L

    /** What the service does on entry: `onAppChanged` for the package now in front. */
    private fun enter(packageName: String = pkg) = tracker.onAppChanged(packageName)

    /** One tick at foreground reading [usageMinutes]. */
    private fun tickAt(usageMinutes: Long): AutoKickTimeHandler.Verdict {
        usage.foregroundMs = minutes(usageMinutes)
        return handler.tick(pkg)
    }

    private val none = AutoKickTimeHandler.Verdict.None
    private val kick = AutoKickTimeHandler.Verdict.Kick
    private fun remind(n: Int) = AutoKickTimeHandler.Verdict.Remind(n)

    // ── fires once at N, not before ──

    @Test
    fun `a reminder-only rule establishes the session baseline on its own`() {
        // No kick configured: before #77 nothing would ever have set a baseline for this package.
        cache(CounterCacheEntry(remindAfterMinutes = 20))
        enter()

        assertEquals(none, tickAt(300))
        assertEquals(minutes(300), tracker.getSessionUsageBaseline(pkg))
    }

    @Test
    fun `fires at N and not one tick before`() {
        cache(CounterCacheEntry(remindAfterMinutes = 20))
        enter()
        tickAt(100)

        assertEquals(none, tickAt(119))
        assertEquals(remind(20), tickAt(120))
    }

    @Test
    fun `a daily total is never mistaken for session time`() {
        // Five hours in this app earlier today must not fire the reminder the instant it opens.
        cache(CounterCacheEntry(remindAfterMinutes = 1))
        enter()

        assertEquals(none, tickAt(300))
    }

    @Test
    fun `the reminder does not end the session or the clock`() {
        // It only tells: no session reset, so the kick (and the next reminder) still measure from
        // the same baseline.
        cache(CounterCacheEntry(remindAfterMinutes = 10, autoKickAfterMinutes = 30))
        enter()
        tickAt(0)
        assertEquals(remind(10), tickAt(10))

        assertEquals(minutes(0), tracker.getSessionUsageBaseline(pkg))
        assertEquals(none, tickAt(29))
        assertEquals(kick, tickAt(30))
    }

    // ── one per session ──

    @Test
    fun `does not fire again in the same session`() {
        cache(CounterCacheEntry(remindAfterMinutes = 20))
        enter()
        tickAt(0)
        assertEquals(remind(20), tickAt(20))

        assertEquals(none, tickAt(21))
        assertEquals(none, tickAt(45))
        assertEquals(none, tickAt(600))
    }

    @Test
    fun `a quick trip to another app is the same session, so no second reminder`() {
        cache(CounterCacheEntry(remindAfterMinutes = 20))
        enter()
        tickAt(0)
        assertEquals(remind(20), tickAt(20))

        enter(other)
        fakeTime += minutes(2) // inside SESSION_EXPIRY_MS
        enter()

        assertEquals(none, tickAt(50))
    }

    @Test
    fun `a quick trip before N does not restart the countdown`() {
        // The bypass the kick already closes, closed for the reminder too: 19 minutes, tab out,
        // tab back, and the 20th minute still reminds.
        cache(CounterCacheEntry(remindAfterMinutes = 20))
        enter()
        tickAt(0)
        tickAt(19)

        enter(other)
        fakeTime += 10_000L
        enter()

        assertEquals(remind(20), tickAt(20))
    }

    // ── resets on a new session ──

    @Test
    fun `a genuine break starts a new session with its own reminder`() {
        cache(CounterCacheEntry(remindAfterMinutes = 20))
        enter()
        tickAt(0)
        assertEquals(remind(20), tickAt(20))

        enter(other)
        fakeTime += InteractionTracker.SESSION_EXPIRY_MS + 1
        enter()
        assertFalse(tracker.isSessionReminded(pkg))

        assertEquals(none, tickAt(25)) // new baseline
        assertEquals(none, tickAt(44))
        assertEquals(remind(20), tickAt(45))
    }

    @Test
    fun `the reminder flag and the baseline reset in the same place`() {
        // One definition of "session" for the reminder and both kicks.
        cache(CounterCacheEntry(remindAfterMinutes = 5))
        enter()
        tickAt(0)
        tickAt(5)

        enter(other)
        fakeTime += InteractionTracker.SESSION_EXPIRY_MS + 1
        enter()

        assertNull(tracker.getSessionUsageBaseline(pkg))
        assertFalse(tracker.isSessionReminded(pkg))
    }

    @Test
    fun `resetSession after a kick re-arms the reminder for the next session`() {
        cache(CounterCacheEntry(remindAfterMinutes = 10, autoKickAfterMinutes = 30))
        enter()
        tickAt(0)
        assertEquals(remind(10), tickAt(10))
        assertEquals(kick, tickAt(30))

        // What AutoKickExecutor does immediately after a kick.
        tracker.resetSession(pkg)
        enter()

        assertEquals(none, tickAt(31))
        assertEquals(remind(10), tickAt(41))
    }

    // ── the kick wins ──

    @Test
    fun `a reminder at the same minute as the kick never fires`() {
        cache(CounterCacheEntry(remindAfterMinutes = 20, autoKickAfterMinutes = 20))
        enter()
        tickAt(0)

        assertEquals(none, tickAt(19))
        assertEquals(kick, tickAt(20))
        assertFalse(tracker.isSessionReminded(pkg))
    }

    @Test
    fun `a reminder after the kick never fires`() {
        cache(CounterCacheEntry(remindAfterMinutes = 30, autoKickAfterMinutes = 20))
        enter()
        tickAt(0)

        assertEquals(kick, tickAt(20))
        tracker.resetSession(pkg)
        enter()
        tickAt(20)
        // Every session ends in the kick before the reminder's minute can come round; the pure
        // SUPERSEDED_BY_KICK branch itself is pinned in UseReminderEvaluatorTest.
        assertEquals(kick, tickAt(50))
        assertFalse(tracker.isSessionReminded(pkg))
    }

    @Test
    fun `a kick and a reminder due on the same tick yield only the kick`() {
        // reminder 10, kick 15, and a long gap between ticks (e.g. a slow tick after doze): both
        // are due, the user is about to be sent home, a notification would be noise.
        cache(CounterCacheEntry(remindAfterMinutes = 10, autoKickAfterMinutes = 15))
        enter()
        tickAt(0)

        assertEquals(kick, tickAt(16))
        assertFalse(tracker.isSessionReminded(pkg))
    }

    @Test
    fun `a kick on one rule supersedes a reminder on another rule for the same app`() {
        // Precedence is decided on the MERGED entry, at read time.
        cache(
            pkg to CounterCacheEntry(remindAfterMinutes = 20),
            pkg to CounterCacheEntry(autoKickAfterMinutes = 15)
        )
        enter()
        tickAt(0)

        assertEquals(none, tickAt(14))
        assertEquals(kick, tickAt(15))
    }

    @Test
    fun `a disabled kick of zero minutes never suppresses a reminder`() {
        cache(CounterCacheEntry(remindAfterMinutes = 20, autoKickAfterMinutes = 0))
        enter()
        tickAt(0)

        assertEquals(remind(20), tickAt(20))
    }

    // ── bad readings, other paths ──

    @Test
    fun `a stored zero reminder is off, not remind-immediately`() {
        cache(CounterCacheEntry(remindAfterMinutes = 0))
        enter()

        assertEquals(none, tickAt(0))
        assertEquals(none, tickAt(600))
        assertNull(tracker.getSessionUsageBaseline(pkg))
    }

    @Test
    fun `a failing usage read never reminds`() {
        cache(CounterCacheEntry(remindAfterMinutes = 1))
        usage.throwOnRead = true

        assertEquals(none, handler.tick(pkg))
        assertFalse(tracker.isSessionReminded(pkg))
    }

    @Test
    fun `a backwards reading re-baselines instead of reminding`() {
        cache(CounterCacheEntry(remindAfterMinutes = 20))
        enter()
        tickAt(300)

        // Midnight inside the session.
        assertEquals(none, tickAt(1))
        assertEquals(minutes(1), tracker.getSessionUsageBaseline(pkg))
        assertEquals(remind(20), tickAt(21))
    }

    @Test
    fun `shouldKick, the web clock's entry point, never consumes a reminder`() {
        // If it did, a reminder due on the app path could be marked done by a call that cannot
        // post it -- a dropped message.
        cache(CounterCacheEntry(remindAfterMinutes = 20, autoKickAfterMinutes = 60))
        enter()
        usage.foregroundMs = 0L
        handler.shouldKick(pkg)
        usage.foregroundMs = minutes(25)

        assertFalse(handler.shouldKick(pkg))
        assertFalse(tracker.isSessionReminded(pkg))
        assertEquals(remind(20), handler.tick(pkg))
    }

    @Test
    fun `each package gets its own reminder`() {
        cache(
            pkg to CounterCacheEntry(remindAfterMinutes = 10),
            other to CounterCacheEntry(remindAfterMinutes = 10)
        )
        usage.perPackage[pkg] = 0L
        usage.perPackage[other] = 0L
        handler.tick(pkg)
        handler.tick(other)

        usage.perPackage[pkg] = minutes(10)
        usage.perPackage[other] = minutes(3)
        assertEquals(remind(10), handler.tick(pkg))
        assertEquals(none, handler.tick(other))

        usage.perPackage[other] = minutes(10)
        assertEquals(remind(10), handler.tick(other))
    }

    private class FakeUsageProvider : UsageProvider {
        var foregroundMs: Long = 0L
        var throwOnRead: Boolean = false
        val perPackage = mutableMapOf<String, Long>()

        override fun getDailyForegroundTimeMs(packageName: String): Long {
            if (throwOnRead) throw IllegalStateException("usage stats unavailable")
            return perPackage[packageName] ?: foregroundMs
        }
    }
}
