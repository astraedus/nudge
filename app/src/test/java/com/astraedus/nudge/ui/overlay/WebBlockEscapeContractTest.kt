package com.astraedus.nudge.ui.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level half of issue [#78](https://github.com/astraedus/nudge/issues/78): that
 * `BlockOverlayActivity` actually WIRES the web escape the pure classes decide.
 *
 * `OverlayLifecycleTest`, `OverlayLifecycleGuardTest` and `WebBlockEscapeTest` prove what the
 * decisions are. None of them can see whether the activity passes the browser in, executes the
 * effect, or builds the intent it is supposed to; an unwired `escapeBrowser` would leave every one
 * of them green and the browser bricked exactly as reported. That residue is pinned here, comments
 * stripped so a KDoc quoting the code cannot satisfy it.
 */
class WebBlockEscapeContractTest {

    private val source: String by lazy {
        val raw = listOf(
            File("src/main/java/com/astraedus/nudge/ui/overlay/BlockOverlayActivity.kt"),
            File("app/src/main/java/com/astraedus/nudge/ui/overlay/BlockOverlayActivity.kt")
        ).firstOrNull { it.exists() }?.readText()
            ?: error("BlockOverlayActivity.kt not found from working dir ${File("").absolutePath}")
        raw.replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
    }

    /** The body of `private fun <name>(`, refusing to fall back to the whole file. */
    private fun body(name: String): String {
        val marker = "private fun $name("
        assertTrue("BlockOverlayActivity must declare $marker", source.contains(marker))
        return source.substringAfter(marker).substringBefore("\n    }")
    }

    @Test
    fun `every walk-away passes the web block's browser to the lifecycle`() {
        val navigateHome = body("navigateHome")
        assertTrue(navigateHome.contains("onWalkAwayRequested("))
        assertTrue(
            "the walk-away must pass escapeBrowser, or a web block goes home and leaves the tab on the URL",
            navigateHome.contains(Regex("""escapeBrowser\s*=\s*browserToEscape\(intent\)"""))
        )
    }

    /**
     * The RAW extra. `passthroughPackage()` falls back to the displayed package, which would make
     * every app block look like a web block and send Instagram's walk-away to `about:blank`.
     */
    @Test
    fun `the escape browser is read from the raw passthrough extra`() {
        val helper = body("browserToEscape")
        assertTrue(helper.contains("WebBlockEscape.browserToEscape("))
        assertTrue(helper.contains("getStringExtra(EXTRA_PASSTHROUGH_PACKAGE)"))
        assertFalse(helper.contains("passthroughPackage("))
    }

    @Test
    fun `the leave-the-page effect is executed`() {
        assertTrue(
            source.contains(
                Regex("""is OverlayLifecycle\.Effect\.LeaveBlockedPage\s*->\s*leaveBlockedPage\(effect\)""")
            )
        )
    }

    @Test
    fun `the escape asks the policy, starts the browser on the neutral page, and falls back on failure`() {
        val leave = body("leaveBlockedPage")
        assertTrue("the exit must be decided once, while performing it", leave.contains("webBlockEscape.decideExit("))
        assertTrue(leave.contains("startActivity(webEscapeIntent("))
        assertTrue("a refused start must not strand the user", leave.contains("catch"))
        assertTrue("every failure path must run the go-home fallback", leave.contains("runEffects(effect.fallback)"))
    }

    @Test
    fun `the escape intent targets that browser with the neutral url and our app id`() {
        val intent = body("webEscapeIntent")
        assertTrue(intent.contains("Intent.ACTION_VIEW"))
        assertTrue(
            "never a hand-typed URL: the neutral page is the constant WebBlockEscapeTest proves unblockable",
            intent.contains("Uri.parse(WebBlockEscape.NEUTRAL_URL)")
        )
        assertTrue("an untargeted VIEW could open a different browser", intent.contains("setPackage(browserPackage)"))
        assertTrue(
            "the app id is what makes Chromium reuse our tab instead of piling up new ones",
            intent.contains("Browser.EXTRA_APPLICATION_ID")
        )
        assertTrue(intent.contains("FLAG_ACTIVITY_NEW_TASK"))
    }

    /** A re-block right after an escape is how a failed escape is detected; it has to be reported. */
    @Test
    fun `a delivered web block is reported to the escape policy`() {
        val render = body("render")
        assertTrue(render.contains("webBlockEscape.onBlockDelivered("))
    }
}
