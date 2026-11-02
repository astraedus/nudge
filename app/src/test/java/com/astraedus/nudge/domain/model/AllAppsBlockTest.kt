package com.astraedus.nudge.domain.model

import com.astraedus.nudge.domain.engine.CoverageFacts
import com.astraedus.nudge.domain.engine.RuleCoverage
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.groups.GroupDefinition
import com.astraedus.nudge.domain.groups.GroupMembershipResolver
import com.astraedus.nudge.domain.packages.AppCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.astraedus.nudge.testing.aBlockRuleData

/**
 * L1: the All apps block screen's "Open an allowed app" list is the set the ENGINE allows. An app
 * allowed through a category group (Work checks Productivity) is listed exactly as one allowed
 * through a hand-picked group, and nothing the engine still blocks is listed: "Removed by you", a
 * browser a category would match, an essential (never blocked, so never offered as an escape).
 *
 * The overlay feeds [AllAppsBlock.allowedPackagesOf] the device's resolution of each group
 * (`DeviceGroupMembers`, which is `GroupMembershipResolver.membersOf` over the installed apps); this
 * test feeds it the same resolver over a fixed phone, and cross-checks every listed app against
 * [RuleCoverage]: listed <=> not covered by the All apps rule.
 */
class AllAppsBlockTest {

    private val work = 3L
    private val music = 4L
    private val deleted = 99L

    private val installed = mapOf(
        "com.docs" to AppCategory.PRODUCTIVITY,
        "com.notes" to AppCategory.PRODUCTIVITY,
        "com.removed" to AppCategory.PRODUCTIVITY,
        "org.mozilla.firefox" to AppCategory.PRODUCTIVITY,
        "com.calendar" to AppCategory.PRODUCTIVITY,
        "com.spotify" to AppCategory.AUDIO,
        "com.chat" to AppCategory.SOCIAL
    )
    private val essentials = setOf("com.calendar")
    private val browsers = setOf("org.mozilla.firefox")

    private val resolver = GroupMembershipResolver(
        listOf(
            GroupDefinition(work, categories = setOf(AppCategory.PRODUCTIVITY), excludes = setOf("com.removed")),
            GroupDefinition(music, includes = setOf("com.spotify", "com.docs"))
        )
    )

    private val rule = aBlockRuleData(
        id = 1L, packageName = null, groupId = null, mode = BlockMode.HARD_BLOCK, delaySeconds = 15,
        dailyLimitMinutes = null, enabled = true, allApps = true,
        exceptPackages = setOf("com.maps"), exceptGroupIds = setOf(work, music, deleted)
    )

    private fun onDevice(groupIds: List<Long>) =
        AllAppsBlock.allowedPackagesOf(listOf("com.maps"), groupIds) { id ->
            resolver.membersOf(id, installed, essentials, browsers)
        }

    @Test
    fun `apps allowed through a category group are listed, after the rule's own apps, without repeats`() {
        val allowed = onDevice(listOf(work, music))
        assertEquals("com.maps", allowed.first())
        assertEquals(setOf("com.maps", "com.docs", "com.notes", "com.spotify"), allowed.toSet())
        assertEquals(allowed.distinct(), allowed)
    }

    @Test
    fun `Removed by you, an essential and a browser the category would match are not listed`() {
        val allowed = onDevice(listOf(work, music))
        assertFalse("com.removed" in allowed)
        assertFalse("com.calendar" in allowed)
        assertFalse("org.mozilla.firefox" in allowed)
        assertFalse("com.chat" in allowed)
    }

    @Test
    fun `listed is exactly not covered by the All apps rule, for every installed app`() {
        val allowed = onDevice(listOf(work, music)).toSet()
        installed.forEach { (pkg, category) ->
            val essential = pkg in essentials
            val facts = CoverageFacts(essential = essential, launchable = true, category = category, browser = pkg in browsers)
            val covered = RuleCoverage.coveringRules(pkg, listOf(rule), resolver, facts).isNotEmpty()
            if (!essential) assertEquals("$pkg: listed <=> allowed", !covered, pkg in allowed)
        }
    }

    @Test
    fun `the engine's block carries only the allowed groups that still exist, and their hand-added apps`() {
        val active = RuleEvaluator().resolveRulesForPackage(
            "com.chat",
            listOf(rule),
            resolver,
            CoverageFacts(essential = false, launchable = true, category = AppCategory.SOCIAL, browser = false)
        ).single()
        val block = active.allApps!!
        assertEquals(listOf(work, music), block.exceptGroupIds)
        // Category members need the phone, so the engine names only the hand-added ones; the
        // screen fills in the rest from exceptGroupIds (above).
        assertEquals(listOf("com.maps", "com.spotify", "com.docs"), block.allowedPackages)
        assertTrue(onDevice(block.exceptGroupIds).containsAll(block.allowedPackages))
    }
}
