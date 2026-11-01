package com.astraedus.nudge.data.repository

import com.astraedus.nudge.data.db.dao.AppGroupDao
import com.astraedus.nudge.data.db.dao.BlockRuleDao
import com.astraedus.nudge.data.db.dao.membershipChanges
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** In-memory [AppGroupDao]: the `open` transactional methods under test run for real on it. */
private class FakeGroupDao : AppGroupDao() {
    val groups = mutableMapOf<Long, String>()
    val members = mutableSetOf<Pair<Long, String>>()
    val rules = mutableMapOf<Long, BlockRule>()
    var nextId = 1L

    override fun getAll(): Flow<List<AppGroup>> = flowOf(groups.map { AppGroup(it.key, it.value) })
    override fun getById(id: Long): Flow<AppGroup?> = flowOf(groups[id]?.let { AppGroup(id, it) })
    override suspend fun insert(group: AppGroup): Long { groups[nextId] = group.name; return nextId++ }
    override suspend fun update(group: AppGroup) { groups[group.id] = group.name }
    override suspend fun delete(group: AppGroup) { groups.remove(group.id) }
    override fun getMembersForGroup(groupId: Long): Flow<List<AppGroupMember>> =
        flowOf(members.filter { it.first == groupId }.map { AppGroupMember(it.first, it.second) })
    override fun getAllMembers(): Flow<List<AppGroupMember>> =
        flowOf(members.map { AppGroupMember(it.first, it.second) })
    override suspend fun addMember(member: AppGroupMember) { members.add(member.groupId to member.packageName) }
    override suspend fun removeMember(groupId: Long, packageName: String) { members.remove(groupId to packageName) }
    override fun getGroupsForPackage(packageName: String): Flow<List<AppGroup>> = flowOf(emptyList())
    override suspend fun getMemberPackages(groupId: Long) =
        members.filter { it.first == groupId }.map { it.second }
    override suspend fun renameGroup(groupId: Long, name: String) { groups[groupId] = name }
    override suspend fun deleteMembersOfGroup(groupId: Long) { members.removeAll { it.first == groupId } }
    override suspend fun deleteGroupById(groupId: Long) { groups.remove(groupId) }
    override suspend fun deleteRulesOfGroup(groupId: Long) { rules.values.removeAll { it.groupId == groupId } }
    override suspend fun insertGroupRule(rule: BlockRule): Long {
        val id = nextId++; rules[id] = rule.copy(id = id); return id
    }
    override suspend fun updateGroupRule(rule: BlockRule) { rules[rule.id] = rule }
}

class GroupSaveTest {

    private val dao = FakeGroupDao()
    private val repo = BlockRuleRepository(mockk<BlockRuleDao>(relaxed = true), dao)
    private val rule = BlockRule(mode = "DELAY", packageName = "ignored")

    @Test
    fun `membershipChanges splits adds and removes`() {
        val c = membershipChanges(setOf("a", "b"), setOf("b", "c"))
        assertEquals(setOf("c"), c.toAdd)
        assertEquals(setOf("a"), c.toRemove)
    }

    @Test
    fun `saving a new group inserts it, its members and its rule with groupId set`() = runTest {
        val id = repo.saveGroup(null, "  Social ", setOf("a", "b"), rule)

        assertEquals("Social", dao.groups[id])
        assertEquals(setOf("a", "b"), dao.members.filter { it.first == id }.map { it.second }.toSet())
        val saved = dao.rules.values.single()
        assertEquals(id, saved.groupId)
        assertEquals(null, saved.packageName)
    }

    @Test
    fun `saving an existing group replaces members as a set and updates its rule`() = runTest {
        val id = repo.saveGroup(null, "Social", setOf("a", "b"), rule)
        val ruleId = dao.rules.keys.single()

        repo.saveGroup(id, "Feeds", setOf("b", "c"), dao.rules.getValue(ruleId).copy(mode = "HARD_BLOCK"))

        assertEquals("Feeds", dao.groups[id])
        assertEquals(setOf("b", "c"), dao.members.map { it.second }.toSet())
        assertEquals(1, dao.rules.size)
        assertEquals("HARD_BLOCK", dao.rules.getValue(ruleId).mode)
    }

    @Test
    fun `deleting a group removes its members and rules explicitly but not other groups'`() = runTest {
        val a = repo.saveGroup(null, "A", setOf("x"), rule)
        val b = repo.saveGroup(null, "B", setOf("y"), rule)

        repo.deleteGroupWithRules(a)

        assertEquals(setOf(b), dao.groups.keys)
        assertEquals(listOf(b to "y"), dao.members.toList())
        assertEquals(listOf(b), dao.rules.values.map { it.groupId })
    }

    @Test
    fun `renaming trims the name`() = runTest {
        val id = repo.saveGroup(null, "A", emptySet(), rule)
        repo.renameGroup(id, " Z ")
        assertEquals("Z", dao.groups[id])
    }
}
