package com.astraedus.nudge.domain.lock

import com.astraedus.nudge.domain.groups.GroupDefinition
import com.astraedus.nudge.domain.packages.AppCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.astraedus.nudge.testing.aBlockRule

class GroupWeakeningTest {

    private val blocks = GroupUsage(blocks = true, allowedBy = false)
    private val allowed = GroupUsage(blocks = false, allowedBy = true)
    private val both = GroupUsage(blocks = true, allowedBy = true)

    private fun g(vararg m: String) = ResolvedGroup(1, m.toSet())

    @Test fun `removing a member while blocking is weakening`() =
        assertTrue(GroupWeakening.isWeakening(g("a", "b"), g("a"), blocks))

    @Test fun `adding a member while blocking is not weakening`() =
        assertFalse(GroupWeakening.isWeakening(g("a"), g("a", "b"), blocks))

    @Test fun `deleting a blocking group is weakening`() =
        assertTrue(GroupWeakening.isWeakening(g("a", "b"), g(), blocks))

    @Test fun `any change while unused is free`() {
        assertFalse(GroupWeakening.isWeakening(g("a", "b"), g("a"), GroupUsage.UNUSED))
        assertFalse(GroupWeakening.isWeakening(g("a"), g("a", "b"), GroupUsage.UNUSED))
        assertFalse(GroupWeakening.isWeakening(g("a"), g(), GroupUsage.UNUSED))
    }

    @Test fun `joining while allowedBy is weakening`() =
        assertTrue(GroupWeakening.isWeakening(g("a"), g("a", "b"), allowed))

    @Test fun `leaving while only allowedBy is free`() =
        assertFalse(GroupWeakening.isWeakening(g("a", "b"), g("a"), allowed))

    @Test fun `with both uses a join is weakening and so is a leave`() {
        assertTrue(GroupWeakening.isWeakening(g("a"), g("a", "b"), both))
        assertTrue(GroupWeakening.isWeakening(g("a", "b"), g("a"), both))
    }

    @Test fun `no change is free in every usage`() {
        for (u in listOf(GroupUsage.UNUSED, blocks, allowed, both)) {
            assertFalse(GroupWeakening.isWeakening(g("a", "b"), g("b", "a"), u))
        }
    }

    @Test fun `usage of an enabled rule for the group blocks`() {
        val rules = listOf(aBlockRule(id = 1, groupId = 5, mode = "DELAY", enabled = true))
        assertEquals(GroupUsage(blocks = true, allowedBy = false), GroupUsage.of(5, rules))
    }

    @Test fun `a disabled rule, another group's rule and a direct app rule do not count`() {
        val rules = listOf(
            aBlockRule(id = 1, groupId = 5, mode = "DELAY", enabled = false),
            aBlockRule(id = 2, groupId = 6, mode = "DELAY", enabled = true),
            aBlockRule(id = 3, packageName = "com.x", groupId = null, mode = "DELAY", enabled = true)
        )
        assertEquals(GroupUsage.UNUSED, GroupUsage.of(5, rules))
    }

    // -- every usage x every kind of edit (L1): the whole truth table of spec 5.6 --

    @Test fun `every usage against join, leave, both and no change`() {
        val joinOnly = g("a") to g("a", "b")
        val leaveOnly = g("a", "b") to g("a")
        val joinAndLeave = g("a", "b") to g("a", "c")
        val noChange = g("a", "b") to g("a", "b")
        // usage -> expected for (join, leave, join+leave, no change)
        val expected = mapOf(
            GroupUsage.UNUSED to listOf(false, false, false, false),
            blocks to listOf(false, true, true, false),
            allowed to listOf(true, false, true, false),
            both to listOf(true, true, true, false)
        )
        for ((usage, want) in expected) {
            val got = listOf(joinOnly, leaveOnly, joinAndLeave, noChange)
                .map { (old, new) -> GroupWeakening.isWeakening(old, new, usage) }
            assertEquals("usage $usage", want, got)
        }
    }

    // -- GroupUsage.of with All apps rules (L1) --

    private fun allApps(enabled: Boolean = true, exceptGroupIds: String?, exceptPackages: String? = null) =
        aBlockRule(
            id = 9, mode = "HARD_BLOCK", enabled = enabled, allApps = true,
            exceptGroupIds = exceptGroupIds, exceptPackages = exceptPackages
        )

    @Test fun `an enabled All apps rule that allows the group makes it allowedBy`() {
        assertEquals(
            GroupUsage(blocks = false, allowedBy = true),
            GroupUsage.of(5, listOf(allApps(exceptGroupIds = "3,5")))
        )
    }

    @Test fun `a disabled All apps rule that allows the group does not count`() {
        assertEquals(GroupUsage.UNUSED, GroupUsage.of(5, listOf(allApps(enabled = false, exceptGroupIds = "5"))))
    }

    @Test fun `an All apps rule that allows other groups or only apps does not count`() {
        val rules = listOf(
            allApps(exceptGroupIds = "3,6"),
            allApps(exceptGroupIds = null, exceptPackages = "com.five"),
            allApps(exceptGroupIds = "55")
        )
        assertEquals(GroupUsage.UNUSED, GroupUsage.of(5, rules))
    }

    @Test fun `stale and unparseable ids in the except list do not make another group allowed`() {
        // Group 7 was deleted; its id lingers. Group 5 must still read as unused.
        assertEquals(GroupUsage.UNUSED, GroupUsage.of(5, listOf(allApps(exceptGroupIds = "7,x, ,"))))
        // ...and the lingering id itself still reads as allowed for 7 (fail strict at the gate).
        assertEquals(
            GroupUsage(blocks = false, allowedBy = true),
            GroupUsage.of(7, listOf(allApps(exceptGroupIds = "7,x, ,")))
        )
    }

    @Test fun `a group both blocked and allowed has both uses`() {
        val rules = listOf(
            aBlockRule(id = 1, groupId = 5, mode = "DELAY", enabled = true),
            allApps(exceptGroupIds = "5")
        )
        assertEquals(GroupUsage(blocks = true, allowedBy = true), GroupUsage.of(5, rules))
    }

    // ---- Category groups: judged on RESOLVED members (spec 5.6) ----

    private val fiveSocial = (1..5).associate { "com.social$it" to AppCategory.SOCIAL } +
        mapOf("com.dialer" to AppCategory.SOCIAL, "com.game" to AppCategory.GAME)
    private val essentials = setOf("com.dialer")

    private fun def(vararg categories: AppCategory, includes: Set<String> = emptySet(), excludes: Set<String> = emptySet()) =
        GroupDefinition(1, categories.toSet(), includes, excludes)

    @Test fun `unchecking a category no installed app is in is free`() =
        assertFalse(
            GroupWeakening.isWeakening(def(AppCategory.SOCIAL, AppCategory.NEWS), def(AppCategory.SOCIAL), blocks, fiveSocial, essentials, emptySet())
        )

    @Test fun `unchecking a category that brings in five apps is weakening`() =
        assertTrue(GroupWeakening.isWeakening(def(AppCategory.SOCIAL), def(), blocks, fiveSocial, essentials, emptySet()))

    @Test fun `unchecking a category whose only app is essential is free`() =
        assertFalse(
            GroupWeakening.isWeakening(def(AppCategory.SOCIAL), def(), blocks, mapOf("com.dialer" to AppCategory.SOCIAL), essentials, emptySet())
        )

    @Test fun `removed by you on a category app is weakening`() =
        assertTrue(
            GroupWeakening.isWeakening(def(AppCategory.SOCIAL), def(AppCategory.SOCIAL, excludes = setOf("com.social1")), blocks, fiveSocial, essentials, emptySet())
        )

    @Test fun `add back is free while blocking`() =
        assertFalse(
            GroupWeakening.isWeakening(def(AppCategory.SOCIAL, excludes = setOf("com.social1")), def(AppCategory.SOCIAL), blocks, fiveSocial, essentials, emptySet())
        )

    @Test fun `checking a category is free while blocking and weakening while an All apps rule allows the group`() {
        assertFalse(GroupWeakening.isWeakening(def(), def(AppCategory.GAME), blocks, fiveSocial, essentials, emptySet()))
        assertTrue(GroupWeakening.isWeakening(def(), def(AppCategory.GAME), allowed, fiveSocial, essentials, emptySet()))
    }

    @Test fun `swapping a hand-picked app for its category is free when the members are the same`() =
        assertFalse(
            GroupWeakening.isWeakening(
                def(includes = (1..5).map { "com.social$it" }.toSet()),
                def(AppCategory.SOCIAL),
                both,
                fiveSocial,
                essentials,
                emptySet()
            )
        )

    @Test fun `a corrupt row naming a group and allApps is read as the group rule, not as allowing`() {
        // RuleTarget.of reads the narrowest target, so this row blocks group 5 and allows nothing.
        val corrupt = aBlockRule(
            id = 1, groupId = 5, mode = "DELAY", enabled = true, allApps = true, exceptGroupIds = "5"
        )
        assertEquals(GroupUsage(blocks = true, allowedBy = false), GroupUsage.of(5, listOf(corrupt)))
    }
}
