package com.astraedus.nudge.ui.overlay

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The redirect-app bubble's WIRING, which no value test can see: the overlay activity is not
 * JVM-constructible and Compose UI is not JVM-testable here. What a value test CAN see is covered
 * elsewhere: the walk-away's effect list (`OverlayLifecycleTest`), what the service then believes
 * (`OverlayLifecycleGuardTest`), and which apps may be offered (`RedirectAppPolicyTest`).
 *
 * Every assertion below describes a CLASS of defect:
 *  - a block body that renders without the bubble (the feature is "on every block screen", and the
 *    body list is DERIVED from the activity, so a sixth body added later is covered without anyone
 *    remembering this file);
 *  - a second walk-away writer, or a second way to open an app, growing inside the bubble or picker;
 *  - a launch failure that strands the user on a spent block.
 */
class RedirectAppContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
            ?: error("main source set not found from working dir ${File("").absolutePath}")

    private fun read(relativePath: String): String {
        val file = File(sourceRoot(), relativePath)
        assertTrue("$relativePath must exist", file.exists())
        return file.readText()
    }

    private fun code(source: String): String =
        source.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

    private val activity by lazy { code(read("com/astraedus/nudge/ui/overlay/BlockOverlayActivity.kt")) }

    /** The block bodies the activity renders, derived from its `when (mode)` block. */
    private val bodies: List<String> by lazy {
        val whenBlock = activity.substringAfter("when (mode) {").substringBefore("RedirectAppPickerHost(")
        Regex("""\b([A-Z]\w*Content)\(""").findAll(whenBlock).map { it.groupValues[1] }.distinct().toList()
    }

    @Test
    fun `every block body the overlay renders is found, including Nuke's`() {
        assertEquals(
            setOf("NukeBlockContent", "HardBlockContent", "DelayContent", "HoldContent", "BreathingContent"),
            bodies.toSet()
        )
    }

    @Test
    fun `every block body is handed the bubble, and every body renders it`() {
        val calls = Regex("""redirect = redirectBubble""").findAll(activity).count()
        assertEquals("one bubble per body call site", bodies.size, calls)

        bodies.forEach { body ->
            val source = code(read("com/astraedus/nudge/ui/overlay/$body.kt"))
            assertTrue("$body must take the redirect slot", source.contains("redirect: @Composable () -> Unit"))
            assertTrue("$body must render the redirect slot", source.contains("redirect()"))
        }
    }

    /**
     * The bubble is a walk-away. Its tap reaches the ONE gated path, `navigateHome`, which is what
     * records exactly one walk-away, arms the #26 window before leaving, and never grants passthrough.
     */
    @Test
    fun `the bubble leaves through the one walk-away path`() {
        assertTrue(activity.contains("onLaunch = { navigateHome(redirectPackage = it) }"))
        assertTrue(
            "the redirect app is launched only as a walk-away effect",
            activity.contains("is OverlayLifecycle.Effect.LaunchRedirectApp -> launchRedirectApp(effect.packageName)")
        )
        assertEquals(
            "launchRedirectApp has exactly one caller, the effect runner",
            1,
            Regex("""launchRedirectApp\(effect""").findAll(activity).count()
        )
    }

    @Test
    fun `a redirect app that cannot be launched still gets the user off the block`() {
        val body = activity.substringAfter("private fun launchRedirectApp(").substringBefore("\n    }")
        assertTrue("launch failure must fall back to the launcher", body.contains("goHome()"))
    }

    /**
     * Neither the bubble nor the picker may open an app, write a stat, grant a pass or finish the
     * overlay on its own account. Each of those is a decision the activity's walk-away owns.
     */
    @Test
    fun `the bubble and the picker are UI only`() {
        listOf(
            "com/astraedus/nudge/ui/redirect/RedirectAppBubble.kt",
            "com/astraedus/nudge/ui/redirect/RedirectAppPickerSheet.kt",
            "com/astraedus/nudge/ui/redirect/RedirectAppController.kt"
        ).forEach { path ->
            val source = code(read(path))
            listOf("startActivity", "finish()", "recordWalkAway", "passthroughManager", "requestGoHome")
                .forEach { forbidden ->
                    assertFalse("$path must not call $forbidden", source.contains(forbidden))
                }
        }
    }

    /**
     * The picker is a ModalBottomSheet, i.e. its own dialog window with its own back dispatcher, so
     * the back gesture closes the PICKER and never reaches the overlay's walk-away callback. A
     * rewrite into an in-window panel would need its own BackHandler, and without one back would
     * walk away mid-pick.
     */
    @Test
    fun `the picker owns the back gesture while it is open`() {
        val sheet = code(read("com/astraedus/nudge/ui/redirect/RedirectAppPickerSheet.kt"))
        assertTrue(sheet.contains("ModalBottomSheet(onDismissRequest = onDismiss"))
    }

    /** The timers hold still under the picker, and the picker itself is outside the pause. */
    @Test
    fun `the block body is paused while the picker is open`() {
        assertTrue(activity.contains("PauseWhile(paused = redirect.pickerOpen)"))
        val pausedRegion = activity.substringAfter("PauseWhile(paused = redirect.pickerOpen)")
            .substringBefore("RedirectAppPickerHost(redirect)")
        assertTrue("every body sits inside the pause", bodies.all { pausedRegion.contains("$it(") })
    }

    @Test
    fun `the paused lifecycle is held at STARTED and never raised`() {
        assertEquals(Lifecycle.State.STARTED, cappedLifecycleState(Lifecycle.State.RESUMED, paused = true))
        assertEquals(Lifecycle.State.RESUMED, cappedLifecycleState(Lifecycle.State.RESUMED, paused = false))
        Lifecycle.State.values().filter { !it.isAtLeast(Lifecycle.State.RESUMED) }.forEach { below ->
            assertEquals(below, cappedLifecycleState(below, paused = true))
            assertEquals(below, cappedLifecycleState(below, paused = false))
        }
    }

    /**
     * Each body scrolls only when it overflows. An enabled scroll container claims a vertical drag
     * even when it cannot move, which would cancel a HOLD whose thumb drifts.
     */
    @Test
    fun `block bodies scroll only when they overflow`() {
        val helper = code(read("com/astraedus/nudge/ui/overlay/OverlayContentScroll.kt"))
        assertTrue(helper.contains("verticalScroll(scroll, enabled = scroll.maxValue > 0)"))
        bodies.forEach { body ->
            assertTrue(
                "$body must use the shared overlay scroll",
                code(read("com/astraedus/nudge/ui/overlay/$body.kt")).contains(".overlayContentScroll(scroll)")
            )
        }
    }
}
