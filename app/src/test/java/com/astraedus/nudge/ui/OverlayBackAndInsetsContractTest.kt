package com.astraedus.nudge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard for the two behaviours `targetSdk = 36` (Android 16) changed underneath the
 * full-screen overlays: predictive back, and enforced edge-to-edge.
 *
 * **Predictive back is the safety-critical half.** From targetSdk 36 the platform no longer calls
 * `onBackPressed()` and no longer dispatches `KEYCODE_BACK`, so the three overrides that used to
 * implement "back walks away" became dead code and the system default took over. That default is
 * `finish()`, and every one of these activities is `singleInstance` with an empty `taskAffinity`,
 * so finishing pops back to the task underneath: for [com.astraedus.nudge.ui.overlay.BlockOverlayActivity]
 * that is **the blocked app**, and for [com.astraedus.nudge.ui.lock.StrictModeGuardActivity] it is the
 * Settings escape route the guard exists to stand in front of. The back gesture would have been a
 * one-swipe bypass of every block Nudge enforces.
 *
 * None of these activities is JVM-testable (real Android lifecycle, Hilt field injection, Compose,
 * and predictive back is a platform behaviour that only exists on an API-36 device), and the defect
 * is in the SHAPE of the code rather than any value a unit test can read, so this pins the shape,
 * exactly as `BlockOverlayWalkAwayContractTest` and `HomeScreenPassthroughContractTest` already do.
 *
 * The assertions describe the CLASS: no activity anywhere in the app may go back to overriding
 * `onBackPressed()`, and no manifest may reach for the `enableOnBackInvokedCallback="false"`
 * opt-out, which Google documents as temporary and will remove at a later target.
 */
class OverlayBackAndInsetsContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("main source set not found from working dir ${File("").absolutePath}")

    private fun read(relativePath: String): String {
        val file = File(sourceRoot(), relativePath)
        assertTrue("$relativePath must exist", file.exists())
        return file.readText()
    }

    private fun allKotlinSources(): List<File> =
        sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /**
     * Every activity whose back button carries meaning, and the handler each one owes. Adding a
     * fourth overlay without a row here is fine; adding one that overrides `onBackPressed()` is not,
     * the sweep below catches it.
     */
    private val backHandlers = mapOf(
        "java/com/astraedus/nudge/ui/overlay/BlockOverlayActivity.kt" to "navigateHome()",
        "java/com/astraedus/nudge/ui/lock/StrictModeGuardActivity.kt" to "onChangedMind()",
        "java/com/astraedus/nudge/ui/overlay/PipEscapeActivity.kt" to "onDismiss()"
    )

    /** Root composables of the full-screen overlays, whose content must sit inside the safe area. */
    private val overlayContents = listOf(
        "java/com/astraedus/nudge/ui/overlay/HardBlockContent.kt",
        "java/com/astraedus/nudge/ui/overlay/DelayContent.kt",
        "java/com/astraedus/nudge/ui/overlay/BreathingContent.kt",
        "java/com/astraedus/nudge/ui/overlay/PipEscapeContent.kt"
    )

    /**
     * The migration itself. `onBackPressed()` is not called at all from targetSdk 36, so an override
     * is silently dead code, and dead code here means the system's `finish()` decides where the user
     * lands.
     */
    @Test
    fun `no activity in the app overrides onBackPressed`() {
        val offenders = allKotlinSources()
            .filter { it.readText().contains("override fun onBackPressed(") }
            .map { it.name }
        assertEquals(
            "targetSdk 36 never calls onBackPressed(); use onBackPressedDispatcher.addCallback",
            emptyList<String>(),
            offenders
        )
    }

    /**
     * Each overlay must register an ALWAYS-ENABLED callback wired to the same handler its old
     * override called. A disabled callback, or one wired to the wrong handler, is the bypass.
     */
    @Test
    fun `each overlay registers an always-enabled back callback for its own handler`() {
        backHandlers.forEach { (path, handler) ->
            val source = read(path)
            val body = source.substringAfter("private fun registerBackHandler()", "")
            assertTrue("$path must declare registerBackHandler()", body.isNotEmpty())
            assertTrue(
                "$path must register on the activity's own dispatcher, scoped to its lifecycle",
                body.contains("onBackPressedDispatcher.addCallback(this,")
            )
            assertTrue(
                "$path must register the callback ENABLED, or back falls through to the system",
                body.contains("OnBackPressedCallback(true)")
            )
            assertTrue(
                "$path back callback must still call $handler",
                body.substringBefore("\n    }").contains(handler)
            )
        }
    }

    /**
     * Registration must happen once per instance, from `onCreate`. These activities are all
     * `singleInstance`, so a re-delivered block arrives at `onNewIntent`; registering anywhere
     * reachable from there would stack a new callback on every delivery and leak them for the life
     * of the activity.
     */
    @Test
    fun `the back callback is registered exactly once, from onCreate`() {
        backHandlers.keys.forEach { path ->
            val source = read(path)
            assertEquals(
                "$path must contain exactly one declaration and one call of registerBackHandler()",
                2,
                Regex("registerBackHandler\\(\\)").findAll(source).count()
            )
            val onCreate = source.substringAfter("override fun onCreate(").substringBefore("\n    }")
            assertTrue(
                "$path must call registerBackHandler() from onCreate",
                onCreate.contains("registerBackHandler()")
            )
        }
    }

    /**
     * The escape hatch that would silently un-migrate all of the above. Google documents
     * `enableOnBackInvokedCallback="false"` as temporary; it stops working at a later target, and
     * until then it hides the fact that the overrides are gone.
     */
    @Test
    fun `the manifest does not opt out of the predictive back callback`() {
        assertFalse(
            "enableOnBackInvokedCallback=false is a temporary opt-out and must not be used",
            read("AndroidManifest.xml").contains("enableOnBackInvokedCallback")
        )
    }

    /**
     * Edge-to-edge is enforced with no opt-out from targetSdk 36, so the overlay window spans under
     * the status and navigation bars. Content must be inset; the opaque Surface must NOT be, it is
     * what covers every pixel of the app behind the block.
     */
    @Test
    fun `overlay content is inset while its background stays full-bleed`() {
        overlayContents.forEach { path ->
            val source = read(path)
            assertTrue(
                "$path content must sit inside the safe area on an edge-to-edge window",
                source.contains(".safeDrawingPadding()")
            )
            assertTrue(
                "$path background Surface must stay full-bleed, insets belong on the content",
                source.contains("Surface(\n        modifier = Modifier.fillMaxSize(),")
            )
        }
    }
}
