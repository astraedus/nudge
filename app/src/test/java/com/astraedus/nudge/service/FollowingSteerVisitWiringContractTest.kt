package com.astraedus.nudge.service

import com.astraedus.nudge.testing.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue [#56](https://github.com/astraedus/nudge/issues/56): where the Following steer is allowed to
 * learn that a visit ended.
 *
 * `FollowingSteerVisitLifecycleTest` proves the BEHAVIOUR given the wire. This file is about the
 * wire's placement, which no value-level test can see: the steer's memory used to be cleared inside
 * `hideAllOverlays`, one of whose two callers is the `ACTION_SCREEN_OFF` receiver. Nothing about
 * either object was wrong. What was wrong was which line called which, and a display timeout on
 * Instagram's home feed therefore re-steered a user who had deliberately chosen that feed.
 *
 * ## The shape of the assertions
 *
 * `docs/testing-strategy.md` rule (e): check an **absence of a whole defect class**, or a **count
 * over a discovered set** - never the presence of a particular spelling. So:
 *
 *  - the screen-off receiver, and the overlay teardown it calls, must not reach the steer at all
 *    (absence, and it is the defect class itself: "a broadcast decides a visit ended");
 *  - the set of REASONS the steer's memory is cleared for is discovered from the source and
 *    compared as a set, so a fifth caller cannot appear without someone editing this list and
 *    saying why.
 *
 * The one presence assertion is the departure verdict actually reaching the reset, and it is here
 * for the reason `EventDispatchOrderContractTest` keeps its `onInteraction` twin: a model fix that
 * nobody calls is a silent no-op with a green suite, and this repo has shipped that exact thing.
 */
class FollowingSteerVisitWiringContractTest {

    private val source: String by lazy {
        SourceScan.kotlinFile("com/astraedus/nudge/service/NudgeAccessibilityService.kt").readText()
    }

    /**
     * Comments stripped, and load-bearing rather than tidiness: the code this test reads carries
     * long comments that NAME the lines they say are gone. Grepping raw source would read an
     * explanation as the thing it explains - `EventDispatchOrderContractTest` has had to say so
     * three times.
     */
    private fun stripComments(text: String): String = SourceScan.stripComments(text)

    private val code: String by lazy { stripComments(source) }

    private fun bodyBetween(startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("could not find `$startMarker` - has it been renamed?", start >= 0)
        val end = source.indexOf(endMarker, start)
        assertTrue("could not find `$endMarker` after `$startMarker`", end > start)
        return stripComments(source.substring(start, end))
    }

    /** Everything the steer's once-per-visit memory could be touched through. */
    private val steerStateSymbols = listOf(
        "followingSteer.reset()",
        "syntheticClicks.reset()",
        "resetHostAppActuation("
    )

    /**
     * THE DEFECT, as an absence. A broadcast that fires on lack of INPUT cannot be allowed to
     * decide that a visit ended - that premise was removed from the sitting and the arrival by
     * [#54](https://github.com/astraedus/nudge/issues/54), and this was the last place still
     * holding it.
     */
    @Test
    fun `the screen-off receiver cannot reach the steer's memory`() {
        val receiver = bodyBetween(
            "private val screenOffReceiver by lazy {",
            "private val imeSettingObserver by lazy {"
        )
        assertTrue(
            "the receiver must still start the sitting's away clock - that is the #54 mechanism " +
                "the steer now borrows its verdict from",
            receiver.contains("onScreenOff(sittingClock())")
        )
        steerStateSymbols.forEach { symbol ->
            assertFalse(
                "the ACTION_SCREEN_OFF receiver must not clear host-app actuation state (`$symbol`): " +
                    "Android blanks the display on lack of INPUT, not lack of attention, so the " +
                    "broadcast alone cannot tell a thirty-second read from putting the phone down. " +
                    "The verdict belongs to SittingTracker's return window, and reaches the steer " +
                    "through onSittingEnded",
                receiver.contains(symbol)
            )
        }
    }

    /**
     * The same absence one level down, because the defect arrived by SHARING: the two lines sat in
     * `hideAllOverlays`, which the receiver above calls for a completely different reason (a dark
     * screen owes no overlay). Hiding something and forgetting something are different jobs, and
     * putting them back in one function re-creates the bug without anyone editing the receiver.
     */
    @Test
    fun `the overlay teardown hides overlays and forgets nothing`() {
        val body = bodyBetween("private fun hideAllOverlays() {", "private fun resetHostAppActuation(")
        steerStateSymbols.forEach { symbol ->
            assertFalse(
                "hideAllOverlays must not clear `$symbol` - it has two callers with two different " +
                    "reasons, and only one of them means the visit is over",
                body.contains(symbol)
            )
        }
        assertTrue(
            "it must still hide the tab cover, which IS just something being drawn",
            body.contains("tabCoverOverlayManager().hide()")
        )
    }

    /**
     * A DISCOVERED SET, not a hand-list of call sites. Every reason a visit is declared over is
     * read out of the source and compared whole, so a third one fails here and forces the question
     * this bug family keeps failing to ask: *is that actually the user leaving?*
     *
     * The two that are legitimate:
     *  - `sitting_ended_*` - the one departure verdict, shared with the grant and the arrival;
     *  - `globally_disabled` - a disabled Nudge behaves as if uninstalled (two call sites, one
     *    reason: the synchronous hot-path gate and the toggle collector).
     */
    @Test
    fun `a visit is declared over for exactly two reasons`() {
        val reasons = Regex("""resetHostAppActuation\("([^"]*)"\)""")
            .findAll(code)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(
            "a new reason to forget a visit is a new definition of 'the user left', and this " +
                "subsystem has now paid for that four times (#28, #36, #54, #64). Justify it here " +
                "or route it through the sitting's verdict",
            setOf("sitting_ended_\${ended.cause}", "globally_disabled"),
            reasons
        )
    }

    /**
     * The count, over a discovered set rather than a list of places I happened to think of. The
     * steer's memory is cleared in exactly TWO spots and both are accounted for; a third is either
     * a new definition of leaving or the bug coming back.
     *
     * The second one is the per-rule toggle, which is not a departure claim at all - switching the
     * feature off makes it inert, and switching it back on must start from a clean arrival rather
     * than inheriting an "already steered" recorded while it was. It deliberately does NOT go
     * through [resetHostAppActuation]: it runs on the service's IO scope, and `SyntheticClickWindow`
     * is written and read on Main.
     */
    @Test
    fun `the steer's memory is cleared in exactly two places, and a screen-off is not one of them`() {
        assertEquals(
            "exactly two writers: the shared visit-ended reset, and the per-rule toggle going off",
            2,
            Regex(Regex.escape("followingSteer.reset()")).findAll(code).count()
        )
        assertEquals(
            "the synthetic-click record is cleared only by the shared visit-ended reset",
            1,
            Regex(Regex.escape("syntheticClicks.reset()")).findAll(code).count()
        )

        val helper = bodyBetween("private fun resetHostAppActuation(", "private fun onGlobalDisabled(")
        assertTrue(
            "one writer must be the shared reset",
            helper.contains("followingSteer.reset()") && helper.contains("syntheticClicks.reset()")
        )

        val steerPath = bodyBetween("private suspend fun maintainFollowingSteer(", "private fun completePendingSteer(")
        assertTrue(
            "the other must be the per-rule toggle path, next to the toggle read that justifies it",
            steerPath.contains("isFollowingSteerEnabled(packageName)") &&
                steerPath.contains("followingSteer.reset()")
        )
    }

    /**
     * The fix reaching production. A pure model that nobody calls is the failure mode
     * `EventDispatchOrderContractTest` guards for #64, and it is the one place a presence
     * assertion earns its keep.
     */
    @Test
    fun `the sitting's departure verdict ends the steer's visit`() {
        val body = bodyBetween("private fun onSittingEnded(", "private fun isOwnAppWindowEvent(")
        assertTrue(
            "the steer must take its 'new visit' verdict from the same place the grant takes it " +
                "from, or #56 is a comment rather than a fix",
            body.contains("resetHostAppActuation(")
        )
        assertTrue(
            "and the screen-off arrival report must still be there - #54's half of this method",
            body.contains("onDeparture(\"screen_off\")")
        )
    }
}
