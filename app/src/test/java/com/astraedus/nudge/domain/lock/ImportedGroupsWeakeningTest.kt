package com.astraedus.nudge.domain.lock

import com.astraedus.nudge.data.db.entity.BlockRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1. [ImportedGroupsWeakening]: a backup's groups merge into this device's groups by name, and
 * the merge weakens exactly when, RESOLVED on this device, it adds a member to a group an enabled
 * All apps rule allows. (The merge itself: [ImportedGroupMergeTest].)
 */
class ImportedGroupsWeakeningTest {

    private val social = ResolvedGroup(id = 5, members = setOf("com.insta"))
    private val existing = mapOf("Social" to social)

    private fun allowing(groupId: Long, enabled: Boolean = true) =
        BlockRule(id = 1, mode = "HARD_BLOCK", allApps = true, exceptGroupIds = "$groupId", enabled = enabled)

    private fun blocking(groupId: Long) = BlockRule(id = 2, mode = "DELAY", groupId = groupId)

    private fun merged(group: ResolvedGroup, vararg added: String, name: String = "Social") =
        mapOf(name to group.copy(members = group.members + added))

    @Test
    fun `a new member joining a group an enabled All apps rule allows is weakening`() {
        assertTrue(
            ImportedGroupsWeakening.isWeakening(existing, merged(social, "com.tiktok"), listOf(allowing(5)))
        )
    }

    @Test
    fun `a merge that resolves to the same members on this device is free, even if the file changed settings`() {
        // New categories that bring in no installed app: the resolved sets are equal.
        assertFalse(ImportedGroupsWeakening.isWeakening(existing, merged(social), listOf(allowing(5))))
    }

    @Test
    fun `a category merge that resolves to apps under an All apps allow is weakening, to none is not`() {
        val withApps = merged(social, "com.insta2", "com.tiktok", "com.snap", "com.fb", "com.x")
        assertTrue(ImportedGroupsWeakening.isWeakening(existing, withApps, listOf(allowing(5))))
        assertFalse(ImportedGroupsWeakening.isWeakening(existing, merged(social), listOf(allowing(5))))
    }

    @Test
    fun `a disabled All apps rule, or one allowing another group, does not make the merge weakening`() {
        val after = merged(social, "com.tiktok")
        assertFalse(ImportedGroupsWeakening.isWeakening(existing, after, listOf(allowing(5, enabled = false))))
        assertFalse(ImportedGroupsWeakening.isWeakening(existing, after, listOf(allowing(6))))
        assertFalse(ImportedGroupsWeakening.isWeakening(existing, after, emptyList()))
    }

    @Test
    fun `joining a group that only blocks is stricter, never weakening`() {
        assertFalse(ImportedGroupsWeakening.isWeakening(existing, merged(social, "com.tiktok"), listOf(blocking(5))))
    }

    @Test
    fun `a group with no namesake here is created fresh and is always free`() {
        // Not in [existing] at all: nothing to compare, nothing to weaken.
        assertFalse(ImportedGroupsWeakening.isWeakening(emptyMap(), merged(social, "com.tiktok"), listOf(allowing(5))))
        // Untouched by the file: absent from [merged].
        assertFalse(ImportedGroupsWeakening.isWeakening(existing, emptyMap(), listOf(allowing(5))))
    }

    @Test
    fun `one weakening group among free ones is enough`() {
        val work = ResolvedGroup(id = 7, members = setOf("com.slack"))
        val both = existing + ("Work" to work)
        val after = merged(social, "com.tiktok") + merged(work, name = "Work")
        assertTrue(ImportedGroupsWeakening.isWeakening(both, after, listOf(allowing(5))))
        assertFalse(ImportedGroupsWeakening.isWeakening(both, after, listOf(allowing(7))))
    }
}
