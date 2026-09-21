package com.astraedus.nudge.domain.block

import com.astraedus.nudge.domain.block.BlockLaunchGate.Arrival
import com.astraedus.nudge.domain.block.BlockLaunchGate.Decision
import com.astraedus.nudge.domain.block.BlockLaunchGate.LaunchStorm
import com.astraedus.nudge.domain.block.BlockLaunchGate.MAX_COUNTED_CONFRONTATIONS_PER_ARRIVAL
import com.astraedus.nudge.domain.block.BlockLaunchGate.NO_OVERLAY_ID
import com.astraedus.nudge.domain.block.BlockLaunchGate.PendingOverlay
import com.astraedus.nudge.domain.block.BlockLaunchGate.STORM_LAUNCH_THRESHOLD
import com.astraedus.nudge.domain.block.BlockLaunchGate.STORM_WINDOW_MS
import com.astraedus.nudge.domain.events.ForegroundSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BlockLaunchGateTest], in the same package, pins the SHOW decision: whether a computed block
 * decision is still about where the user is about to be. This file pins a different question --
 * the COUNT decision and the storm diagnostic added for
 * [#36](https://github.com/astraedus/nudge/issues/36) -- and the two are deliberately kept apart.
 *
 * Issue #36 was never about a decision being shown when it shouldn't be; every gate that
 * [BlockLaunchGateTest] pins was already correctly saying "show it". The 2500-interventions bug
 * was that showing an overlay and writing a `UsageEvent` row were the same event, so any
 * autonomous loop that re-launched an overlay -- with no user gesture at all -- also re-counted
 * it. The fix here does not change what gets shown; it answers "does this arrival already owe a
 * row for this confrontation" as its own, separate pure question, and separately diagnoses when a
 * loop is happening at all (the storm) so the next field report is legible instead of a wall of
 * 2500 identical rows.
 */
class ArrivalAndStormGateTest {

    private val insta = "com.instagram.android"
    private val keep = "com.google.android.keep"
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val nudge = "dev.astraedus.nudge"

    // ============================================================== confrontationKey ==========

    @Test
    fun `a whole-app confrontation and a feature confrontation on the same app are different things`() {
        val wholeApp = BlockLaunchGate.confrontationKey(insta)
        val feature = BlockLaunchGate.confrontationKey(insta, featureKey = "reels")
        assertTrue(
            "a feature confrontation must not collapse into the whole-app key",
            wholeApp != feature
        )
    }

    @Test
    fun `two different websites blocked in the same browser are different confrontations`() {
        val siteA = BlockLaunchGate.confrontationKey(insta, webDomain = "instagram.com")
        val siteB = BlockLaunchGate.confrontationKey(insta, webDomain = "twitter.com")
        assertTrue(
            "two distinct domains under the same attributed package must produce distinct keys",
            siteA != siteB
        )
    }

    @Test
    fun `a feature key and a web domain on the same app are also different confrontations`() {
        val feature = BlockLaunchGate.confrontationKey(insta, featureKey = "reels")
        val web = BlockLaunchGate.confrontationKey(insta, webDomain = "instagram.com")
        assertTrue(
            "a feature confrontation and a web-domain confrontation must not collide",
            feature != web
        )
    }

    @Test
    fun `a blank or null feature never invents a second confrontation`() {
        val bare = BlockLaunchGate.confrontationKey(insta)
        val nullFeature = BlockLaunchGate.confrontationKey(insta, featureKey = null)
        val blankFeature = BlockLaunchGate.confrontationKey(insta, featureKey = "  ")
        assertEquals(
            "a null feature key must be indistinguishable from no feature key at all",
            bare,
            nullFeature
        )
        assertEquals(
            "a blank feature key must be indistinguishable from no feature key at all",
            bare,
            blankFeature
        )
    }

    @Test
    fun `a blank or null web domain never invents a second confrontation`() {
        val bare = BlockLaunchGate.confrontationKey(insta)
        val nullDomain = BlockLaunchGate.confrontationKey(insta, webDomain = null)
        val blankDomain = BlockLaunchGate.confrontationKey(insta, webDomain = "   ")
        assertEquals(
            "a null web domain must be indistinguishable from no web domain at all",
            bare,
            nullDomain
        )
        assertEquals(
            "a blank web domain must be indistinguishable from no web domain at all",
            bare,
            blankDomain
        )
    }

    @Test
    fun `the same inputs always produce the same key`() {
        val first = BlockLaunchGate.confrontationKey(insta, featureKey = "reels", webDomain = "x.com")
        val second = BlockLaunchGate.confrontationKey(insta, featureKey = "reels", webDomain = "x.com")
        assertEquals("the key is a pure function of its inputs", first, second)
    }

    // ============================================================== isNewConfrontation ========

    @Test
    fun `arriving with no prior arrival at all is a new confrontation`() {
        assertTrue(
            "a null arrival means nothing has been counted yet, which is a fresh arrival",
            BlockLaunchGate.isNewConfrontation(arrival = null, target = insta, key = insta)
        )
    }

    @Test
    fun `arriving in a different app than the one the arrival tracked is a new confrontation`() {
        val arrival = Arrival(targetPackage = keep, countedKeys = listOf(keep))
        assertTrue(
            "the user cannot be mid-arrival in two apps at once, so a different target is fresh",
            BlockLaunchGate.isNewConfrontation(arrival, target = insta, key = insta)
        )
    }

    @Test
    fun `the same confrontation shown twice while the user stays put is not new`() {
        val key = BlockLaunchGate.confrontationKey(insta, featureKey = "reels")
        val arrival = Arrival(targetPackage = insta, countedKeys = listOf(key))
        assertFalse(
            "the same key already counted in this arrival must not be counted again",
            BlockLaunchGate.isNewConfrontation(arrival, target = insta, key = key)
        )
    }

    @Test
    fun `a different confrontation inside the same arrival is new`() {
        val wholeAppKey = BlockLaunchGate.confrontationKey(insta)
        val featureKey = BlockLaunchGate.confrontationKey(insta, featureKey = "reels")
        val arrival = Arrival(targetPackage = insta, countedKeys = listOf(wholeAppKey))
        assertTrue(
            "a whole-app rule and a feature rule alternating in one arrival must each be countable",
            BlockLaunchGate.isNewConfrontation(arrival, target = insta, key = featureKey)
        )
    }

    /**
     * [MAX_COUNTED_CONFRONTATIONS_PER_ARRIVAL] is a HARD CEILING enforced here, not merely a
     * memory budget for [arrivalAfterConfrontation]'s eviction. Remembering only the last N keys
     * would bound storage while leaving the number of rows unbounded, because an evicted key looks
     * new again the next time it recurs -- a loop that manufactured distinct keys would be back to
     * 2500 rows a day with a tidier data structure underneath it.
     */
    @Test
    fun `one arrival can never write more rows than the ceiling, whatever fires`() {
        val keys = (1..MAX_COUNTED_CONFRONTATIONS_PER_ARRIVAL).map {
            BlockLaunchGate.confrontationKey(insta, featureKey = "f$it")
        }
        val fullArrival = Arrival(targetPackage = insta, countedKeys = keys)
        val oneMore = BlockLaunchGate.confrontationKey(insta, featureKey = "one-more-entirely-new")
        assertFalse(
            "a further new key once the arrival already holds the ceiling's worth must be refused",
            BlockLaunchGate.isNewConfrontation(fullArrival, target = insta, key = oneMore)
        )
    }

    @Test
    fun `a key already counted in a full arrival is still correctly not new`() {
        val keys = (1..MAX_COUNTED_CONFRONTATIONS_PER_ARRIVAL).map {
            BlockLaunchGate.confrontationKey(insta, featureKey = "f$it")
        }
        val fullArrival = Arrival(targetPackage = insta, countedKeys = keys)
        assertFalse(
            "the ceiling refuses NEW keys; a key the arrival already counted is a repeat, not a refusal",
            BlockLaunchGate.isNewConfrontation(fullArrival, target = insta, key = keys.first())
        )
    }

    @Test
    fun `the ceiling is per-arrival, not permanent -- a fresh arrival counts again`() {
        val keys = (1..MAX_COUNTED_CONFRONTATIONS_PER_ARRIVAL).map {
            BlockLaunchGate.confrontationKey(insta, featureKey = "f$it")
        }
        val fullArrival = Arrival(targetPackage = insta, countedKeys = keys)
        val freshKey = BlockLaunchGate.confrontationKey(keep)
        assertTrue(
            "a different target means a different arrival, which starts the ceiling over",
            BlockLaunchGate.isNewConfrontation(fullArrival, target = keep, key = freshKey)
        )
    }

    // ============================================================== arrivalAfterConfrontation =

    @Test
    fun `counting a confrontation records its key on the arrival`() {
        val key = BlockLaunchGate.confrontationKey(insta)
        val arrival = BlockLaunchGate.arrivalAfterConfrontation(arrival = null, target = insta, key = key)
        assertEquals("the target must be recorded", insta, arrival.targetPackage)
        assertEquals(
            "the confrontation key must be recorded so a repeat is recognised",
            listOf(key),
            arrival.countedKeys
        )
    }

    @Test
    fun `switching target starts a fresh key list`() {
        val keepKey = BlockLaunchGate.confrontationKey(keep)
        val priorArrival = Arrival(targetPackage = keep, countedKeys = listOf(keepKey))
        val instaKey = BlockLaunchGate.confrontationKey(insta)
        val next = BlockLaunchGate.arrivalAfterConfrontation(priorArrival, target = insta, key = instaKey)
        assertEquals(
            "a confrontation for a new target must not inherit the previous target's keys",
            listOf(instaKey),
            next.countedKeys
        )
    }

    @Test
    fun `re-counting an existing key moves it to most-recent without duplicating it`() {
        val keyA = BlockLaunchGate.confrontationKey(insta, featureKey = "reels")
        val keyB = BlockLaunchGate.confrontationKey(insta, featureKey = "stories")
        var arrival = BlockLaunchGate.arrivalAfterConfrontation(null, insta, keyA)
        arrival = BlockLaunchGate.arrivalAfterConfrontation(arrival, insta, keyB)
        arrival = BlockLaunchGate.arrivalAfterConfrontation(arrival, insta, keyA)
        assertEquals(
            "re-counting keyA must not duplicate it, and must move it after keyB",
            listOf(keyB, keyA),
            arrival.countedKeys
        )
    }

    @Test
    fun `the counted-keys list never exceeds the cap and evicts oldest-first`() {
        var arrival: Arrival? = null
        val keys = (1..40).map { BlockLaunchGate.confrontationKey(insta, featureKey = "f$it") }
        for (key in keys) {
            arrival = BlockLaunchGate.arrivalAfterConfrontation(arrival, insta, key)
        }
        val result = requireNotNull(arrival)
        assertEquals(
            "the counted-keys list must be capped at MAX_COUNTED_CONFRONTATIONS_PER_ARRIVAL",
            MAX_COUNTED_CONFRONTATIONS_PER_ARRIVAL,
            result.countedKeys.size
        )
        assertTrue(
            "the most recently counted key (the 40th) must survive the eviction",
            keys.last() in result.countedKeys
        )
        assertFalse(
            "the oldest key (the 1st) must have been evicted once the cap was exceeded",
            keys.first() in result.countedKeys
        )
    }

    // ============================================================== arrivalAfterSignal: the ====
    // ============================================================== departure matrix ===========

    @Test
    fun `going home ends the arrival`() {
        val arrival = Arrival(insta, listOf(insta))
        assertNull(
            "Home is the one unambiguous 'this sitting is over' signal",
            BlockLaunchGate.arrivalAfterSignal(ForegroundSignal.Home(launcher), arrival)
        )
    }

    @Test
    fun `a different app coming to the foreground ends the arrival`() {
        val arrival = Arrival(insta, listOf(insta))
        assertNull(
            "a real window for a different app proves the user left the arrival's target",
            BlockLaunchGate.arrivalAfterSignal(ForegroundSignal.AppWindow(keep), arrival)
        )
    }

    @Test
    fun `the same app's own window does not end the arrival`() {
        val arrival = Arrival(insta, listOf(insta))
        assertEquals(
            "the user is still sitting in the same target, so the arrival must survive",
            arrival,
            BlockLaunchGate.arrivalAfterSignal(ForegroundSignal.AppWindow(insta), arrival)
        )
    }

    @Test
    fun `Nudge's own overlay in front does not end the arrival`() {
        val arrival = Arrival(insta, listOf(insta))
        assertEquals(
            "our own block overlay is on screen for every confrontation by construction; " +
                "treating it as a departure would re-open the arrival the fix exists to close",
            arrival,
            BlockLaunchGate.arrivalAfterSignal(ForegroundSignal.OwnUi(nudge), arrival)
        )
    }

    /**
     * ISSUE #41'S OTHER HALF. The counter and the time-remaining pill are drawn over the app the
     * user is still in, so they are the LEAST plausible departure there is: if one ended the
     * arrival, the very overlay that appears because the user is sitting in a blocked app would
     * make the next block a fresh confrontation and start the count climbing again, which is
     * issue #36's loop reached by a new road.
     */
    @Test
    fun `an awareness overlay does not end the arrival`() {
        val arrival = Arrival(insta, listOf(insta))
        assertEquals(
            "a counter drawn over the app the user never left cannot be them leaving it",
            arrival,
            BlockLaunchGate.arrivalAfterSignal(ForegroundSignal.AwarenessOverlay(nudge), arrival)
        )
    }

    @Test
    fun `a system surface does not end the arrival`() {
        val arrival = Arrival(insta, listOf(insta))
        assertEquals(
            "a notification shade or permission dialog carries no claim about where the user is",
            arrival,
            BlockLaunchGate.arrivalAfterSignal(ForegroundSignal.SystemSurface("com.google.android.permissioncontroller"), arrival)
        )
    }

    @Test
    fun `a transient window does not end the arrival`() {
        val arrival = Arrival(insta, listOf(insta))
        assertEquals(
            "a keyboard or framework popup is not the user leaving",
            arrival,
            BlockLaunchGate.arrivalAfterSignal(ForegroundSignal.Transient("android"), arrival)
        )
    }

    @Test
    fun `a picture-in-picture bubble does not end the arrival`() {
        val arrival = Arrival(insta, listOf(insta))
        assertEquals(
            "a PiP bubble fires while the user is elsewhere entirely and drives nothing",
            arrival,
            BlockLaunchGate.arrivalAfterSignal(ForegroundSignal.PipOnly(insta), arrival)
        )
    }

    @Test
    fun `a non-window event does not end the arrival`() {
        val arrival = Arrival(insta, listOf(insta))
        assertEquals(
            "a content change, click, or scroll carries no claim about what is in front",
            arrival,
            BlockLaunchGate.arrivalAfterSignal(ForegroundSignal.NotForeground(insta), arrival)
        )
    }

    @Test
    fun `a null arrival stays null for every signal kind`() {
        val signals = listOf(
            ForegroundSignal.Home(launcher),
            ForegroundSignal.AppWindow(insta),
            ForegroundSignal.OwnUi(nudge),
            ForegroundSignal.AwarenessOverlay(nudge),
            ForegroundSignal.SystemSurface("com.google.android.permissioncontroller"),
            ForegroundSignal.Transient("android"),
            ForegroundSignal.PipOnly(insta),
            ForegroundSignal.NotForeground(insta)
        )
        for (signal in signals) {
            assertNull(
                "with nothing arrived into, no signal ($signal) can produce an arrival out of thin air",
                BlockLaunchGate.arrivalAfterSignal(signal, arrival = null)
            )
        }
    }

    // ============================================================== stormAfterSignal: same =====
    // ============================================================== matrix, keyed on storm =====

    @Test
    fun `going home ends the storm`() {
        val storm = LaunchStorm(insta, windowStartedAtMs = 0, launches = 3, decisions = listOf("LAUNCH"), reported = false)
        assertNull(
            "the storm is keyed on the same departure evidence as the arrival",
            BlockLaunchGate.stormAfterSignal(ForegroundSignal.Home(launcher), storm)
        )
    }

    @Test
    fun `a different app in front ends the storm`() {
        val storm = LaunchStorm(insta, windowStartedAtMs = 0, launches = 3, decisions = listOf("LAUNCH"), reported = false)
        assertNull(
            "a genuine switch away from the storming target ends the run being counted",
            BlockLaunchGate.stormAfterSignal(ForegroundSignal.AppWindow(keep), storm)
        )
    }

    @Test
    fun `the storming app's own window does not end the storm`() {
        val storm = LaunchStorm(insta, windowStartedAtMs = 0, launches = 3, decisions = listOf("LAUNCH"), reported = false)
        assertEquals(
            "the user has not left the app the storm is about",
            storm,
            BlockLaunchGate.stormAfterSignal(ForegroundSignal.AppWindow(insta), storm)
        )
    }

    @Test
    fun `Nudge's own overlay does not end the storm`() {
        val storm = LaunchStorm(insta, windowStartedAtMs = 0, launches = 3, decisions = listOf("LAUNCH"), reported = false)
        assertEquals(
            "the overlay being on screen is not evidence the storming app was left",
            storm,
            BlockLaunchGate.stormAfterSignal(ForegroundSignal.OwnUi(nudge), storm)
        )
    }

    @Test
    fun `an awareness overlay does not end the storm`() {
        val storm = LaunchStorm(insta, windowStartedAtMs = 0, launches = 3, decisions = listOf("LAUNCH"), reported = false)
        assertEquals(
            "our own counter appearing over the storming app is not evidence it was left",
            storm,
            BlockLaunchGate.stormAfterSignal(ForegroundSignal.AwarenessOverlay(nudge), storm)
        )
    }

    @Test
    fun `a system surface does not end the storm`() {
        val storm = LaunchStorm(insta, windowStartedAtMs = 0, launches = 3, decisions = listOf("LAUNCH"), reported = false)
        assertEquals(
            storm,
            BlockLaunchGate.stormAfterSignal(ForegroundSignal.SystemSurface("com.google.android.permissioncontroller"), storm)
        )
    }

    @Test
    fun `a transient window does not end the storm`() {
        val storm = LaunchStorm(insta, windowStartedAtMs = 0, launches = 3, decisions = listOf("LAUNCH"), reported = false)
        assertEquals(storm, BlockLaunchGate.stormAfterSignal(ForegroundSignal.Transient("android"), storm))
    }

    @Test
    fun `a picture-in-picture bubble does not end the storm`() {
        val storm = LaunchStorm(insta, windowStartedAtMs = 0, launches = 3, decisions = listOf("LAUNCH"), reported = false)
        assertEquals(storm, BlockLaunchGate.stormAfterSignal(ForegroundSignal.PipOnly(insta), storm))
    }

    @Test
    fun `a non-window event does not end the storm`() {
        val storm = LaunchStorm(insta, windowStartedAtMs = 0, launches = 3, decisions = listOf("LAUNCH"), reported = false)
        assertEquals(storm, BlockLaunchGate.stormAfterSignal(ForegroundSignal.NotForeground(insta), storm))
    }

    @Test
    fun `a null storm stays null for every signal kind`() {
        val signals = listOf(
            ForegroundSignal.Home(launcher),
            ForegroundSignal.AppWindow(insta),
            ForegroundSignal.OwnUi(nudge),
            ForegroundSignal.AwarenessOverlay(nudge),
            ForegroundSignal.SystemSurface("com.google.android.permissioncontroller"),
            ForegroundSignal.Transient("android"),
            ForegroundSignal.PipOnly(insta),
            ForegroundSignal.NotForeground(insta)
        )
        for (signal in signals) {
            assertNull(
                "with no storm running, no signal ($signal) can start one",
                BlockLaunchGate.stormAfterSignal(signal, storm = null)
            )
        }
    }

    // ============================================================== stormAfterLaunch / =========
    // ============================================================== stormReport ================

    @Test
    fun `the first launch attempt for a target starts a window of one with no report`() {
        val storm = BlockLaunchGate.stormAfterLaunch(
            storm = null,
            target = insta,
            decision = Decision.LAUNCH,
            nowMs = 1_000
        )
        assertEquals("the first attempt is launches == 1", 1, storm.launches)
        assertEquals(insta, storm.targetPackage)
        assertNull(
            "one launch is nowhere near the storm threshold; no report is owed yet",
            BlockLaunchGate.stormReport(storm, nowMs = 1_000)
        )
    }

    @Test
    fun `five attempts inside the window produce exactly one report on the fifth`() {
        var storm: LaunchStorm? = null
        var report: BlockLaunchGate.StormReport? = null
        var now = 0L
        repeat(5) { i ->
            now = 1_000L * (i + 1)
            storm = BlockLaunchGate.stormAfterLaunch(storm, insta, Decision.LAUNCH, now)
            val candidate = BlockLaunchGate.stormReport(requireNotNull(storm), now)
            if (candidate != null) report = candidate
        }
        val finalStorm = requireNotNull(storm)
        assertEquals("five launch attempts must have accumulated", 5, finalStorm.launches)
        val finalReport = requireNotNull(report) {
            "a report must be produced exactly on the launch that reaches STORM_LAUNCH_THRESHOLD"
        }
        assertEquals(STORM_LAUNCH_THRESHOLD, finalReport.launches)
        assertEquals(insta, finalReport.targetPackage)
        assertEquals(
            "the report's window must span from the first attempt to the reporting attempt",
            now - finalStorm.windowStartedAtMs,
            finalReport.windowMs
        )
    }

    @Test
    fun `no report is produced before the fourth attempt`() {
        var storm: LaunchStorm? = null
        for (i in 1..4) {
            storm = BlockLaunchGate.stormAfterLaunch(storm, insta, Decision.LAUNCH, nowMs = 1_000L * i)
            assertNull(
                "attempt #$i must not yet cross STORM_LAUNCH_THRESHOLD ($STORM_LAUNCH_THRESHOLD)",
                BlockLaunchGate.stormReport(requireNotNull(storm), nowMs = 1_000L * i)
            )
        }
    }

    @Test
    fun `a report is not produced again once the storm has already been reported`() {
        var storm = LaunchStorm(
            targetPackage = insta,
            windowStartedAtMs = 0,
            launches = STORM_LAUNCH_THRESHOLD,
            decisions = listOf("LAUNCH"),
            reported = false
        )
        val firstReport = BlockLaunchGate.stormReport(storm, nowMs = 5_000)
        assertNotNull("the threshold-crossing attempt must produce a report", firstReport)

        // Simulate the guard marking it reported and continuing to launch.
        storm = storm.copy(reported = true)
        storm = BlockLaunchGate.stormAfterLaunch(storm, insta, Decision.LAUNCH, nowMs = 6_000)
        storm = BlockLaunchGate.stormAfterLaunch(storm, insta, Decision.LAUNCH, nowMs = 7_000)

        assertNull(
            "one line per storm, not one per event -- a reported storm must stay quiet",
            BlockLaunchGate.stormReport(storm, nowMs = 7_000)
        )
    }

    @Test
    fun `distinct decision names accumulate in order without duplicates`() {
        var storm: LaunchStorm? = null
        storm = BlockLaunchGate.stormAfterLaunch(storm, insta, Decision.LAUNCH, nowMs = 1_000)
        storm = BlockLaunchGate.stormAfterLaunch(storm, insta, Decision.DROP_ALREADY_PENDING, nowMs = 1_100)
        storm = BlockLaunchGate.stormAfterLaunch(storm, insta, Decision.LAUNCH, nowMs = 1_200)
        storm = BlockLaunchGate.stormAfterLaunch(storm, insta, Decision.DROP_FOREGROUND_MOVED, nowMs = 1_300)
        storm = BlockLaunchGate.stormAfterLaunch(storm, insta, Decision.DROP_ALREADY_PENDING, nowMs = 1_400)
        assertEquals(
            "each distinct decision name must appear once, in first-seen order",
            listOf(
                Decision.LAUNCH.name,
                Decision.DROP_ALREADY_PENDING.name,
                Decision.DROP_FOREGROUND_MOVED.name
            ),
            requireNotNull(storm).decisions
        )
    }

    @Test
    fun `a target change restarts the storm window at one`() {
        var storm: LaunchStorm? = null
        storm = BlockLaunchGate.stormAfterLaunch(storm, insta, Decision.LAUNCH, nowMs = 1_000)
        storm = BlockLaunchGate.stormAfterLaunch(storm, insta, Decision.LAUNCH, nowMs = 1_100)
        storm = BlockLaunchGate.stormAfterLaunch(storm, keep, Decision.LAUNCH, nowMs = 1_200)
        val result = requireNotNull(storm)
        assertEquals("a different target must not inherit the running count", keep, result.targetPackage)
        assertEquals(1, result.launches)
    }

    @Test
    fun `an attempt past the storm window restarts the count at one`() {
        var storm: LaunchStorm? = null
        storm = BlockLaunchGate.stormAfterLaunch(storm, insta, Decision.LAUNCH, nowMs = 0)
        storm = BlockLaunchGate.stormAfterLaunch(
            storm,
            insta,
            Decision.LAUNCH,
            nowMs = STORM_WINDOW_MS + 1
        )
        val result = requireNotNull(storm)
        assertEquals(
            "an attempt outside STORM_WINDOW_MS is a fresh run, not a continuation of the old one",
            1,
            result.launches
        )
        assertEquals(STORM_WINDOW_MS + 1, result.windowStartedAtMs)
    }

    // ============================================================== pendingOverlayAfterLaunch ==

    @Test
    fun `a re-launch for the same target while the overlay is already shown keeps windowShown true and its id`() {
        val shown = PendingOverlay(insta, launchedAtMs = 1_000, windowShown = true, id = 7L)
        val result = BlockLaunchGate.pendingOverlayAfterLaunch(shown, target = insta, nowMs = 4_000, id = 9L)
        assertTrue(
            "the overlay is genuinely on screen; resetting this starves isGenuineBypass forever",
            result.windowShown
        )
        assertEquals(
            "the live instance's id must be preserved, not replaced by the re-delivery's id",
            7L,
            result.id
        )
        assertEquals(insta, result.packageName)
    }

    @Test
    fun `a launch for a different target produces a fresh unseen pending overlay`() {
        val shown = PendingOverlay(insta, launchedAtMs = 1_000, windowShown = true, id = 7L)
        val result = BlockLaunchGate.pendingOverlayAfterLaunch(shown, target = keep, nowMs = 4_000, id = 9L)
        assertEquals(keep, result.packageName)
        assertFalse(
            "a new target's overlay has not been seen on screen yet",
            result.windowShown
        )
        assertEquals(9L, result.id)
        assertEquals(4_000L, result.launchedAtMs)
    }

    @Test
    fun `a launch for the same target whose overlay has not yet been seen produces a fresh pending`() {
        val unseen = PendingOverlay(insta, launchedAtMs = 1_000, windowShown = false, id = 7L)
        val result = BlockLaunchGate.pendingOverlayAfterLaunch(unseen, target = insta, nowMs = 4_000, id = 9L)
        assertFalse(
            "an overlay that has never reached the screen is replaced, not preserved, on re-launch",
            result.windowShown
        )
        assertEquals(9L, result.id)
        assertEquals(4_000L, result.launchedAtMs)
    }

    @Test
    fun `a launch with no prior pending overlay produces a fresh unseen pending`() {
        val result = BlockLaunchGate.pendingOverlayAfterLaunch(null, target = insta, nowMs = 4_000, id = 9L)
        assertEquals(insta, result.packageName)
        assertFalse(result.windowShown)
        assertEquals(9L, result.id)
    }

    // ============================================================== pendingOverlayAfterDismissal

    @Test
    fun `a dismissal clears the pending overlay when the id matches`() {
        val pending = PendingOverlay(insta, launchedAtMs = 1_000, windowShown = true, id = 7L)
        assertNull(
            "the owning instance's dismissal must clear its own pending state",
            BlockLaunchGate.pendingOverlayAfterDismissal(pending, id = 7L)
        )
    }

    @Test
    fun `a dismissal from an older instance keeps the live pending overlay`() {
        val pending = PendingOverlay(insta, launchedAtMs = 1_000, windowShown = true, id = 8L)
        assertEquals(
            "onDestroy of a replaced instance must not wipe the replacement's live pending state",
            pending,
            BlockLaunchGate.pendingOverlayAfterDismissal(pending, id = 7L)
        )
    }

    @Test
    fun `a dismissal with no pending overlay at all stays null`() {
        assertNull(BlockLaunchGate.pendingOverlayAfterDismissal(null, id = NO_OVERLAY_ID))
    }
}
