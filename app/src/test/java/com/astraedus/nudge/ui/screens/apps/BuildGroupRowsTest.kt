package com.astraedus.nudge.ui.screens.apps

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.groupResolverOf
import com.astraedus.nudge.domain.packages.AppCategory
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

    private val installed = mapOf(
        "com.a" to AppCategory.SOCIAL, "com.b" to AppCategory.SOCIAL, "com.c" to AppCategory.SOCIAL,
        "com.d" to AppCategory.GAME
    )

    private fun rows(
        groups: List<AppGroup>, members: List<AppGroupMember>, rules: List<BlockRule>, query: String = "",
        installed: Map<String, AppCategory> = this.installed, essentials: Set<String> = emptySet()
    ) = buildGroupRows(groups, groupResolverOf(groups, members), installed, essentials, emptySet(), rules, query)

    @Test
    fun `rows are sorted by name and carry count, summary and switch state`() {
        val rows = rows(listOf(social, games), members, rules)

        assertEquals(listOf("games · 1 app", "Social · 3 apps"), rows.map { it.title })
        assertEquals(listOf("Hard Block", "Delay 15s · 30min limit"), rows.map { it.summary })
        assertEquals(listOf(false, true), rows.map { it.blocking })
        assertEquals(listOf(11L, 10L), rows.map { it.rule?.id })
    }

    @Test
    fun `a group with no rule reads Not blocked and its switch is off`() {
        val row = rows(listOf(social), members, emptyList()).single()

        assertEquals("Not blocked", row.summary)
        assertFalse(row.blocking)
        assertNull(row.rule)
    }

    @Test
    fun `search matches the group's name, not its apps`() {
        assertEquals(
            listOf("Social · 3 apps"),
            rows(listOf(social, games), members, rules, query = " soc ").map { it.title }
        )
        assertTrue(rows(listOf(social, games), members, rules, query = "com.a").isEmpty())
    }

    // -- category groups (spec 5.2): the count is resolved, not a row count --

    @Test
    fun `a category group counts installed apps in the category minus essentials minus removed`() {
        val cat = AppGroup(id = 3, name = "Social apps", categories = "SOCIAL")
        val apps = installed + ("com.e" to AppCategory.SOCIAL) + ("com.f" to AppCategory.SOCIAL)
        val rows = rows(
            listOf(cat),
            members = listOf(AppGroupMember(3, "com.b", excluded = true)),
            rules = emptyList(),
            installed = apps,
            essentials = setOf("com.c")
        )
        // SOCIAL: a, b, c, e, f. Minus essential c, minus removed b -> a, e, f.
        assertEquals("Social apps · 3 apps", rows.single().title)
    }

    @Test
    fun `a category group also counts a hand-added app of another category once`() {
        val cat = AppGroup(id = 3, name = "Mixed", categories = "SOCIAL")
        val rows = rows(
            listOf(cat),
            members = listOf(AppGroupMember(3, "com.d"), AppGroupMember(3, "com.a")),
            rules = emptyList()
        )
        // a, b, c by category; d by hand; a is both and counts once.
        assertEquals("Mixed · 4 apps", rows.single().title)
    }

    @Test
    fun `a category with no installed apps gives an empty group`() {
        val cat = AppGroup(id = 3, name = "News", categories = "NEWS")
        assertEquals("News · 0 apps", rows(listOf(cat), emptyList(), emptyList()).single().title)
    }
}
