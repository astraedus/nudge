package com.astraedus.nudge.ui.screens.allapps

import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.CHAT
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.DELETED
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.DIALER
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.FEED
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.GAME
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.GONE
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.MAIL
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.NOTES
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.SOCIAL
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.WORK
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.allAppsRule
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.appRule
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.device
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.groupRule
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.tables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Layer L1 (pure unit): the numbers and names the All apps editor shows (spec 3.4). "Blocks N
 * apps" is asked of the engine's own coverage, so these pin that the editor's count agrees with
 * what the rule would actually block: essentials, allowed apps, allowed groups, stale group ids and
 * apps that cannot be opened.
 */
class AllAppsCoverageTest {

    private fun count(rule: com.astraedus.nudge.data.db.entity.BlockRule) = blockedCount(rule, tables(), device)

    // --- Blocks N apps ---

    @Test
    fun `a rule with no exceptions blocks every launchable app but the essentials`() {
        // chat, feed, game, mail, notes; not the dialer (essential), not the launcher (no entry).
        assertEquals(5, count(allAppsRule()))
    }

    @Test
    fun `each allowed app comes off the count`() {
        assertEquals(3, count(allAppsRule(allowedPackages = listOf(CHAT, GAME))))
    }

    @Test
    fun `an allowed group takes its installed members off the count`() {
        // Work = mail, notes and an uninstalled app: two come off.
        assertEquals(3, count(allAppsRule(allowedGroupIds = listOf(WORK))))
        assertEquals(1, count(allAppsRule(allowedGroupIds = listOf(WORK, SOCIAL), allowedPackages = listOf(CHAT))))
    }

    @Test
    fun `an app allowed twice, by name and by group, comes off once`() {
        assertEquals(3, count(allAppsRule(allowedPackages = listOf(MAIL), allowedGroupIds = listOf(WORK))))
    }

    @Test
    fun `a stale group id allows nothing, even though its old member rows remain`() {
        assertEquals(5, count(allAppsRule(allowedGroupIds = listOf(DELETED))))
    }

    @Test
    fun `allowing an essential or an uninstalled app changes nothing`() {
        assertEquals(5, count(allAppsRule(allowedPackages = listOf(DIALER, GONE))))
    }

    @Test
    fun `the count is what the rule would block, whether it is on or off`() {
        assertEquals(5, count(allAppsRule(enabled = false)))
    }

    @Test
    fun `allowing every app leaves nothing to block`() {
        assertEquals(0, count(allAppsRule(allowedPackages = listOf(CHAT, FEED, GAME, MAIL, NOTES))))
    }

    // --- Also blocked by ---

    @Test
    fun `other rules are named the way the user knows them`() {
        val rules = listOf(
            appRule(id = 10, pkg = FEED),
            groupRule(id = 11, groupId = SOCIAL),
            allAppsRule(id = 12)
        )
        val t = tables(rules)
        assertEquals(
            listOf("its own rule", "Social", "All apps"),
            alsoBlockedBy(FEED, t.otherRules(editedRuleId = 20), t, device)
        )
    }

    @Test
    fun `the rule being edited, rules that are off and per-feature rules are not named`() {
        val rules = listOf(
            allAppsRule(id = 20),
            appRule(id = 13, pkg = CHAT, enabled = false),
            appRule(id = 14, pkg = CHAT, features = "REELS")
        )
        val t = tables(rules)
        assertEquals(emptyList<String>(), alsoBlockedBy(CHAT, t.otherRules(editedRuleId = 20), t, device))
    }

    @Test
    fun `two All apps rules read as All apps once`() {
        val t = tables(listOf(allAppsRule(id = 12), allAppsRule(id = 15)))
        assertEquals(listOf("All apps"), alsoBlockedBy(GAME, t.otherRules(null), t, device))
    }

    @Test
    fun `another All apps rule that allows the app does not block it`() {
        val t = tables(listOf(allAppsRule(id = 12, allowedPackages = listOf(GAME))))
        assertEquals(emptyList<String>(), alsoBlockedBy(GAME, t.otherRules(null), t, device))
    }

    @Test
    fun `also blocked by text`() {
        assertNull(alsoBlockedByText(emptyList()))
        assertEquals("Also blocked by Social", alsoBlockedByText(listOf("Social")))
        assertEquals("Also blocked by its own rule, Social", alsoBlockedByText(listOf("its own rule", "Social")))
        assertEquals("Also blocked by 3 rules", alsoBlockedByText(listOf("a", "b", "c")))
    }

    // --- Still blocked ---

    @Test
    fun `still blocked counts the installed members another rule blocks`() {
        val t = tables(listOf(appRule(id = 10, pkg = MAIL), appRule(id = 11, pkg = GONE)))
        // mail has its own rule; the uninstalled member opens nothing, so it is not counted.
        assertEquals(1, stillBlockedCount(WORK, t.otherRules(null), t, device))
        val all = tables(listOf(allAppsRule(id = 12)))
        assertEquals(2, stillBlockedCount(WORK, all.otherRules(null), all, device))
        assertEquals(0, stillBlockedCount(WORK, tables().otherRules(null), tables(), device))
    }

    @Test
    fun `still blocked text`() {
        assertNull(stillBlockedText(0))
        assertEquals("1 still blocked by another rule", stillBlockedText(1))
        assertEquals("2 still blocked by other rules", stillBlockedText(2))
    }

    // --- Copy ---

    @Test
    fun `blocks text says would while the rule is off`() {
        assertEquals("Blocks 47 apps", blocksText(47, enabled = true))
        assertEquals("Blocks 1 app", blocksText(1, enabled = true))
        assertEquals("Would block 0 apps", blocksText(0, enabled = false))
    }

    @Test
    fun `confirm title counts the apps`() {
        assertEquals("Block 47 apps all the time?", confirmTitle(47))
        assertEquals("Block 1 app all the time?", confirmTitle(1))
    }

    @Test
    fun `always allowed names three apps, then counts the rest`() {
        assertEquals("Always allowed: your essential apps", alwaysAllowedText(emptyList()))
        assertEquals("Always allowed: Phone", alwaysAllowedText(listOf("Phone")))
        assertEquals("Always allowed: Phone and Messages", alwaysAllowedText(listOf("Phone", "Messages")))
        assertEquals(
            "Always allowed: Phone, Messages and Settings",
            alwaysAllowedText(listOf("Phone", "Messages", "Settings"))
        )
        assertEquals(
            "Always allowed: Phone, Messages, Settings and 5 more",
            alwaysAllowedText(listOf("Phone", "Messages", "Settings", "a", "b", "c", "d", "e"))
        )
    }

    // --- Tables ---

    @Test
    fun `member counts and memberships only read groups that exist`() {
        val t = tables()
        assertEquals(3, t.memberCount(WORK))
        assertEquals(0, t.memberCount(DELETED))
        assertEquals(setOf(WORK, SOCIAL), t.memberships.map { it.groupId }.toSet())
    }

    @Test
    fun `the picker never offers an essential`() {
        assertEquals(listOf(CHAT, FEED, GAME, MAIL, NOTES), device.pickerApps.map { it.packageName })
    }
}
