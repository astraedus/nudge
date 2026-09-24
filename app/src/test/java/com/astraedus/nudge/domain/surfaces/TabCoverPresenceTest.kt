package com.astraedus.nudge.domain.surfaces

import com.astraedus.nudge.domain.events.ForegroundSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every [ForegroundSignal] variant, against [TabCoverPresence.shouldKeep].
 *
 * The split under test is claim versus non-claim: only `AppWindow`, `Home`, `OwnUi` and
 * `SystemSurface` say anything about what is in front, and reading a non-claim as "the user left" is
 * the mistake that produced issues #19 and #41. The exhaustiveness test at the bottom is the one that
 * matters most — it fails when a new signal variant is added and nobody decided what the cover should
 * do about it.
 */
class TabCoverPresenceTest {

    private val host = "com.instagram.android"
    private val own = "dev.astraedus.nudge"

    // -----------------------------------------------------------------------------------------
    // Signals that DO make a claim about the foreground
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an app window for the covered package keeps the cover`() {
        assertTrue(TabCoverPresence.shouldKeep(ForegroundSignal.AppWindow(host), host))
    }

    @Test
    fun `an app window for a different package drops the cover`() {
        assertFalse(
            "the user switched apps; a cover left behind would float over the new one",
            TabCoverPresence.shouldKeep(ForegroundSignal.AppWindow("com.google.android.youtube"), host)
        )
    }

    /** No cover is up, so no `AppWindow` can match and there is nothing to keep. */
    @Test
    fun `an app window with no covered package keeps nothing`() {
        assertFalse(TabCoverPresence.shouldKeep(ForegroundSignal.AppWindow(host), null))
    }

    @Test
    fun `the launcher drops the cover`() {
        assertFalse(
            TabCoverPresence.shouldKeep(ForegroundSignal.Home("com.google.android.apps.nexuslauncher"), host)
        )
    }

    @Test
    fun `a system surface drops the cover`() {
        assertFalse(
            "the shade or a permission dialog covers the nav bar itself; a cover under it would " +
                "be drawn over system UI",
            TabCoverPresence.shouldKeep(ForegroundSignal.SystemSurface("com.android.systemui"), host)
        )
    }

    @Test
    fun `nudge's own ui drops the cover`() {
        assertFalse(TabCoverPresence.shouldKeep(ForegroundSignal.OwnUi(own), host))
    }

    // -----------------------------------------------------------------------------------------
    // Signals that make NO claim
    // -----------------------------------------------------------------------------------------

    /**
     * The case the whole class exists for. The tab cover IS an awareness overlay, so its own window
     * fires window and content events like any other. A rule that tore the cover down on
     * `AwarenessOverlay` would order the cover hidden the instant it appeared, on its own event —
     * the identical shape as issue #41, where the awareness overlay's events moved the foreground
     * onto Nudge and every daily-limit tick was refused while the user sat still in the blocked app.
     */
    @Test
    fun `an awareness overlay keeps the cover because the cover is one`() {
        assertTrue(
            "if this is false the cover hides itself the moment it is shown (issue #41's shape)",
            TabCoverPresence.shouldKeep(ForegroundSignal.AwarenessOverlay(own), host)
        )
    }

    /** ...including when the cover is the only thing up and the host app is what it covers. */
    @Test
    fun `an awareness overlay keeps the cover regardless of which package it carries`() {
        assertTrue(TabCoverPresence.shouldKeep(ForegroundSignal.AwarenessOverlay(own), own))
    }

    @Test
    fun `a keyboard keeps the cover`() {
        assertTrue(
            "the app underneath has not changed (issue #5)",
            TabCoverPresence.shouldKeep(ForegroundSignal.Transient("com.google.android.inputmethod.latin"), host)
        )
    }

    @Test
    fun `a picture-in-picture bubble keeps the cover`() {
        assertTrue(
            "PiP fires events from somewhere the user is not (issue #19)",
            TabCoverPresence.shouldKeep(ForegroundSignal.PipOnly("com.google.android.youtube"), host)
        )
    }

    /** The commonest signal of all while a user scrolls. A drop here would blink the cover constantly. */
    @Test
    fun `a non-foreground event keeps the cover`() {
        assertTrue(TabCoverPresence.shouldKeep(ForegroundSignal.NotForeground(host), host))
    }

    // -----------------------------------------------------------------------------------------
    // The gate that matters
    // -----------------------------------------------------------------------------------------

    /**
     * Every `ForegroundSignal` variant is decided here, derived from the sealed interface rather than
     * hand-listed (rule (b)).
     *
     * `shouldKeep`'s `when` has no `else`, so a new variant is a compile error in production — but a
     * compile error only proves somebody typed a branch. This asserts the test file covers the same
     * closed set, so adding a variant also fails until it has been *decided about* in a test.
     */
    @Test
    fun `every foreground signal variant is covered by this test class`() {
        // Plain Java reflection over the sealed interface's nested types, not `KClass.sealedSubclasses`
        // — the latter needs kotlin-reflect on the test runtime classpath, which is here only
        // transitively through MockK and would turn a dependency tidy-up into a mystery failure.
        val variants = ForegroundSignal::class.java.declaredClasses
            .filter { ForegroundSignal::class.java.isAssignableFrom(it) }
            .map { it.simpleName }
            .toSet()
        val decided = setOf(
            "AppWindow", "Home", "SystemSurface", "OwnUi",
            "AwarenessOverlay", "Transient", "PipOnly", "NotForeground"
        )
        assertEquals(
            "a ForegroundSignal variant was added or removed — decide here what the tab cover " +
                "should do about it, then add it to this set",
            variants,
            decided
        )
    }

    /**
     * The claim/non-claim split as one property over the whole closed set: exactly four variants may
     * ever drop the cover, and they are the four that assert something about the foreground.
     */
    @Test
    fun `exactly the four claim-bearing signals can drop the cover`() {
        val signals = listOf(
            ForegroundSignal.AppWindow(host),
            ForegroundSignal.Home("com.google.android.apps.nexuslauncher"),
            ForegroundSignal.SystemSurface("com.android.systemui"),
            ForegroundSignal.OwnUi(own),
            ForegroundSignal.AwarenessOverlay(own),
            ForegroundSignal.Transient("com.google.android.inputmethod.latin"),
            ForegroundSignal.PipOnly("com.google.android.youtube"),
            ForegroundSignal.NotForeground(host)
        )
        // Driven with a covered package that no signal above matches, so `AppWindow`'s package
        // comparison lands on the drop side and the partition is by variant alone.
        val dropped = signals
            .filterNot { TabCoverPresence.shouldKeep(it, "com.example.other") }
            .map { it::class.simpleName }
            .toSet()
        assertEquals(
            setOf("AppWindow", "Home", "SystemSurface", "OwnUi"),
            dropped
        )
    }
}
