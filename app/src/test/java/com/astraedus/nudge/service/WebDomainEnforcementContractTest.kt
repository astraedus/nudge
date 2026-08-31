package com.astraedus.nudge.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guard for the SHAPE of web-domain enforcement, in the spirit of
 * [HomeScreenPassthroughContractTest] and `BlockOverlayWalkAwayContractTest`.
 *
 * The field report was *"it'll delay me to go on insta web but once I'm on there's no other blocks
 * or it doesn't track anything"*, and every cause was positional rather than value-level:
 *
 *  - the domain pass was assigned in the service at BLOCK time, so abandoning the block still let
 *    the site through;
 *  - the pass was granted to the RULE'S APP package, so completing an instagram.com delay in Chrome
 *    handed a free pass to the Instagram app and none to the browser;
 *  - the session bookkeeping that measures a visit sat BELOW the passthrough early-return, i.e. in
 *    the one branch the user spends their whole visit outside of.
 *
 * No unit test over a pure function can see any of those; each is about which side of a `return` a
 * call sits on. These assertions describe the CLASS, so a future edit that moves the bookkeeping
 * back below the return, or re-grants at block time, fails here.
 */
class WebDomainEnforcementContractTest {

    private fun read(relative: String): String {
        val candidates = listOf(File("src/main/java/$relative"), File("app/src/main/java/$relative"))
        return (candidates.firstOrNull { it.exists() }
            ?: error("$relative not found from ${File("").absolutePath}"))
            .readText()
    }

    private val service: String by lazy {
        read("com/astraedus/nudge/service/NudgeAccessibilityService.kt")
    }

    private val overlay: String by lazy {
        read("com/astraedus/nudge/ui/overlay/BlockOverlayActivity.kt")
    }

    /** The body of `evaluateWebDomain`, up to the next top-level private fun. */
    private val evaluateWebDomain: String by lazy {
        val start = service.indexOf("private suspend fun evaluateWebDomain(")
        assertTrue("evaluateWebDomain must still exist", start >= 0)
        val end = service.indexOf("\n    /**", start + 1)
        service.substring(start, if (end > start) end else service.length)
    }

    /**
     * The fix for "it doesn't track anything". The user spends their entire visit in the passthrough
     * branch — everything that measures the visit has to be reached from there, not only from the
     * evaluate path below it.
     */
    @Test
    fun `the web session is recorded in the passthrough branch, not only when evaluating`() {
        val passthroughBranch = evaluateWebDomain.substringAfter("Action.PASSTHROUGH ->")
            .substringBefore("Action.EVALUATE ->")

        assertTrue(
            "the PASSTHROUGH branch must still record the foreground web session before returning",
            passthroughBranch.contains("onWebDomainForeground(")
        )
        assertTrue(
            "the evaluate path must record it too",
            evaluateWebDomain.substringAfter("Action.EVALUATE ->").contains("onWebDomainForeground(")
        )
    }

    /**
     * The pass is EARNED. Any assignment of the granted domain inside the service puts it back to
     * being handed over at block time, which is what let a walk-away through.
     */
    @Test
    fun `the service never grants the domain pass itself`() {
        assertFalse(
            "the old lastBlockedDomain field must stay deleted",
            service.contains("lastBlockedDomain")
        )
        assertFalse(
            "only BlockOverlayActivity may grant a passthrough",
            service.contains("passthroughManager().grant(")
        )
    }

    @Test
    fun `the completed block is what grants the domain`() {
        val onTimerComplete = overlay.substringAfter("private fun onTimerComplete()")
            .substringBefore("private fun navigateHome()")

        assertTrue(
            "the grant must carry the web domain so it is scoped to the site",
            onTimerComplete.contains("webDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN)")
        )
        assertTrue(
            "the grant must apply to the app the user is IN (the browser for a web block)",
            onTimerComplete.contains("passthroughPackage(intent)")
        )
    }

    /**
     * The overlay displays and attributes the RULE'S app (so it says "Instagram" and the stat lands
     * on Instagram) while granting to the browser. Collapsing the two back into one extra is the
     * bug where completing a website delay opened the app.
     */
    @Test
    fun `the displayed package and the granted package are separate extras`() {
        assertTrue(
            overlay.contains("const val EXTRA_PASSTHROUGH_PACKAGE") &&
                overlay.contains("const val EXTRA_WEB_DOMAIN")
        )
        assertTrue(
            "a web block must pass the browser as the passthrough package",
            service.contains("EXTRA_PASSTHROUGH_PACKAGE, it.browserPackage")
        )
        assertTrue(
            "the UsageEvent must stay attributed to the rule's app, not the browser",
            service.contains("UsageEvent(\n                        packageName = packageName,")
        )
    }

    /**
     * An unreadable URL bar must not revoke a live pass (it re-blocked users mid-visit). The gate
     * owns that decision; the service must consult it rather than re-deriving the comparison.
     */
    @Test
    fun `the pass-or-evaluate decision goes through WebDomainGate`() {
        assertTrue(
            evaluateWebDomain.contains("WebDomainGate.decide(extractedDomain, passthrough.lastDomain)")
        )
        val unreadableBranch = evaluateWebDomain.substringAfter("Action.UNREADABLE ->")
            .substringBefore("Action.PASSTHROUGH ->")
        assertFalse(
            "an unverifiable reading must change no state at all",
            unreadableBranch.contains("clearWebGrant(") || unreadableBranch.contains("onWebDomainForeground(")
        )
    }

    /**
     * A cooldown or a kick armed on the browser package would lock every website the user has. The
     * key is always the domain's.
     */
    @Test
    fun `the web cooldown is keyed by domain, never by the browser package`() {
        val cooldown = service.substringAfter("private fun enforceWebCooldown(")
            .substringBefore("private fun startWebTimeTicker(")

        assertTrue(
            "the cooldown must be looked up under the domain's session key",
            cooldown.contains("WebSessionKey.forDomain(")
        )
        assertFalse(
            "the browser package must never be the cooldown key",
            cooldown.contains("isInCooldown(browserPackage)")
        )
    }

    /**
     * The web clock is a separate job on purpose: browsers are not in the counter cache, so every
     * browser window event runs `clearOverlays` -> `stopForegroundTimeTicker()`, and a shared job
     * would be torn down and restarted (re-reading usage) on each one.
     */
    @Test
    fun `the web clock is not the app-level ticker`() {
        assertTrue(
            "the web clock must be its own ForegroundClock, separate from the app one",
            service.contains("private lateinit var webClock: ForegroundClock") &&
                service.contains("private lateinit var foregroundClock: ForegroundClock")
        )
        assertTrue(
            "clearOverlays must not end a web session while a browser is still in front",
            service.contains(
                "if (!entryPoint.webDomainDetector().isBrowser(packageName)) endWebSession(reason)"
            )
        )
    }

    /**
     * Both clocks must go through [ForegroundClock], which is where the per-tick exception guard and
     * the start/stop/exit logging live. A hand-rolled `while (isActive) { tick(); delay() }` back in
     * the service is the shape that let one throwing tick end the clock permanently and silently.
     */
    @Test
    fun `no hand-rolled tick loop may live in the service`() {
        assertFalse(
            "clock loops belong in ForegroundClock, not inlined here",
            service.contains("while (isActive)")
        )
        assertTrue(service.contains("foregroundClock.start("))
        assertTrue(service.contains("webClock.start("))
    }

    /**
     * The defect that made the time-based auto-kick unreliable: the SYSTEM_PACKAGES branch stopped
     * the foreground-time clock for EVERY system surface, so a heads-up notification or a shade pull
     * silently ended a running session's clock. Only genuinely going home may stop it.
     */
    @Test
    fun `a transient system window must not stop the foreground clock`() {
        val branch = service.substringAfter("if (packageName in SYSTEM_PACKAGES) {")
            .substringBefore("\n        }")
        assertTrue(
            "the clock must stop only when the user actually went home",
            branch.contains("stopClocks = home")
        )
        assertFalse(
            "the branch must not stop the clocks unconditionally",
            branch.contains("stopForegroundTimeTicker(") || branch.contains("endWebSession(")
        )
    }

    /**
     * A kick that leaves the completed-delay pass in place puts the user straight back on the page
     * they were just removed from.
     */
    @Test
    fun `a web kick revokes the pass it was kicking out of`() {
        val tick = service.substringAfter("private suspend fun tickWebTime(")
            .substringBefore("/** Identifies a block that happened on a website")

        assertTrue(tick.contains("autoKickExecutor.kick("))
        assertTrue(tick.contains("clearWebGrant()"))
        assertTrue(tick.contains("endWebSession("))
    }
}
