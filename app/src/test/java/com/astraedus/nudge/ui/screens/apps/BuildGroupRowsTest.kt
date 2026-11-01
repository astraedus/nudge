package com.astraedus.nudge.ui.screens.apps

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The group rows on Manage Apps: title, one summary line, the switch state, search. */
class BuildGroupRowsTest {

    private val social = AppGroup(id = 1, name = "Social")
    private val games = AppGroup(id = 2, name = "games")
    private val members = listOf(
        AppGroupMember(1, "com.a"), AppGroupMember(1, "com.b"), AppGroupMember(1, "com.c"),
        AppGroupMember(2, "com.d")
    )
    private val rules = listOf(
        BlockRule(id = 10, groupId = 1, mode = "DELAY", delaySeconds = 15, dailyLimitMinutes = 30),
        BlockRule(id = 11, groupId = 2, mode = "HARD_BLOCK", enabled = false),
        BlockRule(id = 12, packageName = "com.a", mode = "HARD_BLOCK")
    )

    @Test
    fun `rows are sorted by name and carry count, summary and switch state`() {
        val rows = AppListViewModel.buildGroupRows(listOf(social, games), members, rules, query = "")

        assertEquals(listOf("games · 1 app", "Social · 3 apps"), rows.map { it.title })
        assertEquals(listOf("Hard Block", "Delay 15s · 30min limit"), rows.map { it.summary })
        assertEquals(listOf(false, true), rows.map { it.blocking })
        assertEquals(listOf(11L, 10L), rows.map { it.rule?.id })
    }

    @Test
    fun `a group with no rule reads Not blocked and its switch is off`() {
        val row = AppListViewModel.buildGroupRows(listOf(social), members, emptyList(), query = "").single()

        assertEquals("Not blocked", row.summary)
        assertFalse(row.blocking)
        assertNull(row.rule)
    }

    @Test
    fun `search matches the group's name, not its apps`() {
        assertEquals(
            listOf("Social · 3 apps"),
            AppListViewModel.buildGroupRows(listOf(social, games), members, rules, query = " soc ").map { it.title }
        )
        assertTrue(AppListViewModel.buildGroupRows(listOf(social, games), members, rules, query = "com.a").isEmpty())
    }
}
