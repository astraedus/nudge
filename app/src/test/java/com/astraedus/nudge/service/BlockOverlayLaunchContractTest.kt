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

    private val mainActivitySource: String by lazy {
        read("main/java/com/astraedus/nudge/MainActivity.kt")
    }

    /** Not under `src/`, so it needs its own candidate search rather than [read]. */
    private val appBuildGradle: String by lazy {
        val candidates = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
        (candidates.firstOrNull { it.exists() }
            ?: error("build.gradle.kts not found from ${File("").absolutePath}"))
            .readText()
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
     * "Started" and "on screen" are different facts, and the whole duplicate-block bug was one
     * field standing in for both. The launch records the first, the overlay's own `onResume`
     * reports the second, and the bypass check is the only reader of the difference.
     */
    @Test
    fun `the overlay reports when it actually reaches the screen`() {
        assertTrue(
            "the launch must record that an overlay is on its way but not yet visible",
            launchHelper.contains("guard.onOverlayLaunched(targetPackage)")
        )
        // The DECISIONS moved into the pure `OverlayLifecycle`, where they are driven against the
        // real guard by `OverlayLifecycleGuardTest`. What no value test can see is whether this
        // activity still forwards the two callbacks at all — a lifecycle callback that quietly
        // stops calling the state machine is a silent no-op, so the forwarding is what is pinned
        // here.
        val onResume = stripComments(
            overlay.substringAfter("override fun onResume()").substringBefore("\n    }")
        )
        assertTrue(
            "only the activity can say when it is on screen; the accessibility stream cannot, " +
                "because the overlay task's first window arrives ahead of the overlay itself",
            onResume.contains("overlayLifecycle.onResumed()")
        )
        val onDestroy = stripComments(
            overlay.substringAfter("override fun onDestroy()").substringBefore("\n    }")
        )
        assertTrue(
            "and a destroyed overlay must stop suppressing bypasses",
            onDestroy.contains("overlayLifecycle.onDestroyed()")
        )
        assertTrue(
            "...but only for the overlay instance it actually owns: a stale pending overlay " +
                "cleared unconditionally would let a REPLACEMENT instance's bypass suppression be " +
                "wiped out by the finish of the one it replaced, which is already on screen by the " +
                "time the old one is destroyed. So the dismissal is reported BY ID, never bare",
            stripComments(overlay)
                .contains("blockLaunchGuard.onOverlayDismissed(effect.overlayId)")
        )
    }

    /**
     * ISSUE #36'S ORDERING. `claimConfrontation` must sit between the launch and the row: a claim
     * placed ABOVE the launch would refuse rows for blocks that were never shown (the overlay might
     * still be gated by `guard.decide`, whose refusal is unrelated to "have we already counted this
     * arrival"), and one placed BELOW the row -- or never reached at all -- would do nothing, the
     * exact shape of the 2500-interventions-in-a-day report.
     */
    @Test
    fun `the count is gated, and the gate is between the launch and the row`() {
        val start = service.indexOf("private suspend fun handleDecision(")
        assertTrue("handleDecision must exist", start >= 0)
        val body = stripComments(service.substring(start))
        val launch = index(body, "launchBlockOverlay(")
        val claim = index(body, "claimConfrontation(")
        val recorded = index(body, "wasBlocked = true")
        assertTrue(
            "the order must be launch, then claim, then row -- a claim above the launch drops " +
                "rows for blocks nobody saw, a claim below (or missing) the row lets the storm " +
                "keep inflating the count",
            launch < claim && claim < recorded
        )
    }

    /**
     * The arrival invariant is allowed to refuse exactly one thing: the `UsageEvent` row. It must
     * never be allowed to refuse the overlay itself, that would mean a storm SUPPRESSES the block
     * the user is actually facing, not merely its bookkeeping -- the opposite of what issue #36
     * asked for.
     */
    @Test
    fun `the row is the only thing the arrival invariant may refuse`() {
        val start = service.indexOf("private suspend fun handleDecision(")
        assertTrue("handleDecision must exist", start >= 0)
        val body = stripComments(service.substring(start))
        val claim = index(body, "claimConfrontation(")
        val recorded = index(body, "wasBlocked = true")
        assertFalse(
            "weakening enforcement here is the failure this forbids: when the invariant says " +
                "'no row', the overlay must still already be shown, so no startActivity may sit " +
                "between the claim and the row",
            body.substring(claim, recorded).contains("startActivity")
        )
        assertEquals(
            "launchBlockOverlay must be called exactly once in handleDecision, and unconditionally " +
                "ahead of the claim -- gating the launch itself behind claimConfrontation would let " +
                "the arrival invariant suppress the block, not just its row",
            1,
            Regex("""launchBlockOverlay\(""").findAll(body).count()
        )
    }

    /**
     * The `UsageEvent(..., wasBlocked = true, ...)` write is the thing the arrival invariant exists
     * to gate. A second writer anywhere else in the service would be a second block path that
     * bypasses `claimConfrontation` entirely, reopening issue #36 through a door the fix never
     * looked at.
     */
    @Test
    fun `exactly one wasBlocked writer exists in the service`() {
        assertEquals(
            "a new block path that writes its own UsageEvent(wasBlocked = true, ...) instead of " +
                "going through handleDecision's gated write would bypass claimConfrontation " +
                "entirely",
            1,
            Regex("""wasBlocked\s*=\s*true""").findAll(stripComments(service)).count()
        )
    }

    /**
     * THE STORM DIAGNOSTIC (issue #36). Before the arrival invariant, a launch loop showed up as an
     * inflated all-time count with no mechanism attached, a device session's worth of guessing to
     * explain. After it, the same loop produces no visible signal at all, which is worse: it now
     * has to be logged on purpose, once per storm and at a level that survives a normal logcat
     * filter, or the next field report starts from zero again.
     */
    @Test
    fun `the storm diagnostic exists, is w-level, and is not per-event`() {
        assertTrue(
            "launchBlockOverlay must feed every attempt to the guard's storm diagnostic",
            launchHelper.contains("guard.onLaunchAttempt(")
        )
        assertTrue(
            "the storm line must be logged at .w() so it survives a normal logcat filter, and it " +
                "must name what the storm actually was",
            launchHelper.contains(".w(") && launchHelper.contains("block overlay launch storm")
        )
        assertEquals(
            "the storm diagnostic must fire from guard.onLaunchAttempt's own per-storm gating, not " +
                "from a second log call scattered elsewhere -- a log call for every ATTEMPT rather " +
                "than every STORM is worse than no log at all: 2500 lines a day would push the " +
                "surrounding evidence (what was actually in front, what the gate decided) out of " +
                "logcat before anyone can read it",
            1,
            Regex(""""block overlay launch storm""").findAll(stripComments(service)).count()
        )
    }

    /**
     * BOTH UN-STREAMABLE DEPARTURES (issue #36). `EventClassifier` sees every accessibility event,
     * but Nudge's own MAIN window is classified `OwnUi` exactly like the block overlay is, so only
     * `isOwnAppWindowEvent` can tell the two apart, and a screen-off is a broadcast, never an
     * accessibility event at all. Miss either report and the arrival invariant silently stops
     * covering the case that mattered: a user leaving via one of these two paths and coming straight
     * back would be read as the SAME arrival, and owed a fresh confrontation would get none.
     */
    @Test
    fun `both un-streamable departures are reported`() {
        val code = stripComments(service)
        assertEquals(
            "onDeparture must be called exactly twice: the accessibility stream cannot describe " +
                "either of these departures on its own",
            2,
            Regex("""blockLaunchGuard\(\)\.onDeparture\(""").findAll(code).count()
        )
        val ownUiCheck = index(code, "isOwnAppWindowEvent(event)")
        val screenOff = index(code, "Intent.ACTION_SCREEN_OFF) return")
        val departures = Regex("""blockLaunchGuard\(\)\.onDeparture\(""")
            .findAll(code).map { it.range.first }.toList()
        assertTrue(
            "one onDeparture must sit inside the isOwnAppWindowEvent(event) branch -- Nudge's own " +
                "MAIN window is classified OwnUi exactly like our block overlay",
            departures.any { it in ownUiCheck..(ownUiCheck + 400) }
        )
        assertTrue(
            "the other onDeparture must sit inside the screen-off receiver -- a screen-off is a " +
                "broadcast, not an accessibility event, so it cannot arrive through the signal " +
                "pipeline at all",
            departures.any { it in screenOff..(screenOff + 400) }
        )
    }

    /**
     * ISSUE #36'S CLASS-NAME PIN. `BlockLaunchGate.MAIN_APP_ACTIVITY_CLASS` is a hardcoded string
     * literal, not `MainActivity::class.java.name` -- the gate is pure Kotlin with no Android
     * imports, which is what makes the arrival model JVM-testable at all, and nothing in the
     * compiler checks a string literal against a real class.
     *
     * `tasks/lessons.md` (2026-09-14) already records the exact failure mode this guards against:
     * `shouldClearForOwnPackageEvent` was DEAD in production for months because its class-name
     * predicate was compared against the wrong identity (applicationId `dev.astraedus.nudge` vs
     * classes `com.astraedus.nudge.*`), and its OWN unit tests passed the whole time because they
     * supplied their own value for the production constant instead of reading the real one. A
     * silently-wrong `MAIN_APP_ACTIVITY_CLASS` would silently disable the "the user opened Nudge"
     * departure -- the arrival would simply never close, and because the failure is an ABSENCE of a
     * signal rather than a wrong one, nothing else would notice.
     */
    @Test
    fun `MAIN_APP_ACTIVITY_CLASS names a real activity in the real package`() {
        assertTrue(
            "MainActivity.kt must actually declare the class this constant claims to name, not " +
                "just be assumed to",
            stripComments(mainActivitySource).contains("class MainActivity")
        )
        val namespace = Regex("""namespace\s*=\s*"([^"]+)"""")
            .find(appBuildGradle)?.groupValues?.get(1)
            ?: error("namespace not found in build.gradle.kts")
        assertEquals(
            "the constant must equal <namespace>.MainActivity, read from the build file, not a " +
                "value someone typed once and never checked against the app's actual identity -- " +
                "the same mismatch that left shouldClearForOwnPackageEvent dead for months",
            "$namespace.MainActivity",
            com.astraedus.nudge.domain.block.BlockLaunchGate.MAIN_APP_ACTIVITY_CLASS
        )
    }

    /**
     * ISSUE #26 AT THE FAIL-SAFE. The fail-safe finish deliberately reproduces the pop that used to
     * reveal the blocked app underneath, so the walk-away window that suppresses the re-block it
     * causes must be measured from the moment of THIS pop, not from the tap ~1200ms earlier -- a
     * window armed only at the tap could run out before the pop the fail-safe itself causes lands.
     */
    @Test
    fun `the walk-away fail-safe re-arms the window at the moment of the pop`() {
        // The ORDER (re-arm, then finish) is now a value assertion against the real guard, in
        // `OverlayLifecycleGuardTest.the walk-away fail-safe re-arms the window at the pop it
        // causes` — it runs the whole sequence on a real clock and proves, with a counterfactual,
        // that a window armed only at the tap has expired by the time the pop it caused lands.
        //
        // What remains source-level is the thing a value test cannot reach: that the posted
        // callback DELEGATES rather than deciding for itself. A fail-safe that re-derived "may I
        // still act" here would be a second copy of a rule whose whole history is copies drifting.
        val body = stripComments(
            overlay
                .substringAfter("private fun scheduleWalkAwayFinish(token: Int, delayMs: Long) {")
                .substringBefore("\n    }")
        )
        assertTrue(
            "the posted fail-safe must ask the state machine whether it may still act",
            body.contains("overlayLifecycle.onFailSafeFired(")
        )
        assertFalse(
            "and must not decide anything itself: no inline finish, no inline re-arm",
            Regex("""(^|\W)finish\(\)""").containsMatchIn(body) ||
                body.contains("blockLaunchGuard.")
        )
    }

    /**
     * The bypass rule must exist exactly once. It used to be a companion function on the service
     * AND is now the gate's; keeping both would be two answers to one question, which is the shape
     * that produced #5, #7, #19 and #28.
     */
    @Test
    fun `the overlay-bypass rule lives in exactly one place`() {
        assertFalse(
            "the old companion copy of the bypass rule must be gone, not merely unused",
            stripComments(service).contains("fun isOverlayBypassedByForeground(")
        )
        assertTrue(
            "and the dispatch must ask the guard, which knows whether the overlay is on screen yet",
            stripComments(service).contains("blockLaunchGuard().isGenuineBypass(")
        )
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
        // Both halves are now value assertions in `OverlayLifecycleTest` and
        // `OverlayLifecycleGuardTest`: the effect list returned for a walk-away is exactly
        // RecordWalkAway, MarkOverlayInactive, ArmWalkAwayWindow, GoHome, ScheduleFailSafeFinish —
        // so "armed before the go-home" and "never finishes inline" are read off the list rather
        // than off the spelling of a method body, and the guard test proves what each one buys.
        //
        // The residue is the forwarding, and it is worth pinning: a `navigateHome` that grew a
        // second decision of its own would put the once-only gate and the arming order back in two
        // places, which is how #26 arrived in the first place.
        val body = stripComments(
            overlay.substringAfter("private fun navigateHome()").substringBefore("\n    }")
        )
        assertTrue(
            "the walk-away must be one delegation to the state machine",
            body.contains("overlayLifecycle.onWalkAwayRequested(")
        )
        assertFalse(
            "finishing inline is the pop that reveals the blocked app, issue #26. onStop finishes " +
                "us once the launcher lands, and the fail-safe covers a go-home that never does",
            Regex("""(^|\W)finish\(\)""").containsMatchIn(body)
        )
        assertFalse(
            "nor may it arm, record or go home on its own account: the order of those is the fix",
            body.contains("blockLaunchGuard.") ||
                body.contains("recordWalkAway.") ||
                body.contains("goHome()")
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
        // Both numbers are plain constants now that the overlay's lifecycle decisions live in
        // `OverlayLifecycle`, so this compares the VALUES instead of regexing one of them out of a
        // source file — a regex that would have gone on passing by finding nothing if the constant
        // were ever renamed.
        val failSafe =
            com.astraedus.nudge.domain.block.OverlayLifecycle.WALK_AWAY_FINISH_FAILSAFE_MS
        assertTrue(
            "the overlay must give up before the service stops covering for it " +
                "(fail-safe ${failSafe}ms vs window " +
                "${com.astraedus.nudge.domain.block.BlockLaunchGate.WALK_AWAY_TRANSITION_MS}ms)",
            failSafe < com.astraedus.nudge.domain.block.BlockLaunchGate.WALK_AWAY_TRANSITION_MS
        )
    }
}
