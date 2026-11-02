package com.astraedus.nudge.data.repository

import com.astraedus.nudge.domain.groups.GroupDefinition
import com.astraedus.nudge.domain.packages.AppCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.astraedus.nudge.testing.aGroupMember
import com.astraedus.nudge.testing.anAppGroup

/** L1: the group tables read as definitions. A member row is not a member. */
class GroupDefinitionsTest {

    private val groups = listOf(
        anAppGroup(id = 1, name = "Social", categories = "SOCIAL,VIDEO"),
        anAppGroup(id = 2, name = "Hand-picked")
    )
    private val rows = listOf(
        aGroupMember(1, "com.added"),
        aGroupMember(1, "com.removed", excluded = true),
        aGroupMember(2, "com.a"),
        aGroupMember(9, "com.orphan")
    )

    @Test fun `rows split into includes and removed by you, categories parsed`() {
        assertEquals(
            listOf(
                GroupDefinition(1, setOf(AppCategory.SOCIAL, AppCategory.VIDEO), setOf("com.added"), setOf("com.removed")),
                GroupDefinition(2, includes = setOf("com.a"))
            ),
            groupDefinitionsOf(groups, rows)
        )
    }

    @Test fun `a removed by you row is not a member and an orphan row reaches no group`() {
        val resolver = groupResolverOf(groups, rows)
        assertFalse(resolver.isMember(1, "com.removed", AppCategory.SOCIAL, essential = false, browser = false))
        assertTrue(resolver.isMember(1, "com.added", null, essential = false, browser = false))
        assertEquals(emptySet<Long>(), resolver.groupIdsOf("com.orphan", null, browser = false))
    }

    @Test fun `a group from before categories (NULL column, no excluded rows) reads exactly as before`() {
        val old = anAppGroup(id = 3, name = "Old").definition(listOf(aGroupMember(3, "com.x"), aGroupMember(3, "com.y")))
        assertEquals(GroupDefinition(3, includes = setOf("com.x", "com.y")), old)
    }
}
