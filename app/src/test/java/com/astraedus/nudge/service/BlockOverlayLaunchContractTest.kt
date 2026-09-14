package com.astraedus.nudge.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guard on WHERE this app is allowed to put a block overlay on screen.
 *
 * ## Why a test that reads source code
 *
 * Issues [#31](https://github.com/astraedus/nudge/issues/31) and
 * [#26](https://github.com/astraedus/nudge/issues/26) are both about a launch that happens at the
 * wrong MOMENT, and neither is visible in any value: the decision object is correct, the package is
 * correct, the rule is correct. What is wrong is that the code got there. That is the same reason
 * `EventDispatchOrderContractTest`, `HomeScreenPassthroughContractTest` and
 * `BlockOverlayWalkAwayContractTest` already read source.
 *
 * The structural fix is that four hand-rolled launches became one `launchBlockOverlay`, and every
 * gate lives in it. The failure this pins is the obvious next one: someone adds a fifth launch site
 * (a new block mode, a new trigger) and builds the intent inline the way all four used to. It
 * would work, it would pass every value-level test, and it would be ungated.
 */
class BlockOverlayLaunchContractTest {

    private fun read(relative: String): String {
        val candidates = listOf(File("src/$relative"), File("app/src/$relative"))
        return (candidates.firstOrNull { it.exists() }
            ?: error("$relative not found from ${File("").absolutePath}"))
            .readText()
    }

    private val service: String by lazy {
        read("main/java/com/astraedus/nudge/service/NudgeAccessibilityService.kt")
    }

    private val overlay: String by lazy {
        read("main/java/com/astraedus/nudge/ui/overlay/BlockOverlayActivity.kt")
    }

    private val timeRemaining: String by lazy {
        read("main/java/com/astraedus/nudge/service/TimeRemainingHandler.kt")
    }

    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .lines()
        .joinToString("\n") { line -> line.substringBefore("//") }

    /** The body of `launchBlockOverlay`, comments stripped. */
    private val launchHelper: String by lazy {
        val body = service.substringAfter("private fun launchBlockOverlay(")
        assertTrue("launchBlockOverlay must exist", body.length < service.length)
        stripComments(body.substringBefore("\n    /**"))
    }

    private fun index(haystack: String, needle: String): Int {
        val i = haystack.indexOf(needle)
        assertTrue("expected to find `$needle`", i >= 0)
        return i
    }

    /**
     * ONE launch site. Four copies of "build an intent, set the flag, startActivity" is four places
     * to remember a gate, and remembering it in three of four is exactly how issue #19's first fix
     * shipped, it guarded the branch that had been reported and missed the common case.
     */
    @Test
    fun `the block overlay is constructed in exactly one place`() {
        val constructions = Regex("""Intent\(\s*applicationContext,\s*BlockOverlayActivity::class\.java\s*\)""")
            .findAll(stripComments(service))
            .count()
        assertEquals(
            "every block overlay launch must go through launchBlockOverlay, a new trigger that " +
                "builds its own intent is a launch nothing gates",
            1,
            constructions
        )
        assertTrue(
            "and that one construction must be inside the helper",
            launchHelper.contains("BlockOverlayActivity::class.java")
        )
    }

    /**
     * The daily-limit hard block used to own a `Context` and start the activity itself, which put a
     * launch outside the service entirely. It is the launch most likely to land late, a 30-second
     * clock tick, not a foreground event.
     */
    @Test
    fun `the daily-limit hard block does not launch the overlay itself`() {
        // Comments stripped: the callback's own doc names the class it is no longer allowed to
        // start, so that the next reader knows what used to be there. A contract test that greps
        // raw source reads its own explanation as code.
        val code = stripComments(timeRemaining)
        assertFalse(
            "TimeRemainingHandler must report the limit through its callback, not start an activity",
            code.contains("BlockOverlayActivity")
        )
        assertFalse(
            "and must not hold a Context to do it with",
            code.contains("startActivity(")
        )
    }

    /** The gate runs, and it runs before anything is shown. */
    @Test
    fun `the launch helper consults the gate before starting the activity`() {
        val decide = index(launchHelper, "guard.decide(targetPackage)")
        val start = index(launchHelper, "startActivity(overlayIntent)")
        assertTrue("the gate must precede the launch", decide < start)
        assertTrue(
            "a refused launch must return false so the caller records nothing",
            launchHelper.contains("return false")
        )
        assertTrue(
            "and it must say why, with what was actually in front, 'dropped' and 'never " +
                "happened' being indistinguishable from a device is what cost the v1.12.0 cycle",
            launchHelper.contains("block overlay launch dropped") &&
                launchHelper.contains("foreground=")
        )
    }

    /**
     * The overlay flag is asserted in exactly one place on the service side, for the same reason the
     * intent is built in one place: a launch site that started the activity without marking would
     * leave `isOverlayActive` false under a live overlay, and one that marked without launching
     * would swallow every subsequent event for an overlay that is not there.
     */
    @Test
    fun `the overlay flag is asserted only by the launch helper`() {
        val withoutDeclaration = stripComments(service)
            .lines()
            .filterNot { it.contains("fun markOverlayActive(") }
            .joinToString("\n")
        assertEquals(
            "markOverlayActive must be called from launchBlockOverlay and nowhere else in the service",
            1,
            Regex("""markOverlayActive\(""").findAll(withoutDeclaration).count()
        )
        assertTrue(launchHelper.contains("markOverlayActive(attributedPackage)"))
    }

    /**
     * ISSUE #31'S ORDERING, and the part that is easy to get subtly wrong.
     *
     * Gating the launch but not the `UsageEvent` would leave the all-time Blocked count climbing for
     * overlays nobody ever saw, the stat inflation issue #19 measured at +11 in a single incident.
     * The gate must therefore come first, and the row must be conditional on it.
     */
    @Test
    fun `a block that is not shown is not recorded either`() {
        val start = service.indexOf("private suspend fun handleDecision(")
        assertTrue("handleDecision must exist", start >= 0)
        val body = stripComments(service.substring(start))
        val launch = index(body, "launchBlockOverlay(")
        val guard = index(body, "if (!launched) return")
        val usageEvent = index(body, "wasBlocked = true")
        assertTrue("the gate must precede the UsageEvent", launch < guard && guard < usageEvent)
    }

    /**
     * The gate's state comes from the ONE classification, at the one place that applies it. Feeding
     * it from the branches that happen to care is the shape that produced #5, #7, #19 and #28.
     */
    @Test
    fun `the launch guard is fed from the single post-classification application`() {
        val code = stripComments(service)
        assertEquals(
            "the guard must be fed exactly once, from applyForegroundSignal",
            1,
            Regex("""blockLaunchGuard\(\)\.onForegroundSignal\(""").findAll(code).count()
        )
        val apply = index(code, "private fun applyForegroundSignal(")
        val feed = index(code, "blockLaunchGuard().onForegroundSignal(")
        val sittingUpdate = index(code, "passthroughManager().onForegroundSignal(")
        assertTrue("the feed must be inside applyForegroundSignal", feed > apply)
        assertTrue(
            "and alongside the sitting update, so the two consumers of one signal cannot drift",
            sittingUpdate > apply && sittingUpdate - feed < 400
        )
    }

    /**
     * ISSUE #26 AT SOURCE LEVEL. `navigateHome` must arm the window BEFORE dispatching the go-home:
     * the blocked app's window can resurface on the very next frame, and a window armed after the
     * dispatch is a window armed after the event it exists to catch.
     *
     * It must also not `finish()` inline. That call is what pops this singleInstance activity's task
     * and reveals the blocked app underneath; letting `onStop` finish us instead means the finish
     * happens from the background, where it pops nothing forward.
     */
    @Test
    fun `the walk-away arms the launch window before going home, and does not finish itself`() {
        val body = stripComments(
            overlay.substringAfter("private fun navigateHome()").substringBefore("\n    }")
        )
        val arm = index(body, "blockLaunchGuard.onWalkAwayStarted(")
        val goHome = index(body, "goHome()")
        assertTrue("the window must be armed before the go-home is dispatched", arm < goHome)
        assertFalse(
            "finishing inline is the pop that reveals the blocked app, issue #26. onStop finishes " +
                "us once the launcher lands, and the fail-safe covers a go-home that never does",
            Regex("""(^|\W)finish\(\)""").containsMatchIn(body)
        )
        assertTrue(
            "a walk-away must still always terminate the overlay",
            body.contains("scheduleWalkAwayFinish()")
        )
    }

    /**
     * The two numbers are one mechanism. If the overlay's fail-safe finish ever fires it pops the
     * blocked app forward, and it must do so while the service-side window is still open to suppress
     * the block that would otherwise re-arm. Asserted rather than left to a comment, because the
     * relationship lives in two files.
     */
    @Test
    fun `the overlay's fail-safe finish lands inside the service's walk-away window`() {
        val failSafe = Regex("""WALK_AWAY_FINISH_FAILSAFE_MS\s*=\s*([\d_]+)""")
            .find(overlay)?.groupValues?.get(1)?.replace("_", "")?.toLong()
            ?: error("WALK_AWAY_FINISH_FAILSAFE_MS not found in BlockOverlayActivity")
        assertTrue(
            "the overlay must give up before the service stops covering for it " +
                "(fail-safe ${failSafe}ms vs window " +
                "${com.astraedus.nudge.domain.block.BlockLaunchGate.WALK_AWAY_TRANSITION_MS}ms)",
            failSafe < com.astraedus.nudge.domain.block.BlockLaunchGate.WALK_AWAY_TRANSITION_MS
        )
    }
}
