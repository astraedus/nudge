package com.astraedus.nudge.domain.lock

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.export.ExportedGroup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1. [ImportedGroupsWeakening]: a backup's groups merge into this device's groups by name, and
 * the merge weakens exactly when it adds a member to a group an enabled All apps rule allows.
 */
class ImportedGroupsWeakeningTest {

    private val social = ResolvedGroup(id = 5, members = setOf("com.insta"))
    private val existing = mapOf("Social" to social)

    private fun allowing(groupId: Long, enabled: Boolean = true) =
        BlockRule(id = 1, mode = "HARD_BLOCK", allApps = true, exceptGroupIds = "$groupId", enabled = enabled)

    private fun blocking(groupId: Long) = BlockRule(id = 2, mode = "DELAY", groupId = groupId)

    private fun file(vararg groups: Pair<String, List<String>>) = groups.map { ExportedGroup(it.first, it.second) }

    @Test
    fun `a new member joining a group an enabled All apps rule allows is weakening`() {
        assertTrue(
            ImportedGroupsWeakening.isWeakening(existing, file("Social" to listOf("com.insta", "com.tiktok")), listOf(allowing(5)))
        )
    }

    @Test
    fun `members already in the group change nothing`() {
        assertFalse(ImportedGroupsWeakening.isWeakening(existing, file("Social" to listOf("com.insta")), listOf(allowing(5))))
        assertFalse(ImportedGroupsWeakening.isWeakening(existing, file("Social" to emptyList()), listOf(allowing(5))))
    }

    @Test
    fun `a disabled All apps rule, or one allowing another group, does not make the merge weakening`() {
        val incoming = file("Social" to listOf("com.tiktok"))
        assertFalse(ImportedGroupsWeakening.isWeakening(existing, incoming, listOf(allowing(5, enabled = false))))
        assertFalse(ImportedGroupsWeakening.isWeakening(existing, incoming, listOf(allowing(6))))
        assertFalse(ImportedGroupsWeakening.isWeakening(existing, incoming, emptyList()))
    }

    @Test
    fun `joining a group that only blocks is stricter, never weakening`() {
        assertFalse(ImportedGroupsWeakening.isWeakening(existing, file("Social" to listOf("com.tiktok")), listOf(blocking(5))))
    }

    @Test
    fun `a group with no namesake here is created fresh and is always free`() {
        assertFalse(ImportedGroupsWeakening.isWeakening(existing, file("Games" to listOf("com.game")), listOf(allowing(5))))
        assertFalse(ImportedGroupsWeakening.isWeakening(emptyMap(), file("Social" to listOf("com.tiktok")), listOf(allowing(5))))
    }

    @Test
    fun `a name repeated in the file merges cumulatively`() {
        val incoming = file("Social" to listOf("com.insta"), "Social" to listOf("com.tiktok"))
        assertTrue(ImportedGroupsWeakening.isWeakening(existing, incoming, listOf(allowing(5))))
    }

    @Test
    fun `one weakening group among free ones is enough`() {
        val work = ResolvedGroup(id = 7, members = setOf("com.slack"))
        val incoming = file("Work" to listOf("com.slack"), "Games" to listOf("com.game"), "Social" to listOf("com.tiktok"))
        assertTrue(
            ImportedGroupsWeakening.isWeakening(mapOf("Social" to social, "Work" to work), incoming, listOf(allowing(5)))
        )
    }
}
