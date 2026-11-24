package com.astraedus.nudge.service

import com.astraedus.nudge.testing.SourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guard for WHICH decision the daily-limit clock enforces, and WHEN it drops the grant
 * (issue #192). Shape bugs, so a source scan, next to the value-level [EvaluateSpentBudgetTest]
 * (`domain/usecase`), which pins what the decision is.
 *
 *  - `enforceExhaustedBudget` called `EvaluateBlockUseCase.invoke(browser)` with every rule, so a
 *    browser's own rule (websites-only at entry) was evaluated as an app and a spent limit
 *    hard-blocked the whole browser on any site. It must go through `evaluateSpentBudget`, handing
 *    over whether the package is a browser and the URL on screen.
 *  - `TimeRemainingHandler` cleared the passthrough grant on every tick past the budget, before
 *    anything had decided a block. Now that a spent browser budget often blocks nothing, that
 *    revoked a finished website delay every 30 seconds. The grant is dropped in the service, only
 *    once the HARD_BLOCK is confirmed, and before the one block path.
 */
class SpentBudgetBrowserContractTest {

    private val serviceFile = SourceScan.kotlinFile("com/astraedus/nudge/service/NudgeAccessibilityService")
    private val clockFile = SourceScan.kotlinFile("com/astraedus/nudge/service/TimeRemainingHandler")

    private val enforce: String by lazy {
        SourceScan.bodyOf(SourceScan.code(serviceFile), "private suspend fun enforceExhaustedBudget(")
    }

    @Test
    fun `the clock's re-derive routes a browser through the browser decision`() {
        assertTrue(
            "enforceExhaustedBudget must ask evaluateSpentBudget, not invoke: invoke evaluates a " +
                "browser's own rule as an app and blocks the whole browser (#192)",
            enforce.contains(".evaluateSpentBudget(")
        )
        assertFalse(
            "a bare invoke() here is the #192 defect",
            enforce.contains(".invoke(")
        )
        assertTrue(
            "it must say whether the package is a browser",
            enforce.contains("webDomainDetector().isBrowser(packageName)")
        )
        assertTrue(
            "and hand over the URL on screen, or the site cannot be told apart",
            enforce.contains("urlBarText = urlBarText")
        )
        assertTrue(
            "a site block goes out as a website block, like evaluateWebDomain's",
            enforce.contains("web = WebBlockContext(packageName, site)")
        )
    }

    @Test
    fun `the grant is dropped only once a hard block is confirmed, and before the block path`() {
        val check = enforce.indexOf("decision.mode != BlockMode.HARD_BLOCK")
        val clear = enforce.indexOf("passthroughManager().clear()")
        val block = enforce.indexOf("handleDecision(")
        assertTrue("the escalate-only check must stay", check >= 0)
        assertTrue("the grant must still be dropped as the block goes up (v1.18.4 (c))", clear >= 0)
        assertTrue("after the HARD_BLOCK check: a declined tick must not revoke a grant (#192)", clear > check)
        assertTrue("before the block, or the user walks straight back in", block > clear)
    }

    @Test
    fun `the clock itself no longer touches the grant`() {
        SourceScan.assertNoCall(
            clockFile,
            Regex("""passthroughManager\s*\.\s*clear\("""),
            "the tick decides nothing, so clearing there revoked a website delay every 30 s (#192)"
        )
    }
}
