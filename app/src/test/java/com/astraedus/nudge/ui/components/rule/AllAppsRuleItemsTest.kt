package com.astraedus.nudge.ui.components.rule

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1 (pure unit): how an All apps rule reads as a row (spec 3.2, 3.3, 3.7): the title, the one
 * summary line, and which rules a group is "Allowed during".
 */
class AllAppsRuleItemsTest {

    private val labels = mapOf("com.spotify" to "Spotify", "com.maps" to "Maps", "com.mail" to "Mail")
    private val work = AppGroup(id = 1, name = "Work")
    private val family = AppGroup(id = 2, name = "Family")
    private val groups = listOf(work, family)

    private fun rule(
        id: Long,
        enabled: Boolean = true,
        mode: String = "HARD_BLOCK",
        delay: Int = 0,
        exceptPackages: String? = null,
        exceptGroupIds: String? = null,
        days: String? = null,
        start: Int? = null,
        end: Int? = null
    ) = BlockRule(
        id = id,
        allApps = true,
        mode = mode,
        delaySeconds = delay,
        enabled = enabled,
        exceptPackages = exceptPackages,
        exceptGroupIds = exceptGroupIds,
        scheduleDays = days,
        scheduleStartMinute = start,
        scheduleEndMinute = end
    )

    // -- Title -------------------------------------------------------------------------------

    @Test
    fun `no exceptions - All apps`() {
        assertEquals("All apps", RuleSummary.allAppsTitle(listOf<String>()))
        assertEquals("All apps", allAppsRuleItems(listOf(rule(1)), groups, labels).single().title)
    }

    @Test
    fun `one or two exceptions are named, more are counted`() {
        assertEquals("All apps except Spotify", RuleSummary.allAppsTitle(listOf("Spotify")))
        assertEquals("All apps except Spotify, Maps", RuleSummary.allAppsTitle(listOf("Spotify", "Maps")))
        assertEquals(
            "All apps except Spotify, Maps +2",
            RuleSummary.allAppsTitle(listOf("Spotify", "Maps", "Mail", "Chat"))
        )
    }

    @Test
    fun `groups come before apps, a deleted group and an uninstalled app are left out`() {
        val item = allAppsRuleItems(
            listOf(rule(1, exceptPackages = "com.spotify,com.gone", exceptGroupIds = "2,99")),
            groups,
            labels
        ).single()

        assertEquals("All apps except Family, Spotify", item.title)
    }

    @Test
    fun `the domain form of a rule is titled the same as the row form`() {
        val row = rule(1, exceptPackages = "com.maps", exceptGroupIds = "1")
        val domain = BlockRuleData(
            id = 1, packageName = null, groupId = null, mode = BlockMode.HARD_BLOCK, delaySeconds = 0,
            dailyLimitMinutes = null, enabled = true, allApps = true,
            exceptPackages = setOf("com.maps"), exceptGroupIds = setOf(1L), exceptGroupNames = listOf("Work")
        )

        assertEquals(
            allAppsRuleItems(listOf(row), groups, labels).single().title,
            RuleSummary.allAppsTitle(domain, labels)
        )
    }

    // -- Summary -----------------------------------------------------------------------------

    @Test
    fun `summary is the mode, then the schedule`() {
        assertEquals("Hard Block", RuleSummary.allAppsSummaryText(rule(1)))
        assertEquals("Delay 15s", RuleSummary.allAppsSummaryText(rule(1, mode = "DELAY", delay = 15)))
        assertEquals(
            "Hard Block Mon–Fri 9:00–17:00",
            RuleSummary.allAppsSummaryText(
                rule(1, days = "1,2,3,4,5", start = 9 * 60, end = 17 * 60)
            )
        )
    }

    // -- Rows --------------------------------------------------------------------------------

    @Test
    fun `only All apps rules become rows, oldest first, with their on state`() {
        val appRule = BlockRule(id = 3, packageName = "com.instagram", mode = "HARD_BLOCK")
        val groupRule = BlockRule(id = 4, groupId = 1, mode = "HARD_BLOCK")

        val items = allAppsRuleItems(listOf(rule(9, enabled = false), appRule, rule(2), groupRule), groups, labels)

        assertEquals(listOf(2L, 9L), items.map { it.ruleId })
        assertEquals(listOf(true, false), items.map { it.enabled })
    }

    // -- Group editor: Allowed during ---------------------------------------------------------

    @Test
    fun `a group is allowed during every All apps rule that lists it, on or off`() {
        val rules = listOf(
            rule(1, exceptGroupIds = "1"),
            rule(2, exceptGroupIds = "2"),
            rule(3, exceptGroupIds = "1,2", enabled = false),
            rule(4, exceptPackages = "com.spotify")
        )

        val allowed = allAppsRulesAllowingGroup(1, rules, groups, labels)

        assertEquals(listOf(1L, 3L), allowed.map { it.ruleId })
        assertEquals(listOf(true, false), allowed.map { it.enabled })
        assertEquals("All apps except Work", allowed.first().title)
    }

    @Test
    fun `a group no rule lists has no Allowed during rows`() {
        val rules = listOf(rule(1, exceptGroupIds = "2"), rule(2))

        assertTrue(allAppsRulesAllowingGroup(1, rules, groups, labels).isEmpty())
    }

    @Test
    fun `an app or group rule never counts as allowing a group`() {
        val groupRule = BlockRule(id = 5, groupId = 2, mode = "HARD_BLOCK", exceptGroupIds = "1")

        assertFalse(allAppsRulesAllowingGroup(1, listOf(groupRule), groups, labels).isNotEmpty())
    }
}
