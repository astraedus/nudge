package com.astraedus.nudge.service

import com.astraedus.nudge.domain.surfaces.FollowingSteer
import com.astraedus.nudge.domain.surfaces.HostSurface
import com.astraedus.nudge.domain.surfaces.SteerAction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug this file exists for: **the Following steer opened Instagram's dropdown and never clicked
 * anything**, leaving it hanging open over the user's feed until they pressed back.
 *
 * Nothing was wrong with [FollowingSteer]. Every one of its 26 unit tests passed, including the one
 * asserting that a pending attempt yields [SteerAction.ClickFollowing] when the menu appears. What
 * was wrong was the CADENCE it was driven at: the service resolved pending attempts from
 * `detectAndEvaluateFeature`, which is debounced to `contentChangedDebounceMs` (2s), while the
 * steer's own timeout was 1.5s. The menu opens in ~250ms and fires a burst of content changes, all
 * of them swallowed by the debounce; by the time an observation was allowed through, the attempt had
 * already aged out. `ClickFollowing` was unreachable **by construction**, on every device, always.
 *
 * ## Why this is a different LAYER, not another unit test
 *
 * `docs/testing-strategy.md` rule (a): name the layer. A value-level test of the state machine cannot
 * see this, because the state machine is correct — the defect lives in the relationship between two
 * constants in two different files, and in which code path does the observing. That is exactly what
 * this repo's source-level contract tests are for, and it is the fourth time a defect here has been
 * "the tests were exhaustive about the wrong question".
 *
 * Two independent things are pinned, because either alone would let the bug back:
 *  1. the timeout is longer than the debounce, so even the slow path could resolve an attempt;
 *  2. the service observes pending attempts on a path that does NOT wait for that debounce.
 */
class FollowingSteerCadenceContractTest {

    private val servicePath = "main/java/com/astraedus/nudge/service/NudgeAccessibilityService.kt"

    private fun read(relative: String): String {
        val candidates = listOf(File("src/$relative"), File("app/src/$relative"))
        return (candidates.firstOrNull { it.exists() }
            ?: error("$relative not found from ${File("").absolutePath}"))
            .readText()
    }

    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .lines()
        .joinToString("\n") { line -> line.substringBefore("//") }

    private val code: String by lazy { stripComments(read(servicePath)) }

    /** The debounce the feature path imposes, read from production rather than restated. */
    private fun contentChangeDebounceMs(): Long {
        val m = Regex("""contentChangedDebounceMs\s*=\s*(\d[\d_]*)L""").find(code)
            ?: error("could not read contentChangedDebounceMs from $servicePath")
        return m.groupValues[1].replace("_", "").toLong()
    }

    /**
     * THE arithmetic that was wrong. Both numbers are read from production — a hand-typed copy of
     * either would agree with whoever last edited this test rather than with the app.
     */
    @Test
    fun `the steer's menu timeout outlives the content-change debounce`() {
        val debounce = contentChangeDebounceMs()

        assertTrue(
            "the steer's menu timeout (${FollowingSteer.DEFAULT_MENU_TIMEOUT_MS}ms) must be longer " +
                "than the content-change debounce (${debounce}ms) that gates observations, or a " +
                "pending attempt ages out before anything can ever see the menu and ClickFollowing " +
                "becomes unreachable on every device",
            FollowingSteer.DEFAULT_MENU_TIMEOUT_MS > debounce
        )
    }

    /**
     * The margin, not just the inequality: one debounce period of headroom is the difference between
     * "could resolve if an event lands at exactly the right moment" and "will resolve".
     */
    @Test
    fun `the timeout leaves room for more than one debounced observation`() {
        val debounce = contentChangeDebounceMs()

        assertTrue(
            "the timeout must allow at least two debounced observations, got " +
                "${FollowingSteer.DEFAULT_MENU_TIMEOUT_MS}ms against a ${debounce}ms debounce",
            FollowingSteer.DEFAULT_MENU_TIMEOUT_MS >= debounce * 2
        )
    }

    /**
     * The mechanism that actually fixes it. The service must consult a pending attempt BEFORE the
     * debounce gate, so the menu is seen the moment it opens rather than up to two seconds later.
     *
     * Asserted as an ORDERING, which is what the defect was: both lines existed, on the wrong sides
     * of the gate.
     */
    @Test
    fun `a pending steer attempt is resolved before the debounce gate`() {
        val fastPath = code.indexOf("completePendingSteer(packageName)")
        assertTrue("the service must resolve a pending steer attempt from the content-change path", fastPath >= 0)

        val supportedGate = code.indexOf("packageName !in InAppDetector.SUPPORTED_PACKAGES")
        assertTrue("could not locate the supported-packages gate", supportedGate >= 0)

        assertTrue(
            "completePendingSteer must run ABOVE the debounced feature path, otherwise the open " +
                "dropdown is not observed until the debounce elapses and the attempt has already " +
                "timed out -- the exact shape of the shipped bug",
            fastPath < supportedGate
        )
    }

    /** The fast path must be guarded, or every content change pays for a window sweep. */
    @Test
    fun `the fast path only runs while an attempt is in flight`() {
        assertTrue(
            "the pending-steer fast path must be guarded by isMenuPending so it costs nothing in " +
                "the steady state",
            Regex("""isMenuPending\)\s*completePendingSteer""").containsMatchIn(code)
        )
    }

    /**
     * The back press on give-up must be EVIDENCE-GATED.
     *
     * `CloseMenu` is emitted on timeout, i.e. exactly when we could not see the menu, which covers
     * both "it is open and we failed to find it" and "the user already dismissed it". Pressing back
     * is only correct in the first; in the second it is an unrequested back navigation inside the
     * user's app, potentially out of the feed or out of Instagram. So the service must look again
     * before acting, and do nothing when the menu is gone.
     */
    @Test
    fun `the give-up back press only fires with the menu actually in front of us`() {
        val handler = code.substring(code.indexOf("SteerAction.CloseMenu"))
            .substringBefore("performSteerAction(")
            .take(2_000)

        val guard = handler.indexOf("findMenuRoot(recipe) != null")
        val backPress = handler.indexOf("GLOBAL_ACTION_BACK")

        assertTrue("the CloseMenu branch must re-check for the menu before pressing back", guard >= 0)
        assertTrue("the CloseMenu branch must press back", backPress >= 0)
        assertTrue(
            "the menu re-check must come BEFORE the back press, or Nudge sends a back navigation " +
                "into the user's app on the strength of no evidence at all",
            guard < backPress
        )
    }

    /**
     * Driving the real state machine at the BROKEN cadence, to prove the old arithmetic really did
     * make the click unreachable rather than merely unlikely. This is the counterfactual
     * `docs/testing-strategy.md` rule (d) asks for.
     */
    @Test
    fun `the shipped failure reproduced, and the fix against the same sequence`() {
        // THE DEVICE SEQUENCE. The dropdown is a POPUP in its own window, and the old code only ever
        // looked at `rootInActiveWindow`, so `menuVisible` was FALSE even while the menu was plainly
        // on screen. The debounce then allowed exactly one look, at 2s -- past the old 1.5s timeout.
        val broken = FollowingSteer(menuTimeoutMs = 1_500L)
        assertEquals(SteerAction.OpenMenu, broken.onObservation(HostSurface.HOME_FEED, true, false, 0L))
        assertEquals(
            "the one observation the debounce allowed arrived after the timeout, with the menu " +
                "invisible because the wrong window was searched",
            SteerAction.CloseMenu,
            broken.onObservation(HostSurface.UNKNOWN, true, menuVisible = false, nowMs = 2_000L)
        )
        // On the SHIPPED code that branch returned None and simply cleared the attempt, which is why
        // Instagram's dropdown was left hanging open over the feed until the user pressed back.
        // CloseMenu is what makes that same dead end dismiss the menu instead.

        // THE FIX, against the identical sequence: the service now sweeps every window for the menu,
        // so the same moment reports menuVisible = true, and it is observed on the fast path at the
        // ~250ms the menu actually takes to appear rather than at the 2s the debounce allowed.
        val fixed = FollowingSteer()
        assertEquals(SteerAction.OpenMenu, fixed.onObservation(HostSurface.HOME_FEED, true, false, 0L))
        assertEquals(
            SteerAction.ClickFollowing,
            fixed.resolvePending(menuVisible = true, nowMs = 250L)
        )

        // ...and the longer timeout means even the slow path would still have completed it, which is
        // the belt-and-braces half: had the window sweep missed, a 2s observation is still in time.
        val slowButFixed = FollowingSteer()
        assertEquals(SteerAction.OpenMenu, slowButFixed.onObservation(HostSurface.HOME_FEED, true, false, 0L))
        assertEquals(
            SteerAction.ClickFollowing,
            slowButFixed.onObservation(HostSurface.UNKNOWN, true, menuVisible = true, nowMs = 2_000L)
        )
    }
}
