package com.astraedus.nudge.domain.block

import com.astraedus.nudge.BuildConfig
import com.astraedus.nudge.domain.block.BlockLaunchGate.Arrival
import com.astraedus.nudge.domain.block.BlockLaunchGate.ArrivalVerdict
import com.astraedus.nudge.domain.block.BlockLaunchGate.ArrivalWatch
import com.astraedus.nudge.domain.block.BlockLaunchGate.Decision
import com.astraedus.nudge.domain.block.BlockLaunchGate.PendingOverlay
import com.astraedus.nudge.domain.events.ForegroundSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arrival check: after a block is launched, is it actually the thing in front of the app it
 * blocks? (`BlockLaunchGate.OVERLAY_ARRIVAL_CHECK_MS`.)
 *
 * Layer L1 (pure decision). The recorded sequence that needs it, a first launch of Google Docs whose
 * own trampoline buries the Hard block, is replayed at L2 in `OverlayArrivalReplayTest`.
 */
class OverlayArrivalCheckTest {

    private val docs = "com.google.android.apps.docs.editors.docs"
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val nudge = BuildConfig.APPLICATION_ID
    private val other = "com.google.android.keep"

    private fun watch(seq: Long = 7L, rescues: Int = 0) =
        ArrivalWatch(targetPackage = docs, launchSeq = seq, rescues = rescues)

    // --- the verdict ------------------------------------------------------------------------------

    @Test
    fun `the blocked app owning the active window is a block that is not there - rescue`() {
        assertEquals(
            ArrivalVerdict.RESCUE,
            BlockLaunchGate.arrivalVerdict(watch(), checkSeq = 7L, target = docs, activeWindowPackage = docs)
        )
    }

    @Test
    fun `our overlay in front is a block that arrived`() {
        assertEquals(
            ArrivalVerdict.NOT_BURIED,
            BlockLaunchGate.arrivalVerdict(watch(), checkSeq = 7L, target = docs, activeWindowPackage = nudge)
        )
    }

    /** The launcher, another app, a dialog: none of them is the blocked app getting used. */
    @Test
    fun `anything else in front is not the blocked app getting through`() {
        for (front in listOf(launcher, other, "com.google.android.permissioncontroller")) {
            assertEquals(
                front,
                ArrivalVerdict.NOT_BURIED,
                BlockLaunchGate.arrivalVerdict(watch(), checkSeq = 7L, target = docs, activeWindowPackage = front)
            )
        }
    }

    /** Same rule as #7's verified switch: an unreadable window is never evidence. */
    @Test
    fun `an unreadable active window is never a rescue`() {
        assertEquals(
            ArrivalVerdict.UNVERIFIED,
            BlockLaunchGate.arrivalVerdict(watch(), checkSeq = 7L, target = docs, activeWindowPackage = null)
        )
    }

    @Test
    fun `a check for a launch a later one replaced stands down`() {
        assertEquals(
            ArrivalVerdict.SUPERSEDED,
            BlockLaunchGate.arrivalVerdict(watch(seq = 8L), checkSeq = 7L, target = docs, activeWindowPackage = docs)
        )
    }

    @Test
    fun `a check after the user left has nothing to watch`() {
        assertEquals(
            ArrivalVerdict.MOVED_ON,
            BlockLaunchGate.arrivalVerdict(null, checkSeq = 7L, target = docs, activeWindowPackage = docs)
        )
        assertEquals(
            ArrivalVerdict.MOVED_ON,
            BlockLaunchGate.arrivalVerdict(
                ArrivalWatch(targetPackage = other, launchSeq = 7L),
                checkSeq = 7L,
                target = docs,
                activeWindowPackage = docs
            )
        )
    }

    /** The cap holds even while the evidence keeps saying "rescue". */
    @Test
    fun `a stay that has spent its rescues is logged, never acted on`() {
        val spent = watch(rescues = BlockLaunchGate.MAX_ARRIVAL_RESCUES)
        assertEquals(
            ArrivalVerdict.SPENT,
            BlockLaunchGate.arrivalVerdict(spent, checkSeq = 7L, target = docs, activeWindowPackage = docs)
        )
        assertEquals(
            ArrivalVerdict.RESCUE,
            BlockLaunchGate.arrivalVerdict(
                watch(rescues = BlockLaunchGate.MAX_ARRIVAL_RESCUES - 1),
                checkSeq = 7L,
                target = docs,
                activeWindowPackage = docs
            )
        )
    }

    // --- the watch's lifecycle --------------------------------------------------------------------

    @Test
    fun `a re-launch over the same app keeps the stay's rescue count and takes the new number`() {
        val next = BlockLaunchGate.arrivalWatchAfterLaunch(watch(seq = 7L, rescues = 1), docs, launchSeq = 9L)
        assertEquals(ArrivalWatch(docs, launchSeq = 9L, rescues = 1), next)
    }

    @Test
    fun `a launch over a different app starts a fresh watch`() {
        val next = BlockLaunchGate.arrivalWatchAfterLaunch(watch(rescues = 2), other, launchSeq = 9L)
        assertEquals(ArrivalWatch(other, launchSeq = 9L, rescues = 0), next)
    }

    /**
     * Exhaustive over every signal shape: only visible evidence that the user is somewhere else
     * (Home, ANOTHER app's window) ends the watch, the rule `arrivalAfterSignal` uses.
     */
    @Test
    fun `only Home or another app ends the watch`() {
        val current = watch()
        val ends = listOf(ForegroundSignal.Home(launcher), ForegroundSignal.AppWindow(other))
        val keeps = listOf(
            ForegroundSignal.AppWindow(docs),
            ForegroundSignal.OwnUi(nudge),
            ForegroundSignal.AwarenessOverlay(nudge),
            ForegroundSignal.SystemSurface("com.android.systemui"),
            ForegroundSignal.Transient("com.google.android.inputmethod.latin"),
            ForegroundSignal.PipOnly(other),
            ForegroundSignal.NotForeground(docs)
        )
        val covered = (ends + keeps).map { it::class }.toSet()
        assertEquals(
            "every ForegroundSignal shape is decided here",
            ForegroundSignal::class.sealedSubclasses.toSet(),
            covered
        )
        ends.forEach { assertNull("$it", BlockLaunchGate.arrivalWatchAfterSignal(it, current)) }
        keeps.forEach { assertSame("$it", current, BlockLaunchGate.arrivalWatchAfterSignal(it, current)) }
    }

    // --- what a rescue does to the pending overlay ----------------------------------------------

    private val arrival = Arrival(targetPackage = docs, countedKeys = listOf("docs|HARD_BLOCK"))

    private fun pending(shown: Boolean, displaced: Boolean = false) = PendingOverlay(
        packageName = docs,
        launchedAtMs = 1_000L,
        windowShown = shown,
        id = 3L,
        decisionKey = "HARD_BLOCK",
        displaced = displaced
    )

    /**
     * THE SAME-APP TRAMPOLINE (the QA report's Docs run): the overlay never reached the screen and
     * no other app's window ever displaced it, so before the rescue the re-block is refused as a
     * duplicate of an overlay that will never be drawn. After it, the same gate re-shows it.
     */
    @Test
    fun `a rescue turns an unshown overlay's refused re-block into a re-show`() {
        val buried = pending(shown = false)
        val nowMs = buried.launchedAtMs + BlockLaunchGate.OVERLAY_ARRIVAL_CHECK_MS
        assertTrue(
            "fixture: the check lands inside the settle window, where nothing else would displace it",
            BlockLaunchGate.OVERLAY_ARRIVAL_CHECK_MS < BlockLaunchGate.OVERLAY_SETTLE_MS
        )
        fun decideWith(p: PendingOverlay?) = BlockLaunchGate.decide(
            target = docs,
            foreground = docs,
            walkAway = null,
            nowMs = nowMs,
            pendingOverlay = p,
            decisionKey = buried.decisionKey
        )
        assertEquals("before the rescue", Decision.DROP_ALREADY_PENDING, decideWith(buried))

        val rescued = BlockLaunchGate.pendingOverlayAfterRescue(buried, docs, arrival)!!
        assertTrue(rescued.displaced)
        assertEquals("the arrival is kept so the re-show counts nothing twice", arrival, rescued.displacedArrival)
        assertEquals(buried.id, rescued.id)
        assertEquals(Decision.RESHOW_HIDDEN, decideWith(rescued))
    }

    @Test
    fun `an overlay that did reach the screen is left for an ordinary launch`() {
        val shown = pending(shown = true)
        assertSame(shown, BlockLaunchGate.pendingOverlayAfterRescue(shown, docs, arrival))
        assertEquals(
            Decision.LAUNCH,
            BlockLaunchGate.decide(
                target = docs,
                foreground = docs,
                walkAway = null,
                nowMs = shown.launchedAtMs + BlockLaunchGate.OVERLAY_ARRIVAL_CHECK_MS,
                pendingOverlay = shown,
                decisionKey = shown.decisionKey
            )
        )
    }

    @Test
    fun `a rescue touches no other app's overlay and nothing that is already displaced`() {
        assertNull(BlockLaunchGate.pendingOverlayAfterRescue(null, docs, arrival))
        val elsewhere = pending(shown = false).copy(packageName = other)
        assertSame(elsewhere, BlockLaunchGate.pendingOverlayAfterRescue(elsewhere, docs, arrival))
        val displaced = pending(shown = false, displaced = true)
        assertSame(displaced, BlockLaunchGate.pendingOverlayAfterRescue(displaced, docs, arrival))
    }

    @Test
    fun `another app's arrival is never carried onto the rescued overlay`() {
        val foreign = Arrival(targetPackage = other, countedKeys = listOf("x"))
        val rescued = BlockLaunchGate.pendingOverlayAfterRescue(pending(shown = false), docs, foreign)!!
        assertTrue(rescued.displaced)
        assertNull(rescued.displacedArrival)
        assertFalse(rescued.windowShown)
    }
}
