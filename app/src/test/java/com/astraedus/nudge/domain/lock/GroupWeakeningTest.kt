package com.astraedus.nudge.domain.lock

import com.astraedus.nudge.data.db.entity.BlockRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupWeakeningTest {

    private val blocks = GroupUsage(blocks = true, allowedBy = false)
    private val allowed = GroupUsage(blocks = false, allowedBy = true)
    private val both = GroupUsage(blocks = true, allowedBy = true)

    private fun g(vararg m: String) = GroupDefinition(1, m.toSet())

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
        val rules = listOf(BlockRule(id = 1, groupId = 5, mode = "DELAY", enabled = true))
        assertEquals(GroupUsage(blocks = true, allowedBy = false), GroupUsage.of(5, rules))
    }

    @Test fun `a disabled rule, another group's rule and a direct app rule do not count`() {
        val rules = listOf(
            BlockRule(id = 1, groupId = 5, mode = "DELAY", enabled = false),
            BlockRule(id = 2, groupId = 6, mode = "DELAY", enabled = true),
            BlockRule(id = 3, packageName = "com.x", groupId = null, mode = "DELAY", enabled = true)
        )
        assertEquals(GroupUsage.UNUSED, GroupUsage.of(5, rules))
    }
}
