package com.astraedus.nudge.ui.overlay

import com.astraedus.nudge.domain.model.BlockMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard for the HOLD block mode
 * ([issue #35](https://github.com/astraedus/nudge/issues/35)).
 *
 * The RULES of the gesture are pure and unit-tested in `HoldProgressTest`, and the DECISION that
 * produces a HOLD block is value-tested in `BlockEngineTest`. What neither can see is the thing that
 * actually matters here: **where the hold is WIRED**. A press-and-hold that opens an app is one
 * careless edit away from being a second, unguarded door into every blocked app, and "is there a
 * second door" is a question about the shape of the code, not about any number it computes.
 *
 * This repo already tests shape where behaviour is untestable — `BlockOverlayWalkAwayContractTest`,
 * `BlockOverlayLaunchContractTest`, `ContentFilterAssetTest` — and the failure direction here is the
 * worst one this codebase has: a blocker that quietly stops blocking.
 *
 * The mode-coverage tests at the bottom are deliberately driven by `BlockMode.entries` rather than a
 * list typed out here. A mode missing from a label `when` is not a crash and not a failing
 * assertion: it is the raw enum name shown to a user, or every row of that mode filed under
 * "Other". Nothing fails, so nothing tells you — unless the test enumerates the enum itself.
 */
class HoldModeContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("main source set not found from working dir ${File("").absolutePath}")

    private fun read(relativePath: String): String {
        val file = File(sourceRoot(), relativePath)
        assertTrue("$relativePath must exist", file.exists())
        return file.readText()
    }

    /**
     * Comments stripped before matching. Every file involved here explains the rule it implements,
     * in prose that necessarily quotes the code — a contract test that greps raw source reads its
     * own explanation as evidence, and then passes because a comment mentions something the code no
     * longer does. (Learned the hard way; see `tasks/lessons.md`, 2026-09-11.)
     */
    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .lines()
        .joinToString("\n") { line -> line.substringBefore("//") }

    private val activity by lazy {
        stripComments(read("java/com/astraedus/nudge/ui/overlay/BlockOverlayActivity.kt"))
    }
    private val target by lazy {
        stripComments(read("java/com/astraedus/nudge/ui/overlay/HoldTarget.kt"))
    }
    private val content by lazy {
        stripComments(read("java/com/astraedus/nudge/ui/overlay/HoldContent.kt"))
    }

    /** The `when (mode)` body inside `render`'s `setContent`, comments already stripped. */
    private val renderedModes by lazy {
        activity.substringAfter("setContent {").substringBefore("\n    }")
    }

    /**
     * One mode's branch of that `when`. Taken to the blank line that separates branches, NOT to the
     * first `)` — that one lands in the middle of `onComplete = { onTimerComplete() }` and would
     * quietly make every assertion below it vacuous.
     */
    private fun branchFor(mode: BlockMode): String =
        renderedModes.substringAfter("BlockMode.${mode.name} ->").substringBefore("\n\n")

    /**
     * THE assertion this whole file exists for.
     *
     * Waiting a timer out is the only thing in Nudge that may open a blocked app, and
     * `onTimerComplete` is the one place that does it — lifecycle-gated, because a countdown that
     * reached zero off-screen was the issue #8 bypass. HOLD is a different way to spend that timer;
     * it must never acquire a `grant` of its own, which would be a door with none of
     * `onTimerComplete`'s guards on it.
     */
    @Test
    fun `passthrough is granted from exactly one place, and the hold is not it`() {
        assertEquals(
            "BlockOverlayActivity must grant passthrough from exactly one call site",
            1,
            Regex("""passthroughManager\.grant""").findAll(activity).count()
        )
        assertTrue(
            "the one grant must be reachable only as the GrantPassthrough effect, which " +
                "OverlayLifecycle emits only when the overlay is at least STARTED (issue #8): a " +
                "timer that ran out while backgrounded must never open the app",
            activity.contains("OverlayLifecycle.Effect.GrantPassthrough -> passthroughManager.grant")
        )
        val onTimerComplete = activity
            .substringAfter("private fun onTimerComplete()")
            .substringBefore("\n    }")
        assertTrue(
            "and the activity must hand the state machine the lifecycle fact it cannot read " +
                "itself, or that gate silently becomes unconditional",
            onTimerComplete.contains("lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)")
        )
        listOf("HoldTarget.kt" to target, "HoldContent.kt" to content).forEach { (name, source) ->
            assertFalse(
                "$name must not be able to grant passthrough itself",
                source.contains("passthroughManager") || source.contains("PassthroughManager")
            )
            assertFalse(
                "nor may $name finish the overlay behind the activity's back",
                source.contains("finish()")
            )
        }
    }

    /**
     * The hold must reach the activity's EXISTING completion callback, the same one DELAY and
     * BREATHING hand their countdowns. A parallel one would drift from it the first time either
     * grew a guard.
     */
    @Test
    fun `the hold completes through the same onTimerComplete every other timed overlay uses`() {
        assertTrue(
            "the HOLD branch must complete into onTimerComplete(), not a callback of its own",
            branchFor(BlockMode.HOLD).contains("onComplete = { onTimerComplete() }")
        )
        assertEquals(
            "onTimerComplete must be the completion callback for EVERY timed overlay, so there is " +
                "one completion path and not one per mode",
            3,
            Regex("""onComplete = \{ onTimerComplete\(\) \}""").findAll(activity).count()
        )
        assertTrue(
            "HoldContent must hand the target its own onComplete rather than a second path",
            content.contains("onHoldComplete = onComplete")
        )
        assertEquals(
            "HoldContent must render exactly one hold target",
            1,
            Regex("""HoldTarget\(""").findAll(content).count()
        )
    }

    /**
     * One hold, one grant. `HoldProgress.advance` returns true exactly once per completed hold and
     * is the only thing standing between a sustained press and two passthrough grants, so the
     * completion call must sit inside that branch and nowhere else.
     */
    @Test
    fun `the completion fires from the once-only completion of the hold machine`() {
        assertTrue(
            "the target must drive HoldProgress",
            target.contains("HoldProgress(holdDurationMs)")
        )
        assertTrue(
            "completion must come from advance(), the once-only gate",
            target.contains("progress.advance(")
        )
        assertEquals(
            "the target must invoke the completion callback from exactly one place",
            1,
            Regex("""currentOnHoldComplete\(\)""").findAll(target).count()
        )
        val completion = target.substringAfter("if (progress.advance(now))").substringBefore("}")
        assertTrue(
            "the completion must sit inside the advance() branch, not beside it",
            completion.contains("currentOnHoldComplete()")
        )
    }

    /**
     * Issue #8, one layer further in. A countdown that ran while the overlay was off-screen granted
     * passthrough invisibly; a hold that kept accruing while backgrounded would be the same bypass
     * wearing a new gesture. The drive loop is RESUMED-gated for that reason, and `press` restarts
     * on re-entry so wall-clock time spent away can never count toward opening an app.
     */
    @Test
    fun `the hold only progresses while the overlay is on screen`() {
        assertTrue(
            "the hold loop must be gated on RESUMED",
            target.contains("repeatOnLifecycle(Lifecycle.State.RESUMED)")
        )
        assertTrue(
            "and must restart the hold on re-entry rather than resume it",
            target.substringAfter("repeatOnLifecycle(Lifecycle.State.RESUMED)")
                .substringBefore("while (true)")
                .contains("progress.press(")
        )
        assertTrue(
            "letting go must abandon the attempt rather than pause it, including when the " +
                "composable is cancelled out from under the finger",
            target.contains("progress.release()")
        )
    }

    /**
     * TalkBack consumes touch exploration, so a real press-and-hold never reaches the gesture
     * detector. On a DELAY the user could still just wait; on a HOLD there is nothing else to do,
     * so without a semantic action a blind user would be looking at the one control on the screen
     * that opens their app and be unable to operate it — a block with no way through. The
     * accessible path runs the SAME machine for the SAME duration; the friction is the wait, not
     * the finger.
     */
    @Test
    fun `the hold is operable with a screen reader`() {
        assertTrue(
            "the target must publish a semantic click action for screen readers",
            target.contains("onClick(label =")
        )
        assertTrue(
            "the accessible action must start a hold on the same machine, not complete directly",
            target.substringAfter("onClick(label =").substringBefore("}")
                .contains("accessibilityHold = true")
        )
        assertFalse(
            "the accessible action must never call the completion callback directly",
            target.substringAfter("onClick(label =").substringBefore("}")
                .contains("currentOnHoldComplete")
        )
        assertTrue(
            "and the target must describe itself, or it announces as an unlabelled button",
            target.contains("contentDescription =")
        )
    }

    /**
     * Walking away must cost nothing, mid-hold included. On a HOLD the user's thumb is already on
     * the screen, so the one affordance that must never become harder to reach is the one that
     * lets them stop.
     */
    @Test
    fun `walking away is still available during a hold`() {
        assertTrue(
            "HoldContent must render the walk-away button",
            content.contains("""Text("I changed my mind")""")
        )
        assertTrue(
            "and route it to the activity's walk-away path, which never grants passthrough",
            branchFor(BlockMode.HOLD).contains("onCancel = { navigateHome() }")
        )
        assertTrue(
            "the daily escape hatch must be on this overlay too, like the other three",
            content.contains("EmergencyPassAction(")
        )
    }

    /**
     * A re-delivered block is a NEW attempt and owes a full hold. The activity is `singleInstance`,
     * so a second block arrives on the live instance through `onNewIntent`; the per-delivery
     * `key(blockToken)` is what discards the previous attempt's remembered state, and the hold
     * machine is remembered inside that subtree like every other timer. Hooking into the same token
     * is what keeps this true without a second mechanism to forget about.
     */
    @Test
    fun `a re-delivered block starts its hold from zero`() {
        // The token itself is minted by `OverlayLifecycle.onDelivered`, and that it STRICTLY
        // INCREASES on every delivery — including a re-delivery of an identical block — is a value
        // test in `OverlayLifecycleTest`. What can only be checked here is that render actually
        // composes under the token that machine hands out, rather than a second one of its own.
        assertTrue(
            "render must key the subtree on the state machine's per-delivery token",
            activity.contains("val blockToken = overlayLifecycle.renderToken")
        )
        assertTrue(
            "every overlay must compose under that per-delivery key",
            renderedModes.contains("key(blockToken)")
        )
        assertTrue(
            "and the hold machine must be remembered inside that subtree, keyed on the duration",
            target.contains("remember(holdDurationMs) { HoldProgress(holdDurationMs) }")
        )
    }

    /**
     * The hold's length is the RULE's `delaySeconds` — the same number, the same picker, the same
     * meaning as a delay. Reading it from anywhere else (a preference, a constant) is how HOLD
     * would quietly stop being "the delay, held".
     */
    @Test
    fun `the hold length is the rule's own duration, carried on the block intent`() {
        assertTrue(
            "the HOLD branch must spend the intent's delaySeconds",
            branchFor(BlockMode.HOLD).contains("holdSeconds = delaySeconds")
        )
        assertTrue(
            "and HoldContent must pass exactly that through, in milliseconds",
            content.contains("holdDurationMs = holdSeconds.toLong() * 1000L")
        )
    }

    // ── Every mode is spelled out everywhere a mode is spelled out ──

    /**
     * Modes that can appear on a `usage_events` row, i.e. everything a `BlockDecision.Block` can
     * carry. [BlockMode.NONE] blocks nothing, so it never reaches a label or a chart.
     */
    private val blockingModes = BlockMode.entries.filter { it != BlockMode.NONE }

    /**
     * Every screen that turns a stored mode STRING into words must name every mode.
     *
     * These are `when (mode: String)` blocks with an `else`, so a missing mode does not fail to
     * compile and does not throw: it renders "Other", or the raw enum name, at the user. Enumerating
     * `BlockMode.entries` here is what makes that loud.
     */
    @Test
    fun `every block mode has a label on every screen that labels modes`() {
        val labellers = mapOf(
            "InsightsCalculator.modeLabel" to
                "java/com/astraedus/nudge/ui/screens/stats/InsightsCalculator.kt",
            "AppDetailScreen.formatBlockMode" to
                "java/com/astraedus/nudge/ui/screens/stats/AppDetailScreen.kt",
            "ActiveRulesViewModel.formatMode" to
                "java/com/astraedus/nudge/ui/screens/rules/ActiveRulesViewModel.kt",
            "RuleEditorViewModel's rule summary" to
                "java/com/astraedus/nudge/ui/screens/rules/RuleEditorViewModel.kt",
            "InterventionsScreen.modeColor" to
                "java/com/astraedus/nudge/ui/screens/stats/InterventionsScreen.kt"
        )
        labellers.forEach { (what, path) ->
            val source = stripComments(read(path))
            blockingModes.forEach { mode ->
                assertTrue(
                    "$what must have a branch for BlockMode.${mode.name}, or it shows the raw " +
                        "enum name (or files the mode under \"Other\") with nothing failing",
                    source.contains("\"${mode.name}\"")
                )
            }
        }
    }

    /**
     * The overlay must be able to RENDER every mode it can be launched with.
     *
     * `BlockOverlayActivity.render` switches on an exhaustive `when (mode)`, so this cannot be
     * missing — but a future mode could be added to the enum and quietly routed to `Unit` beside
     * NONE, which would be a block that shows nothing and dismisses itself: an app that is
     * configured as blocked and opens anyway.
     */
    @Test
    fun `every blocking mode renders a real overlay body`() {
        blockingModes.forEach { mode ->
            assertTrue(
                "BlockMode.${mode.name} must render an overlay, not fall through to Unit",
                branchFor(mode).contains("Content(")
            )
        }
    }
}
