package com.astraedus.nudge.domain.block

import com.astraedus.nudge.domain.block.BlockLaunchGate.Decision
import com.astraedus.nudge.domain.block.BlockLaunchGate.WalkAway
import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.ForegroundSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Every branch of the launch gate, and the combinations that matter.
 *
 * The two issues this gate closes have opposite shapes, which is why the combination cases below
 * are the interesting ones rather than padding:
 *
 *  - [#31](https://github.com/astraedus/nudge/issues/31): the decision is stale because the user
 *    moved. The foreground check catches it and the walk-away window is irrelevant.
 *  - [#26](https://github.com/astraedus/nudge/issues/26): the decision is stale because the user is
 *    LEAVING, and at the instant it lands the blocked app genuinely is in front. The foreground
 *    check is powerless; only the walk-away window can see it.
 */
class BlockLaunchGateTest {

    private val blocked = "com.instagram.android"
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val nudge = "dev.astraedus.nudge"
    private val other = "com.google.android.keep"

    // --- the foreground condition (issue #31) ---------------------------------------------------

    @Test
    fun `a decision for the app that is still in front launches`() {
        assertEquals(
            Decision.LAUNCH,
            BlockLaunchGate.decide(blocked, foreground = blocked, walkAway = null, nowMs = 1_000)
        )
    }

    @Test
    fun `a decision that lands after the user went home is dropped`() {
        assertEquals(
            Decision.DROP_FOREGROUND_MOVED,
            BlockLaunchGate.decide(blocked, foreground = launcher, walkAway = null, nowMs = 1_000)
        )
    }

    /** The most visible form of the report: an overlay landing on top of Nudge's own screens. */
    @Test
    fun `a decision that lands after the user opened Nudge is dropped`() {
        assertEquals(
            Decision.DROP_FOREGROUND_MOVED,
            BlockLaunchGate.decide(blocked, foreground = nudge, walkAway = null, nowMs = 1_000)
        )
    }

    @Test
    fun `a decision that lands after the user switched to another app is dropped`() {
        assertEquals(
            Decision.DROP_FOREGROUND_MOVED,
            BlockLaunchGate.decide(blocked, foreground = other, walkAway = null, nowMs = 1_000)
        )
    }

    /**
     * "We have observed nothing" is not "the user is elsewhere". The gate may only ever WEAKEN
     * enforcement on positive evidence, the same failure direction the launcher set, the active
     * window read and the URL-bar read all already take.
     */
    @Test
    fun `an unknown foreground never suppresses a block`() {
        assertEquals(
            Decision.LAUNCH,
            BlockLaunchGate.decide(blocked, foreground = null, walkAway = null, nowMs = 1_000)
        )
    }

    /**
     * A web block is attributed to the rule's app and targeted at the BROWSER. Passing the
     * attributed package as the target would compare Instagram against Chrome and drop every web
     * block ever written.
     */
    @Test
    fun `a web block targets the browser the user is sitting in`() {
        val chrome = "com.android.chrome"
        assertEquals(
            Decision.LAUNCH,
            BlockLaunchGate.decide(chrome, foreground = chrome, walkAway = null, nowMs = 1_000)
        )
        assertEquals(
            "and the rule's app is never in front when its website is blocked in a browser",
            Decision.DROP_FOREGROUND_MOVED,
            BlockLaunchGate.decide(blocked, foreground = chrome, walkAway = null, nowMs = 1_000)
        )
    }

    // --- the walk-away condition (issue #26) ----------------------------------------------------

    /**
     * THE ISSUE #26 CASE, in one assertion. The blocked app is in front, its window really did
     * resurface under the finishing overlay, so the foreground condition says launch. It must not.
     */
    @Test
    fun `the blocked app resurfacing during a walk-away does not re-arm the block`() {
        val walkAway = WalkAway(blocked, armedAtMs = 1_000)
        assertEquals(
            Decision.DROP_WALK_AWAY_IN_FLIGHT,
            BlockLaunchGate.decide(blocked, foreground = blocked, walkAway = walkAway, nowMs = 1_100)
        )
    }

    /** The counterfactual: without the window, that same state is a launch. */
    @Test
    fun `the same state with no walk-away in flight really would launch`() {
        assertEquals(
            "if this ever reads DROP, the test above has stopped proving anything",
            Decision.LAUNCH,
            BlockLaunchGate.decide(blocked, foreground = blocked, walkAway = null, nowMs = 1_100)
        )
    }

    @Test
    fun `the walk-away window expires so a later re-entry still blocks`() {
        val walkAway = WalkAway(blocked, armedAtMs = 1_000)
        val justInside = 1_000 + BlockLaunchGate.WALK_AWAY_TRANSITION_MS - 1
        val atTheBoundary = 1_000 + BlockLaunchGate.WALK_AWAY_TRANSITION_MS
        assertEquals(
            Decision.DROP_WALK_AWAY_IN_FLIGHT,
            BlockLaunchGate.decide(blocked, blocked, walkAway, nowMs = justInside)
        )
        assertEquals(
            "the window is exclusive at its upper bound, a re-entry after it blocks",
            Decision.LAUNCH,
            BlockLaunchGate.decide(blocked, blocked, walkAway, nowMs = atTheBoundary)
        )
    }

    /** Walking away from one app must not buy a free entry into a different one. */
    @Test
    fun `a walk-away from one app does not suppress a block for another`() {
        val walkAway = WalkAway(other, armedAtMs = 1_000)
        assertEquals(
            Decision.LAUNCH,
            BlockLaunchGate.decide(blocked, foreground = blocked, walkAway = walkAway, nowMs = 1_100)
        )
    }

    /**
     * Both conditions true at once. The walk-away answer wins because it is the more specific one,
     * and because logcat naming the wrong cause is how a device session gets spent on the wrong
     * hypothesis.
     */
    @Test
    fun `a walk-away in flight outranks a moved foreground in the reported reason`() {
        val walkAway = WalkAway(blocked, armedAtMs = 1_000)
        assertEquals(
            Decision.DROP_WALK_AWAY_IN_FLIGHT,
            BlockLaunchGate.decide(blocked, foreground = launcher, walkAway = walkAway, nowMs = 1_100)
        )
    }

    // --- the pending-overlay condition (the duplicate block) ------------------------------------

    private fun pending(pkg: String = blocked, at: Long = 1_000, shown: Boolean = false) =
        BlockLaunchGate.PendingOverlay(pkg, at, shown)

    @Test
    fun `a second launch for an overlay already on its way is refused`() {
        assertEquals(
            Decision.DROP_ALREADY_PENDING,
            BlockLaunchGate.decide(blocked, blocked, null, nowMs = 1_100, pendingOverlay = pending())
        )
    }

    @Test
    fun `once the overlay is on screen a fresh launch is allowed again`() {
        assertEquals(
            "a re-block after the user gets past the overlay is a real, separate block",
            Decision.LAUNCH,
            BlockLaunchGate.decide(
                blocked, blocked, null, nowMs = 1_100, pendingOverlay = pending(shown = true)
            )
        )
    }

    @Test
    fun `a pending overlay for one app does not block a launch for another`() {
        assertEquals(
            Decision.LAUNCH,
            BlockLaunchGate.decide(other, other, null, nowMs = 1_100, pendingOverlay = pending())
        )
    }

    @Test
    fun `an overlay that never appears stops blocking launches once it goes stale`() {
        assertEquals(
            Decision.LAUNCH,
            BlockLaunchGate.decide(
                blocked, blocked, null,
                nowMs = 1_000 + BlockLaunchGate.OVERLAY_SETTLE_MS,
                pendingOverlay = pending()
            )
        )
    }

    // --- the pending overlay's DECISION, not just its package (issue #50) -----------------------

    private val dailyLimit = BlockLaunchGate.decisionFingerprint(
        attributedPackage = blocked,
        blockMode = "HARD_BLOCK",
        dailyLimited = true
    )
    private val plainDelay =
        BlockLaunchGate.decisionFingerprint(attributedPackage = blocked, blockMode = "DELAY")

    /**
     * THE #50 CASE. The reporter raised a daily limit and cold-launched the app; logcat shows the
     * fresh evaluation computing a DELAY with 17 minutes left, and the launch that would have
     * shown it refused as a duplicate of the "Daily limit reached" screen that no longer applied.
     */
    @Test
    fun `a pending overlay for a DIFFERENT block does not make this launch a duplicate`() {
        assertEquals(
            Decision.LAUNCH,
            BlockLaunchGate.decide(
                blocked, blocked, null, nowMs = 1_100,
                pendingOverlay = pending().copy(decisionKey = dailyLimit),
                decisionKey = plainDelay
            )
        )
    }

    /**
     * ...and the counterfactual, which is the pre-fix rule verbatim: with no claim about WHICH
     * block this is, the gate falls back to "same package, therefore the same thing" and refuses
     * exactly as it did when issue #50 was reported.
     */
    @Test
    fun `counterfactual - with no decision claim the stale overlay still swallows the launch`() {
        assertEquals(
            Decision.DROP_ALREADY_PENDING,
            BlockLaunchGate.decide(
                blocked, blocked, null, nowMs = 1_100,
                pendingOverlay = pending().copy(decisionKey = dailyLimit),
                decisionKey = null
            )
        )
    }

    /**
     * The other direction, and it is what stops this reopening issue #36: the SAME block launched
     * twice inside the settle window is still one block, and the second launch is still refused.
     */
    @Test
    fun `the same block launched twice inside the settle window is still a duplicate`() {
        assertEquals(
            Decision.DROP_ALREADY_PENDING,
            BlockLaunchGate.decide(
                blocked, blocked, null, nowMs = 1_100,
                pendingOverlay = pending().copy(decisionKey = plainDelay),
                decisionKey = plainDelay
            )
        )
    }

    /**
     * A fingerprint over a MOVING number would be no fingerprint at all: the auto-kick cooldown's
     * remaining seconds and a daily limit's remaining milliseconds both tick every time they are
     * read, so including either would make every re-launch inside the settle window look new and
     * hand back the duplicate launches issue #36 measured.
     */
    @Test
    fun `the fingerprint is stable across everything that ticks`() {
        assertEquals(
            "two reads of the same block a second apart must fingerprint identically",
            BlockLaunchGate.decisionFingerprint(blocked, "DELAY"),
            BlockLaunchGate.decisionFingerprint(blocked, "DELAY")
        )
        assertTrue(
            "and the identity of the confrontation is reused rather than re-spelled",
            plainDelay.startsWith(BlockLaunchGate.confrontationKey(blocked))
        )
    }

    /** What the fingerprint must be able to tell apart, each for a reason a user would see. */
    @Test
    fun `the fingerprint separates mode, daily-limit copy, feature and site`() {
        val distinct = listOf(
            BlockLaunchGate.decisionFingerprint(blocked, "DELAY"),
            BlockLaunchGate.decisionFingerprint(blocked, "HARD_BLOCK"),
            BlockLaunchGate.decisionFingerprint(blocked, "HARD_BLOCK", dailyLimited = true),
            BlockLaunchGate.decisionFingerprint(blocked, "DELAY", featureKey = "reels"),
            BlockLaunchGate.decisionFingerprint(other, "DELAY", webDomain = "instagram.com"),
            BlockLaunchGate.decisionFingerprint(other, "DELAY", webDomain = "youtube.com")
        )
        assertEquals(
            "each of these is a different screen, so none may collapse onto another",
            distinct.size,
            distinct.toSet().size
        )
    }

    /**
     * A re-delivery through `onNewIntent` keeps the on-screen overlay where it is (issue #36) but
     * must adopt the NEW block's fingerprint: the activity is about to render that block, so a
     * record still describing the previous one would call the next identical launch fresh and the
     * next genuinely-different one a duplicate -- issue #50 inverted.
     */
    @Test
    fun `a re-delivery to an on-screen overlay adopts the new decision`() {
        val onScreen = BlockLaunchGate.PendingOverlay(
            blocked, launchedAtMs = 1_000, windowShown = true, id = 7L, decisionKey = dailyLimit
        )
        val after = BlockLaunchGate.pendingOverlayAfterLaunch(
            pending = onScreen, target = blocked, nowMs = 4_000, id = 9L, decisionKey = plainDelay
        )
        assertEquals("the overlay is still the one on screen", 7L, after.id)
        assertTrue("and still on screen", after.windowShown)
        assertEquals("but it is showing the NEW block now", plainDelay, after.decisionKey)
    }

    // --- the block we raced ourselves out of (issue #58, mechanism 2) ---------------------------

    @Test
    fun `a launch dropped because the foreground moved is remembered`() {
        assertEquals(
            BlockLaunchGate.DeferredLaunch(blocked, 1_000),
            BlockLaunchGate.deferredLaunchAfterDecision(
                null, Decision.DROP_FOREGROUND_MOVED, blocked, nowMs = 1_000
            )
        )
    }

    /**
     * The other three verdicts owe nothing. A walk-away drop is the user having just DECLINED this
     * block (#26) and re-arming after it would undo the fix; an already-pending drop means the
     * overlay is on its way; and a launch settles the debt outright.
     */
    @Test
    fun `the deliberate verdicts clear the deferral instead of creating one`() {
        val deferred = BlockLaunchGate.DeferredLaunch(blocked, 1_000)
        listOf(
            Decision.LAUNCH,
            Decision.DROP_WALK_AWAY_IN_FLIGHT,
            Decision.DROP_ALREADY_PENDING
        ).forEach { decision ->
            assertNull(
                "$decision must not leave a block owed",
                BlockLaunchGate.deferredLaunchAfterDecision(deferred, decision, blocked, 1_200)
            )
        }
    }

    @Test
    fun `a verdict for another app leaves this app's deferral alone`() {
        val deferred = BlockLaunchGate.DeferredLaunch(blocked, 1_000)
        assertSame(
            deferred,
            BlockLaunchGate.deferredLaunchAfterDecision(deferred, Decision.LAUNCH, other, 1_200)
        )
    }

    @Test
    fun `the target settling in front redeems the deferral`() {
        assertTrue(
            BlockLaunchGate.isDeferredLaunchRedeemed(
                deferred = BlockLaunchGate.DeferredLaunch(blocked, 1_000),
                eventType = A11yEventType.WINDOW_STATE_CHANGED,
                signal = ForegroundSignal.AppWindow(blocked),
                nowMs = 1_200
            )
        )
    }

    /**
     * `WINDOW_STATE_CHANGED` and nothing else, for the same reason [BlockLaunchGate.isGenuineBypass]
     * insists on it: only that type means a new activity is in front. A content change from the
     * target comes from a window that is already there and proves nothing about the race.
     */
    @Test
    fun `a content change from the target is not the settle`() {
        assertFalse(
            BlockLaunchGate.isDeferredLaunchRedeemed(
                deferred = BlockLaunchGate.DeferredLaunch(blocked, 1_000),
                eventType = A11yEventType.WINDOW_CONTENT_CHANGED,
                signal = ForegroundSignal.AppWindow(blocked),
                nowMs = 1_200
            )
        )
    }

    @Test
    fun `another app settling does not redeem this deferral`() {
        assertFalse(
            BlockLaunchGate.isDeferredLaunchRedeemed(
                deferred = BlockLaunchGate.DeferredLaunch(blocked, 1_000),
                eventType = A11yEventType.WINDOW_STATE_CHANGED,
                signal = ForegroundSignal.AppWindow(other),
                nowMs = 1_200
            )
        )
    }

    @Test
    fun `a deferral past its time to live is never redeemed`() {
        assertFalse(
            "re-blocking an app minutes after the drop is the failure a user would see",
            BlockLaunchGate.isDeferredLaunchRedeemed(
                deferred = BlockLaunchGate.DeferredLaunch(blocked, 1_000),
                eventType = A11yEventType.WINDOW_STATE_CHANGED,
                signal = ForegroundSignal.AppWindow(blocked),
                nowMs = 1_000 + BlockLaunchGate.DEFERRED_LAUNCH_TTL_MS
            )
        )
    }

    /**
     * The deferral's departure rule is the same as the arrival's and the storm's, for the third
     * time: only a signal that proves the user is somewhere else may end it. A sub-flow must not,
     * or the very race this exists for would cancel its own deferral.
     */
    @Test
    fun `the deferral survives a sub-flow and dies on a real departure`() {
        val deferred = BlockLaunchGate.DeferredLaunch(blocked, 1_000)
        listOf(
            ForegroundSignal.OwnUi(nudge),
            ForegroundSignal.AwarenessOverlay(nudge),
            ForegroundSignal.SystemSurface("com.android.systemui"),
            ForegroundSignal.Transient("com.google.android.inputmethod.latin"),
            ForegroundSignal.PipOnly("com.google.android.youtube"),
            ForegroundSignal.NotForeground(blocked),
            ForegroundSignal.AppWindow(blocked)
        ).forEach { signal ->
            assertSame(
                "$signal is not the user leaving $blocked",
                deferred,
                BlockLaunchGate.deferredLaunchAfterSignal(signal, deferred)
            )
        }
        assertNull(
            "going home means no block is owed on the way in any more",
            BlockLaunchGate.deferredLaunchAfterSignal(ForegroundSignal.Home(launcher), deferred)
        )
        assertNull(
            "and so does another app genuinely coming forward",
            BlockLaunchGate.deferredLaunchAfterSignal(ForegroundSignal.AppWindow(other), deferred)
        )
    }

    // --- isGenuineBypass ------------------------------------------------------------------------

    private fun bypass(
        signal: ForegroundSignal,
        pendingOverlay: BlockLaunchGate.PendingOverlay?,
        nowMs: Long = 1_100,
        eventType: A11yEventType = A11yEventType.WINDOW_STATE_CHANGED
    ) = BlockLaunchGate.isGenuineBypass(eventType, signal, pendingOverlay, nowMs)

    /** With nothing pending, the rule is exactly the one it replaces. */
    @Test
    fun `with no overlay pending the bypass rule is unchanged`() {
        assertTrue(bypass(ForegroundSignal.AppWindow(blocked), null))
        assertFalse(bypass(ForegroundSignal.OwnUi(nudge), null))
        assertFalse(bypass(ForegroundSignal.SystemSurface("com.android.systemui"), null))
        assertFalse(bypass(ForegroundSignal.Transient("com.google.android.inputmethod.latin"), null))
        assertFalse(bypass(ForegroundSignal.PipOnly("com.google.android.youtube"), null))
        assertFalse(
            "only a new activity in front can be a bypass",
            bypass(
                ForegroundSignal.AppWindow(blocked), null,
                eventType = A11yEventType.WINDOW_CONTENT_CHANGED
            )
        )
    }

    /** THE DUPLICATE BLOCK, in one assertion. */
    @Test
    fun `the blocked app's own window before the overlay arrives is not a bypass`() {
        assertFalse(bypass(ForegroundSignal.AppWindow(blocked), pending()))
    }

    @Test
    fun `the same event once the overlay is on screen is a bypass`() {
        assertTrue(
            "this is the case the rule exists for: the user really did get back into the app",
            bypass(ForegroundSignal.AppWindow(blocked), pending(shown = true))
        )
    }

    @Test
    fun `a different app coming forward while the overlay launches is still a bypass`() {
        assertTrue(
            "suppressing this would swallow a genuine app switch for the whole settle window",
            bypass(ForegroundSignal.AppWindow(other), pending())
        )
    }

    @Test
    fun `an overlay that never reaches the screen stops suppressing after the settle window`() {
        assertFalse(
            bypass(ForegroundSignal.AppWindow(blocked), pending(at = 0), nowMs = BlockLaunchGate.OVERLAY_SETTLE_MS - 1)
        )
        assertTrue(
            bypass(ForegroundSignal.AppWindow(blocked), pending(at = 0), nowMs = BlockLaunchGate.OVERLAY_SETTLE_MS)
        )
    }

    @Test
    fun `only the overlay reporting itself resolves the pending state`() {
        val p = pending()
        assertSame(p, BlockLaunchGate.pendingOverlayAfter(p, overlayShown = false))
        assertEquals(true, BlockLaunchGate.pendingOverlayAfter(p, overlayShown = true)?.windowShown)
        assertNull(BlockLaunchGate.pendingOverlayAfter(null, overlayShown = true))
    }

    // --- an overlay buried before it reached the screen (issue #143) ------------------------------

    private val arrival = BlockLaunchGate.Arrival(blocked, listOf(blocked))

    private fun displaced(at: Long = 1_000) =
        BlockLaunchGate.pendingOverlayAfterSignal(
            ForegroundSignal.AppWindow(other), pending(at = at).copy(decisionKey = plainDelay), arrival
        )!!

    @Test
    fun `another app's window in front of an unshown overlay displaces it, keeping the arrival`() {
        val d = displaced()
        assertTrue(d.displaced)
        assertEquals(
            "the arrival as it was BEFORE the displacing window ended it, so a re-show can restore it",
            arrival,
            d.displacedArrival
        )
    }

    /** The class, not the instance: every signal that is not another app's window displaces nothing. */
    @Test
    fun `nothing but another app's window displaces a pending overlay`() {
        val p = pending()
        listOf(
            ForegroundSignal.AppWindow(blocked),
            ForegroundSignal.Home(launcher),
            ForegroundSignal.OwnUi(nudge),
            ForegroundSignal.AwarenessOverlay(nudge),
            ForegroundSignal.SystemSurface("com.android.systemui"),
            ForegroundSignal.Transient("com.google.android.inputmethod.latin"),
            ForegroundSignal.PipOnly("com.google.android.youtube"),
            ForegroundSignal.NotForeground(other)
        ).forEach { signal ->
            assertSame("$signal", p, BlockLaunchGate.pendingOverlayAfterSignal(signal, p, arrival))
        }
    }

    @Test
    fun `an overlay already on screen is never displaced, that is the ordinary bypass`() {
        val shown = pending(shown = true)
        assertSame(
            shown,
            BlockLaunchGate.pendingOverlayAfterSignal(ForegroundSignal.AppWindow(other), shown, arrival)
        )
        assertNull(BlockLaunchGate.pendingOverlayAfterSignal(ForegroundSignal.AppWindow(other), null, arrival))
    }

    @Test
    fun `the first displacement's arrival is kept, a second window does not overwrite it`() {
        val d = displaced()
        assertSame(
            d,
            BlockLaunchGate.pendingOverlayAfterSignal(ForegroundSignal.AppWindow("com.example.b"), d, null)
        )
    }

    @Test
    fun `an arrival for another app is not the displaced overlay's to restore`() {
        val d = BlockLaunchGate.pendingOverlayAfterSignal(
            ForegroundSignal.AppWindow(other), pending(), BlockLaunchGate.Arrival(other, listOf(other))
        )!!
        assertTrue(d.displaced)
        assertNull(d.displacedArrival)
    }

    /** THE #143 DECISION: the same block, buried before it arrived, is re-shown and not dropped. */
    @Test
    fun `the same block for a displaced overlay is re-shown, not dropped as pending`() {
        assertEquals(
            Decision.RESHOW_HIDDEN,
            BlockLaunchGate.decide(
                blocked, blocked, null, nowMs = 1_150, pendingOverlay = displaced(), decisionKey = plainDelay
            )
        )
    }

    /** ...and the counterfactual: the identical state without the displacement is the drop #143 hit. */
    @Test
    fun `counterfactual - the same state without the displacement is still dropped as pending`() {
        assertEquals(
            Decision.DROP_ALREADY_PENDING,
            BlockLaunchGate.decide(
                blocked, blocked, null, nowMs = 1_150,
                pendingOverlay = displaced().copy(displaced = false), decisionKey = plainDelay
            )
        )
    }

    @Test
    fun `a DIFFERENT block for a displaced overlay is a fresh launch, as issue 50 requires`() {
        assertEquals(
            Decision.LAUNCH,
            BlockLaunchGate.decide(
                blocked, blocked, null, nowMs = 1_150, pendingOverlay = displaced(), decisionKey = dailyLimit
            )
        )
    }

    @Test
    fun `a displaced overlay past the settle window falls back to an ordinary launch`() {
        assertEquals(
            Decision.LAUNCH,
            BlockLaunchGate.decide(
                blocked, blocked, null,
                nowMs = 1_000 + BlockLaunchGate.OVERLAY_SETTLE_MS,
                pendingOverlay = displaced(), decisionKey = plainDelay
            )
        )
    }

    /**
     * A re-show puts an overlay on screen, so it answers to the same "where is the user" checks a
     * launch does: a buried overlay must never be dragged back over an app the user moved on to.
     */
    @Test
    fun `a re-show is refused when the user has moved on or is walking away`() {
        assertEquals(
            Decision.DROP_FOREGROUND_MOVED,
            BlockLaunchGate.decide(
                blocked, foreground = other, walkAway = null, nowMs = 1_150,
                pendingOverlay = displaced(), decisionKey = plainDelay
            )
        )
        assertEquals(
            Decision.DROP_WALK_AWAY_IN_FLIGHT,
            BlockLaunchGate.decide(
                blocked, foreground = blocked, walkAway = WalkAway(blocked, 1_100), nowMs = 1_150,
                pendingOverlay = displaced(), decisionKey = plainDelay
            )
        )
        assertEquals(
            "with no claim about the foreground, the gate does not weaken enforcement",
            Decision.RESHOW_HIDDEN,
            BlockLaunchGate.decide(
                blocked, foreground = null, walkAway = null, nowMs = 1_150,
                pendingOverlay = displaced(), decisionKey = plainDelay
            )
        )
    }

    @Test
    fun `a displaced overlay for one app does not touch a launch for another`() {
        assertEquals(
            Decision.LAUNCH,
            BlockLaunchGate.decide(other, other, null, nowMs = 1_150, pendingOverlay = displaced())
        )
    }

    @Test
    fun `the target back in front of a displaced overlay is a bypass, even inside the settle window`() {
        assertFalse(
            "fixture: undisplaced, the same event is the app still starting under its overlay",
            bypass(ForegroundSignal.AppWindow(blocked), pending())
        )
        assertTrue(bypass(ForegroundSignal.AppWindow(blocked), displaced()))
        assertFalse(
            "a floating window is still never a bypass (#71)",
            BlockLaunchGate.isGenuineBypass(
                A11yEventType.WINDOW_STATE_CHANGED, ForegroundSignal.AppWindow(blocked), displaced(),
                nowMs = 1_100, floating = true
            )
        )
    }

    @Test
    fun `a re-show keeps the overlay, restarts its settle window and clears the displacement`() {
        val d = displaced().copy(id = 7)
        val r = BlockLaunchGate.pendingOverlayAfterReshow(d, nowMs = 1_200)
        assertEquals(7, r.id)
        assertEquals(plainDelay, r.decisionKey)
        assertEquals(1_200, r.launchedAtMs)
        assertFalse(r.displaced)
        assertNull(r.displacedArrival)
        assertFalse(r.windowShown)
        assertEquals(
            "the target's own windows while it comes back are the app being covered again",
            Decision.DROP_ALREADY_PENDING,
            BlockLaunchGate.decide(blocked, blocked, null, nowMs = 1_300, pendingOverlay = r, decisionKey = plainDelay)
        )
    }

    @Test
    fun `a re-show restores the arrival whichever side of the displacement the claim landed`() {
        val key = BlockLaunchGate.confrontationKey(blocked)
        val counted = BlockLaunchGate.Arrival(blocked, listOf(key))
        // Claimed before the displacement: the snapshot has it, the live arrival was ended.
        assertEquals(counted, BlockLaunchGate.arrivalAfterReshow(null, counted))
        // Claimed after: the snapshot predates it, the live arrival has it.
        assertEquals(counted, BlockLaunchGate.arrivalAfterReshow(counted, null))
        assertEquals(
            counted,
            BlockLaunchGate.arrivalAfterReshow(counted, BlockLaunchGate.Arrival(blocked, emptyList()))
        )
        // Both: one key, never two.
        assertEquals(counted, BlockLaunchGate.arrivalAfterReshow(counted, counted))
        assertFalse(BlockLaunchGate.isNewConfrontation(BlockLaunchGate.arrivalAfterReshow(null, counted), blocked, key))
    }

    @Test
    fun `a re-show settles a deferred launch for its target like a launch does`() {
        val deferred = BlockLaunchGate.DeferredLaunch(blocked, 1_000)
        assertNull(BlockLaunchGate.deferredLaunchAfterDecision(deferred, Decision.RESHOW_HIDDEN, blocked, 1_100))
    }

    // --- foregroundAfter ------------------------------------------------------------------------

    @Test
    fun `only an app window, home and our own UI move the foreground`() {
        val previous = blocked
        assertEquals(other, BlockLaunchGate.foregroundAfter(ForegroundSignal.AppWindow(other), previous))
        assertEquals(launcher, BlockLaunchGate.foregroundAfter(ForegroundSignal.Home(launcher), previous))
        assertEquals(nudge, BlockLaunchGate.foregroundAfter(ForegroundSignal.OwnUi(nudge), previous))
    }

    /**
     * The four that must NOT move it. Each one of these being read as a foreground change would
     * drop legitimate blocks whenever the shade, a permission dialog, a keyboard or a
     * picture-in-picture bubble happened to land inside the milliseconds a rule lookup takes, the
     * `SYSTEM_PACKAGES`-answers-two-questions trap, for the fourth time.
     */
    /**
     * ISSUE #41, at the one line it lives on. Our AWARENESS overlays carry our package like every
     * other Nudge window, and mean the opposite thing: the counter and the time-remaining pill are
     * drawn over an app the user never left. Reading one as "Nudge is in front" moved the
     * foreground and nothing moved it back while the user sat still, so every 30-second
     * daily-limit tick after it was refused with DROP_FOREGROUND_MOVED and someone past their
     * limit stopped being blocked. The overlay is included in the list below for exactly that
     * reason; the assertion that it is NOT `OwnUi` in the first place lives in
     * `EventClassifierTest`.
     */
    @Test
    fun `a system surface, a keyboard, a PiP bubble and a non-window event leave the foreground alone`() {
        val previous = blocked
        listOf(
            ForegroundSignal.SystemSurface("com.android.systemui"),
            ForegroundSignal.Transient("com.google.android.inputmethod.latin"),
            ForegroundSignal.PipOnly("com.google.android.youtube"),
            ForegroundSignal.NotForeground(blocked),
            ForegroundSignal.AwarenessOverlay(nudge)
        ).forEach { signal ->
            assertEquals(
                "$signal must not move the foreground",
                previous,
                BlockLaunchGate.foregroundAfter(signal, previous)
            )
        }
    }

    // --- walkAwayAfter --------------------------------------------------------------------------

    @Test
    fun `there is nothing to expire when no walk-away is pending`() {
        assertNull(BlockLaunchGate.walkAwayAfter(ForegroundSignal.Home(launcher), pending = null))
    }

    /** The ordinary end of the window: the go-home landed, so it is over on evidence, not a clock. */
    @Test
    fun `observing home closes the walk-away window`() {
        val pending = WalkAway(blocked, armedAtMs = 1_000)
        assertNull(BlockLaunchGate.walkAwayAfter(ForegroundSignal.Home(launcher), pending))
    }

    @Test
    fun `the walked-away app coming back keeps the window open`() {
        val pending = WalkAway(blocked, armedAtMs = 1_000)
        assertSame(pending, BlockLaunchGate.walkAwayAfter(ForegroundSignal.AppWindow(blocked), pending))
    }

    /** Any OTHER app in front is proof the departure happened, so the window has done its job. */
    @Test
    fun `a different app coming to the front closes the walk-away window`() {
        val pending = WalkAway(blocked, armedAtMs = 1_000)
        assertNull(BlockLaunchGate.walkAwayAfter(ForegroundSignal.AppWindow(other), pending))
    }

    /**
     * Our own UI must NOT close it. The overlay that armed the window is itself Nudge UI on its way
     * out, and its dying window event arrives before the transition it is waiting for, closing on
     * it would make the window last approximately zero milliseconds and quietly restore issue #26.
     */
    @Test
    fun `Nudge's own UI does not close the walk-away window`() {
        val pending = WalkAway(blocked, armedAtMs = 1_000)
        assertSame(pending, BlockLaunchGate.walkAwayAfter(ForegroundSignal.OwnUi(nudge), pending))
    }

    @Test
    fun `signals that claim nothing about the foreground leave the window alone`() {
        val pending = WalkAway(blocked, armedAtMs = 1_000)
        listOf(
            ForegroundSignal.AwarenessOverlay(nudge),
            ForegroundSignal.SystemSurface("com.android.systemui"),
            ForegroundSignal.Transient("com.google.android.inputmethod.latin"),
            ForegroundSignal.PipOnly("com.google.android.youtube"),
            ForegroundSignal.NotForeground(blocked)
        ).forEach { signal ->
            assertSame("$signal must not close the walk-away window", pending, BlockLaunchGate.walkAwayAfter(signal, pending))
        }
    }
}
