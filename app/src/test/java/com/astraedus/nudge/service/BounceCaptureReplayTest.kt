package com.astraedus.nudge.service

import com.astraedus.nudge.domain.bounce.BounceDetector
import com.astraedus.nudge.domain.events.A11yCapture
import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.ForegroundSignal
import com.astraedus.nudge.domain.packages.FakePackageFacts
import com.astraedus.nudge.service.BounceHarness.Companion.PIXEL_LAUNCHER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #181's bench captures (Pixel 3, Android 12, v1.30.0; Android 17 was NOT run), replayed
 * through the real classifier and check-in with each capture's own `# WALL` lines as the walls.
 *
 * The bench did not reproduce the bug: two Delay-walled apps, walked away from, let through and
 * auto-kicked, never fired. These pin that baseline, prove the walls in the replay are real walls
 * (the positive extension fires), and model the second candidate the report's arithmetic points at
 * (2 apps + Nudge + launcher = 4): a launcher that reaches the check-in as an app.
 *
 * Layer L2 (classified-event replay). The clock is each capture's event time.
 */
class BounceCaptureReplayTest {

    private val contacts = "com.google.android.contacts"
    private val calendar = "com.google.android.calendar"
    private val extraApps = listOf("com.reddit.frontpage", "com.discord")

    private fun device() = FakePackageFacts(
        launchable = setOf(contacts, calendar) + extraApps,
        home = setOf(PIXEL_LAUNCHER, OTHER_LAUNCHER),
        caches = true
    )

    @Test
    fun `every bench capture names its walls and the two ruled apps`() {
        val problems = CAPTURES.mapNotNull { name ->
            val walls = BounceHarness.walls(name)
            val walled = walls.map { it.pkg }.toSet()
            when {
                walls.size < BounceDetector.MIN_WALLS -> "$name: only ${walls.size} WALL lines"
                walled != setOf(contacts, calendar) -> "$name: walls are for $walled"
                A11yCapture.header(name).none { it.contains("Android 17 was NOT run") } ->
                    "$name: header must say Android 17 was not run"
                else -> null
            }
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test
    fun `no bench capture fires the check-in`() {
        val fired = CAPTURES.mapNotNull { name ->
            val h = BounceHarness(device())
            h.replay(name)
            if (h.posted.isEmpty()) null else "$name: ${h.failureMessage()}"
        }
        assertEquals(fired.joinToString("\n"), emptyList<String>(), fired)
    }

    @Test
    fun `positive extension - each capture plus two more apps and a wall fires with MIN_APPS`() {
        // Proves the replay's walls arm and count, and that both captured apps counted: two more
        // apps and no more walls make exactly MIN_APPS, so a replay that registered nothing (or
        // only one of the two apps) would not fire here.
        assertEquals("fixture", BounceDetector.MIN_APPS, 2 + extraApps.size)
        val wrong = CAPTURES.mapNotNull { name ->
            val h = BounceHarness(device())
            h.replay(name)
            extraApps.forEach { h.open(it) } // no new wall: the capture's own walls must do it
            val alert = h.posted.singleOrNull()
            when {
                alert == null -> "$name: did not fire. ${h.failureMessage()}"
                alert.count != BounceDetector.MIN_APPS -> "$name: count ${alert.count}"
                alert.packages.toSet() != setOf(contacts, calendar) + extraApps -> "$name: ${alert.packages}"
                else -> null
            }
        }
        assertEquals(wrong.joinToString("\n"), emptyList<String>(), wrong)
    }

    @Test
    fun `the arithmetic candidate - a launcher that reaches the check-in as an app is not counted`() {
        // On the bench the launcher is Home and Nudge is OwnUi, so the count stays at two. If on
        // another device the launcher arrived as an app (an unresolved launcher set, a launcher the
        // service does not list), 2 apps + launcher + one more would read 4. Model it on the real
        // event stream: the same capture with its launcher renamed to one outside SYSTEM_PACKAGES,
        // and the service's launcher set still empty.
        assertFalse(OTHER_LAUNCHER in NudgeAccessibilityService.SYSTEM_PACKAGES)
        val wrong = CAPTURES.mapNotNull { name ->
            val h = BounceHarness(device(), launcherSet = emptySet())
            h.replay(name) { r ->
                if (r.packageName == PIXEL_LAUNCHER) r.copy(packageName = OTHER_LAUNCHER) else r
            }
            val launcherReachedIt = OTHER_LAUNCHER in h.fedWhileArmed
            // One more real app: with the launcher counted that would be four.
            h.open(extraApps.first())
            when {
                !launcherReachedIt -> "$name: the renamed launcher never reached the check-in (fixture)"
                h.posted.isNotEmpty() -> "$name: ${h.failureMessage()}"
                else -> null
            }
        }
        assertEquals(wrong.joinToString("\n"), emptyList<String>(), wrong)
    }

    @Test
    fun `the pre-181 rule fires on the renamed-launcher capture, so the test above is not vacuous`() {
        val missed = CAPTURES.mapNotNull { name ->
            val h = BounceHarness(device(), launcherSet = emptySet(), countEverything = true)
            h.replay(name) { r ->
                if (r.packageName == PIXEL_LAUNCHER) r.copy(packageName = OTHER_LAUNCHER) else r
            }
            // Two apps + the launcher + ONE more app: only a counted launcher makes it four.
            h.open(extraApps.first())
            if (h.posted.isNotEmpty()) null else "$name did not fire under the old rule. ${h.failureMessage()}"
        }
        assertEquals(missed.joinToString("\n"), emptyList<String>(), missed)
    }

    @Test
    fun `the bench launcher is Home and the overlay is Nudge's own window`() {
        val h = BounceHarness(device())
        val signals = CAPTURES.flatMap { A11yCapture.load(it) }
            .filter { it.type == A11yEventType.WINDOW_STATE_CHANGED }
            .filter { it.packageName == PIXEL_LAUNCHER || it.packageName == h.nudge }
            .map { h.classify(it) }
        assertTrue("fixture has launcher and overlay events", signals.isNotEmpty())
        val asApps = signals.filterIsInstance<ForegroundSignal.AppWindow>()
        assertEquals(emptyList<ForegroundSignal>(), asApps)
    }

    private companion object {
        val CAPTURES = listOf(
            "bounce-181-delay-walkaway",
            "bounce-181-delay-letthrough",
            "bounce-181-autokick-cooldown"
        )

        /** A launcher outside SYSTEM_PACKAGES (Nova's package). */
        const val OTHER_LAUNCHER = "com.teslacoilsw.launcher"
    }
}
