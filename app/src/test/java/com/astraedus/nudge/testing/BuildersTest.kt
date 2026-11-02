package com.astraedus.nudge.testing

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.repository.toRuleData
import com.astraedus.nudge.domain.engine.CoverageFacts
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.AccessibilityEventRecord
import com.astraedus.nudge.domain.groups.GroupMembershipResolver
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.packages.AppCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins every builder default to the production value it is derived from, so a builder can never
 * drift into an invented fixture (CLAUDE.md "fixture honesty").
 */
class BuildersTest {

    private val pkg = "com.instagram.android"

    @Test
    fun `a rule defaults to the row the app writes when an app is switched on`() {
        // AppListViewModel.toggle: BlockRule(packageName = pkg, mode = DELAY.name, delaySeconds = 15, enabled = true)
        val switchedOn = BlockRule(packageName = pkg, mode = BlockMode.DELAY.name, delaySeconds = 15, enabled = true)

        assertEquals(switchedOn.copy(createdAt = FIXED_EPOCH_MS), aBlockRule(packageName = pkg))
    }

    @Test
    fun `every other rule field is the entity's own default`() {
        val entityDefaults = BlockRule(mode = BlockMode.DELAY.name, createdAt = FIXED_EPOCH_MS)

        assertEquals(entityDefaults, aBlockRule())
    }

    @Test
    fun `no builder reads the wall clock`() {
        assertEquals(aBlockRule(), aBlockRule())
        assertEquals(FIXED_EPOCH_MS, aBlockRule().createdAt)
        assertEquals(FIXED_EPOCH_MS, aUsageEvent(pkg).timestamp)
    }

    @Test
    fun `rule data is the rule row through the production mapper`() {
        assertEquals(aBlockRule().toRuleData(), aBlockRuleData())
        assertEquals(aBlockRule(packageName = pkg).toRuleData(), aBlockRuleData(packageName = pkg))
    }

    @Test
    fun `an active rule is what the evaluator emits for the default rule, bar its display name`() {
        val emitted = RuleEvaluator().resolveRulesForPackage(
            pkg,
            listOf(aBlockRuleData(packageName = pkg)),
            GroupMembershipResolver.NONE,
            CoverageFacts(essential = false, launchable = true, category = null, browser = false)
        ).single()

        assertEquals(emitted.copy(ruleName = null), anActiveRule())
    }

    @Test
    fun `a group rule targets the group and nothing else`() {
        val rule = aGroupRule(7L)

        assertEquals(7L, rule.groupId)
        assertNull(rule.packageName)
        assertFalse(rule.allApps)
    }

    @Test
    fun `an exported rule is the default rule read back from a backup`() {
        val exported = anExportedRule(aBlockRule(packageName = pkg))

        assertEquals(pkg, exported.packageName)
        assertEquals(BlockMode.DELAY.name, exported.mode)
        assertEquals(aBlockRule().delaySeconds, exported.delaySeconds)
        assertTrue(exported.enabled)
    }

    @Test
    fun `the event, group and app builders carry the production defaults`() {
        assertEquals(UsageEvent(packageName = pkg, timestamp = FIXED_EPOCH_MS), aUsageEvent(pkg))
        assertEquals(
            AccessibilityEventRecord(type = A11yEventType.VIEW_SCROLLED, packageName = pkg),
            anAccessibilityEvent(A11yEventType.VIEW_SCROLLED, pkg)
        )
        assertEquals(AppGroup(name = "Social"), anAppGroup("Social"))
        assertEquals(AppGroupMember(groupId = 3L, packageName = pkg), aGroupMember(3L, pkg))

        val app = anApp(pkg)
        assertEquals(pkg, app.appName)
        assertNull(app.icon)
        assertEquals(AppCategory.UNDEFINED, app.category)
    }
}
