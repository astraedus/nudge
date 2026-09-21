package com.astraedus.nudge.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard for hold-to-unlock ([issue #35](https://github.com/astraedus/nudge/issues/35)).
 *
 * The RULES of the gesture are pure and unit-tested in `HoldToUnlockTest`. What cannot be tested as
 * a value is the thing that actually matters here: **where the hold is WIRED**. A press-and-hold
 * that opens an app is one authorisation away from being a second, unguarded door into every
 * blocked app, and "is there a second door" is a question about the shape of the code, not about any
 * number it computes.
 *
 * This repo already tests shape where behaviour is untestable — `BlockOverlayWalkAwayContractTest`,
 * `BlockOverlayLaunchContractTest`, `ContentFilterAssetTest` — and the failure direction here is the
 * worst one this codebase has: a blocker that quietly stops blocking. So the assertions describe the
 * CLASS. Any future edit that lets the hold reach `passthroughManager.grant` by its own route, or
 * that drops the exactly-once gate, or that renders the control without a screen-reader path, fails
 * here.
 */
class HoldToUnlockContractTest {

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
    private val control by lazy {
        stripComments(read("java/com/astraedus/nudge/ui/overlay/HoldToUnlockControl.kt"))
    }

    /** The two timed overlays: both end in a grant, so both owe the hold the same wiring. */
    private val timedContents = mapOf(
        "DelayContent.kt" to "java/com/astraedus/nudge/ui/overlay/DelayContent.kt",
        "BreathingContent.kt" to "java/com/astraedus/nudge/ui/overlay/BreathingContent.kt"
    )

    /**
     * THE assertion this whole file exists for.
     *
     * Waiting a timer out is the only thing in Nudge that may open a blocked app, and
     * `onTimerComplete` is the one place that does it — lifecycle-gated, because a countdown that
     * reached zero off-screen was the issue #8 bypass. Hold-to-unlock inserts a step BEFORE that
     * call; it must never acquire a `grant` of its own, which would be a door with none of
     * `onTimerComplete`'s guards on it.
     */
    @Test
    fun `passthrough is granted from exactly one place, and the hold is not it`() {
        assertEquals(
            "BlockOverlayActivity must grant passthrough from exactly one call site",
            1,
            Regex("""passthroughManager\.grant""").findAll(activity).count()
        )
        val onTimerComplete = activity
            .substringAfter("private fun onTimerComplete()")
            .substringBefore("\n    }")
        assertTrue(
            "the one grant must live in onTimerComplete, which is lifecycle-gated (issue #8)",
            onTimerComplete.contains("passthroughManager.grant(")
        )
        assertFalse(
            "the hold control must not be able to grant passthrough itself",
            control.contains("passthroughManager") || control.contains("PassthroughManager")
        )
        assertFalse(
            "nor may it finish the overlay behind the activity's back",
            control.contains("finish()")
        )
    }

    /**
     * The hold must reach the EXISTING completion callback, not a parallel one. `onComplete` is what
     * each overlay already called when its timer ran out; passing anything else here would be a
     * second completion path, and the two would drift the first time one of them grew a guard.
     */
    @Test
    fun `the hold completes through each overlay's existing completion callback`() {
        timedContents.forEach { (name, path) ->
            val source = stripComments(read(path))
            assertTrue(
                "$name must render HoldToUnlockControl once the timer finishes",
                source.contains("HoldToUnlockControl(")
            )
            val call = source.substringAfter("HoldToUnlockControl(").substringBefore(")")
            assertTrue(
                "$name must hand the hold the SAME onComplete the countdown used to call, " +
                    "never a second completion path",
                call.contains("onUnlock = onComplete")
            )
            assertEquals(
                "$name must render exactly one hold control",
                1,
                Regex("""HoldToUnlockControl\(""").findAll(source).count()
            )
        }
    }

    /**
     * "Off" must be the old behaviour exactly: the timer finishing opens the app, with no control to
     * find and no extra tap. A hold that renders at zero length would be an invisible dead end on a
     * screen the user cannot leave except by walking away.
     */
    @Test
    fun `with the hold disabled the timer still completes on its own`() {
        timedContents.forEach { (name, path) ->
            val source = stripComments(read(path))
            assertTrue(
                "$name must branch on HoldToUnlock.isEnabled rather than assuming a hold exists",
                source.contains("HoldToUnlock.isEnabled(holdToUnlockMs)")
            )
            assertTrue(
                "$name must call onComplete directly when no hold is configured",
                source.contains("if (holdEnabled) timerFinished = true else onComplete()")
            )
        }
    }

    /**
     * One hold, one grant. `HoldProgress.advance` returns true exactly once per completed hold and
     * is the only thing standing between a sustained press and two passthrough grants, so the
     * unlock call must sit inside that branch and nowhere else.
     */
    @Test
    fun `the unlock fires from the once-only completion of the hold machine`() {
        assertTrue(
            "the control must drive HoldProgress",
            control.contains("HoldProgress(holdDurationMs)")
        )
        assertTrue(
            "completion must come from advance(), the once-only gate",
            control.contains("progress.advance(")
        )
        assertEquals(
            "the control must invoke the unlock callback from exactly one place",
            1,
            Regex("""currentOnUnlock\(\)""").findAll(control).count()
        )
        val completion = control.substringAfter("if (progress.advance(now))").substringBefore("}")
        assertTrue(
            "the unlock must sit inside the advance() branch, not beside it",
            completion.contains("currentOnUnlock()")
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
            control.contains("repeatOnLifecycle(Lifecycle.State.RESUMED)")
        )
        assertTrue(
            "and must restart the hold on re-entry rather than resume it",
            control.substringAfter("repeatOnLifecycle(Lifecycle.State.RESUMED)")
                .substringBefore("while (true)")
                .contains("progress.press(")
        )
    }

    /**
     * TalkBack consumes touch exploration, so a real press-and-hold never reaches the gesture
     * detector: without a semantic action, a blind user would be looking at the one control on the
     * screen that can open their app and be unable to operate it. The accessible path runs the SAME
     * machine for the SAME duration — the friction is the delay, not the finger.
     */
    @Test
    fun `the hold is operable with a screen reader`() {
        assertTrue(
            "the control must publish a semantic click action for screen readers",
            control.contains("onClick(label =")
        )
        assertTrue(
            "the accessible action must start a hold on the same machine, not unlock directly",
            control.substringAfter("onClick(label =").substringBefore("}")
                .contains("accessibilityHold = true")
        )
        assertFalse(
            "the accessible action must never call the unlock callback directly",
            control.substringAfter("onClick(label =").substringBefore("}")
                .contains("currentOnUnlock")
        )
        assertTrue(
            "and the control must describe itself, or it announces as an unlabelled button",
            control.contains("contentDescription =")
        )
    }

    /**
     * A re-delivered block is a NEW attempt and owes a full hold. The activity is `singleInstance`,
     * so a second block arrives on the live instance through `onNewIntent`; the per-delivery
     * `key(blockToken)` is what discards the previous attempt's remembered state, and the hold
     * machine is remembered inside that subtree like every other countdown. Hooking into the same
     * token is what keeps this true without a second mechanism to forget about.
     */
    @Test
    fun `a re-delivered block starts its hold from zero`() {
        assertTrue(
            "render must mint a fresh delivery token",
            activity.contains("val blockToken = ++renderToken")
        )
        val content = activity.substringAfter("setContent {").substringBefore("\n    }")
        assertTrue(
            "every overlay must compose under that per-delivery key",
            content.contains("key(blockToken)")
        )
        timedContents.forEach { (name, path) ->
            val source = stripComments(read(path))
            assertTrue(
                "$name must take the hold duration as a parameter, so the activity owns the value",
                source.contains("holdToUnlockMs: Long = 0L")
            )
        }
    }

    /**
     * The duration is resolved through the one shared resolver, which already asks the question
     * item 2 of issue #35 will answer ("what is the EFFECTIVE hold for THIS block"). Reading the
     * preference straight into the composable would put that decision in two places the day a
     * per-rule override lands.
     */
    @Test
    fun `the hold duration is resolved once, through the shared resolver`() {
        assertTrue(
            "the activity must resolve the hold through HoldToUnlock.resolveDurationMs",
            activity.contains("HoldToUnlock.resolveDurationMs(")
        )
        assertEquals(
            "exactly one resolution, passed down to both timed overlays",
            2,
            Regex("""holdToUnlockMs = holdToUnlockMs""").findAll(activity).count()
        )
    }
}
