package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.data.repository.RuleSnapshot
import com.astraedus.nudge.data.repository.RuleSnapshots
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.FirstUnlockState
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.nuke.NukeEnforcement
import com.astraedus.nudge.domain.packages.FakeEssentialPackages
import com.astraedus.nudge.domain.packages.FakePackageFacts
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeScreenTime
import com.astraedus.nudge.testing.FakeUsageRepository
import com.astraedus.nudge.testing.aBlockRule
import com.astraedus.nudge.testing.aGroupMember
import com.astraedus.nudge.testing.anAppGroup
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L3: what the daily-limit clock re-derives once a budget is spent, through the real
 * [EvaluateBlockUseCase] ([EvaluateBlockUseCase.evaluateSpentBudget], issue #192).
 *
 * The report (#190): a daily limit on Chrome's own rule, whole-app blocking off, "Block on web too"
 * with a few sites. Chrome opens normally, as the editor promises, but once the limit runs out the
 * clock hard-blocked ALL of Chrome on any site, and again on every reopen. The entry path treats a
 * browser's own rule as websites-only; the clock evaluated it as an app. These pin that the clock
 * now gives the entry path's answer for the page on screen:
 *
 *  - a site the rule does not list: nothing (the bug: a whole-browser hard block);
 *  - one of the rule's sites: that site is hard-blocked, named as a website block;
 *  - an unreadable or internal URL: nothing (no evidence, no block);
 *  - a browser a group covers whole: the group's spent limit still blocks the whole browser, and a
 *    softer group rule hands over to the site, as at entry;
 *  - an app, and a nuked browser: unchanged, the app-level answer.
 */
class EvaluateSpentBudgetTest {

    private val chrome = "com.android.chrome"
    private val instagram = "com.instagram.android"
    private val social = 7L

    /** One minute of budget, two spent: past the limit by a clock tick and more. */
    private val limitMinutes = 1
    private val spentMs = 2 * 60_000L

    /**
     * Chrome's own rule as the App config screen saves it with "Block the whole app" off and "Block
     * on web too" on: app mode NONE, the website mode persisted separately (#21), a daily limit.
     */
    private val chromeWebOnly = aBlockRule(
        id = 1,
        packageName = chrome,
        mode = BlockMode.NONE.name,
        webBlockMode = BlockMode.DELAY.name,
        webDomains = "example.com",
        dailyLimitMinutes = limitMinutes
    )

    private val screenTime = FakeScreenTime().apply {
        todayMs[chrome] = spentMs
        todayMs[instagram] = spentMs
    }

    private fun useCase(
        vararg rules: BlockRule,
        nuke: NukeEnforcement = NukeEnforcement.NEVER
    ): EvaluateBlockUseCase {
        val snapshot = RuleSnapshot.of(
            enabledRules = rules.toList(),
            groups = listOf(anAppGroup("Social", id = social)),
            members = listOf(aGroupMember(social, chrome))
        )
        return EvaluateBlockUseCase(
            ruleSnapshots = object : RuleSnapshots {
                override suspend fun current() = snapshot
                override suspend fun enabledRules() = snapshot.enabledRules
            },
            usageRepository = FakeUsageRepository(screenTime = screenTime),
            blockEngine = BlockEngine(ScheduleEvaluator(FirstUnlockState.NONE)),
            ruleEvaluator = RuleEvaluator(),
            scheduleEvaluator = ScheduleEvaluator(FirstUnlockState.NONE),
            preferences = FakeNudgePreferences(),
            contentFilter = mockk<ContentFilter>(),
            nukeEnforcement = nuke,
            essentialPackages = FakeEssentialPackages(),
            packageFacts = FakePackageFacts()
        )
    }

    private fun BlockDecision.hardBlock(): BlockDecision.Block {
        assertTrue("expected a block, got $this", this is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (this as BlockDecision.Block).mode)
        return this
    }

    // ── The report: a browser's own rule ──────────────────────────────────

    @Test
    fun `a spent limit on a browser's own rule does not block a site the rule does not list`() = runTest {
        val spent = useCase(chromeWebOnly)
            .evaluateSpentBudget(chrome, browser = true, urlBarText = "en.wikipedia.org/wiki/Cat")
        assertEquals(BlockDecision.Allow, spent.decision)
    }

    @Test
    fun `the app-level answer for the same rule is the whole-browser block the clock used to enforce`() = runTest {
        // Why the old clock blocked all of Chrome: `invoke` evaluates the direct rule as an app, and
        // a spent daily limit hard-blocks whatever the rule's mode. Kept as the record of the defect.
        useCase(chromeWebOnly).invoke(chrome).hardBlock()
    }

    @Test
    fun `a spent limit blocks the rule's own site, as a website block`() = runTest {
        val spent = useCase(chromeWebOnly)
            .evaluateSpentBudget(chrome, browser = true, urlBarText = "https://www.example.com/page")
        val block = spent.decision.hardBlock()
        assertEquals("example.com", spent.webDomain)
        assertEquals(chrome, spent.trackingPackage)
        assertEquals(limitMinutes, block.dailyLimitMinutes)
    }

    @Test
    fun `under the limit the rule's own site is not hard-blocked by the clock`() = runTest {
        screenTime.todayMs[chrome] = 30_000L
        val spent = useCase(chromeWebOnly)
            .evaluateSpentBudget(chrome, browser = true, urlBarText = "example.com")
        // The site's own (DELAY) mode: not a HARD_BLOCK, so the service does nothing with it.
        assertTrue(spent.decision !is BlockDecision.Block || (spent.decision as BlockDecision.Block).mode != BlockMode.HARD_BLOCK)
    }

    @Test
    fun `an unreadable or internal URL blocks nothing`() = runTest {
        val useCase = useCase(chromeWebOnly)
        assertEquals(BlockDecision.Allow, useCase.evaluateSpentBudget(chrome, true, null).decision)
        assertEquals(BlockDecision.Allow, useCase.evaluateSpentBudget(chrome, true, "chrome://newtab").decision)
        assertEquals(BlockDecision.Allow, useCase.evaluateSpentBudget(chrome, true, "how to bake bread").decision)
    }

    @Test
    fun `whole-app blocking on in a browser's own rule is still websites-only, as at entry`() = runTest {
        // The entry path never evaluates a browser's own rule as an app (spec 5.5, F5), so a spent
        // limit on such a rule must not block the browser from the clock either.
        val wholeOn = chromeWebOnly.copy(mode = BlockMode.DELAY.name, webBlockMode = null)
        val useCase = useCase(wholeOn)
        assertEquals(BlockDecision.Allow, useCase.invoke(chrome, excludeDirectRules = true))
        assertEquals(
            BlockDecision.Allow,
            useCase.evaluateSpentBudget(chrome, true, "en.wikipedia.org").decision
        )
        useCase.evaluateSpentBudget(chrome, true, "example.com").decision.hardBlock()
    }

    // ── A browser a group covers whole ────────────────────────────────────

    @Test
    fun `a group's spent limit still blocks the whole browser`() = runTest {
        val groupLimit = aBlockRule(
            id = 2,
            groupId = social,
            mode = BlockMode.DELAY.name,
            dailyLimitMinutes = limitMinutes
        )
        val spent = useCase(groupLimit)
            .evaluateSpentBudget(chrome, browser = true, urlBarText = "en.wikipedia.org")
        val block = spent.decision.hardBlock()
        assertNull("a block of the browser itself, not of a site", spent.webDomain)
        assertTrue("named after the group, got ${block.ruleName}", block.ruleName?.startsWith("Social") == true)
    }

    @Test
    fun `a softer group rule hands over to the site, so only the spent rule's sites block`() = runTest {
        val groupDelay = aBlockRule(id = 2, groupId = social, mode = BlockMode.DELAY.name)
        val useCase = useCase(groupDelay, chromeWebOnly)
        assertEquals(
            BlockDecision.Allow,
            useCase.evaluateSpentBudget(chrome, true, "en.wikipedia.org").decision
        )
        val onSite = useCase.evaluateSpentBudget(chrome, true, "example.com")
        onSite.decision.hardBlock()
        assertEquals("example.com", onSite.webDomain)
    }

    // ── Everything that is not a browser: unchanged ───────────────────────

    @Test
    fun `an app's spent limit is the app-level hard block, as before`() = runTest {
        val instagramLimit = aBlockRule(
            id = 3,
            packageName = instagram,
            mode = BlockMode.NONE.name,
            dailyLimitMinutes = limitMinutes
        )
        val spent = useCase(instagramLimit)
            .evaluateSpentBudget(instagram, browser = false, urlBarText = null)
        spent.decision.hardBlock()
        assertNull(spent.webDomain)
    }

    @Test
    fun `a nuked browser is blocked as an app whatever site is open`() = runTest {
        val spent = useCase(chromeWebOnly, nuke = { it == chrome })
            .evaluateSpentBudget(chrome, browser = true, urlBarText = "en.wikipedia.org")
        assertTrue((spent.decision.hardBlock()).nuke)
        assertNull(spent.webDomain)
    }
}
