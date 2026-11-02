package com.astraedus.nudge.data.repository

import com.astraedus.nudge.data.db.dao.AppGroupDao
import com.astraedus.nudge.data.db.dao.BlockRuleDao
import com.astraedus.nudge.data.db.dao.exceptGroupIdsWithout
import com.astraedus.nudge.data.db.dao.memberRowChanges
import com.astraedus.nudge.data.db.dao.memberRows
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.packages.AppCategory
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** In-memory [AppGroupDao]: the `open` transactional methods under test run for real on it. */
private class FakeGroupDao : AppGroupDao() {
    val groups = mutableMapOf<Long, String>()
    val categories = mutableMapOf<Long, String?>()
    /** (group, package) -> excluded. */
    val members = mutableMapOf<Pair<Long, String>, Boolean>()
    val rules = mutableMapOf<Long, BlockRule>()
    var nextId = 1L

    override fun getAll(): Flow<List<AppGroup>> = flowOf(groups.map { AppGroup(it.key, it.value) })
    override fun getById(id: Long): Flow<AppGroup?> = flowOf(groups[id]?.let { AppGroup(id, it) })
    override suspend fun insert(group: AppGroup): Long {
        groups[nextId] = group.name; categories[nextId] = group.categories; return nextId++
    }
    override suspend fun update(group: AppGroup) { groups[group.id] = group.name }
    override suspend fun delete(group: AppGroup) { groups.remove(group.id) }
    override fun getMembersForGroup(groupId: Long): Flow<List<AppGroupMember>> =
        flowOf(members.filter { it.key.first == groupId }.map { AppGroupMember(it.key.first, it.key.second, it.value) })
    override fun getAllMembers(): Flow<List<AppGroupMember>> =
        flowOf(members.map { AppGroupMember(it.key.first, it.key.second, it.value) })
    override suspend fun addMember(member: AppGroupMember) {
        members[member.groupId to member.packageName] = member.excluded
    }
    override suspend fun removeMember(groupId: Long, packageName: String) { members.remove(groupId to packageName) }
    override fun getGroupsForPackage(packageName: String): Flow<List<AppGroup>> = flowOf(emptyList())
    override suspend fun getMemberRows(groupId: Long) =
        members.filter { it.key.first == groupId }.map { AppGroupMember(groupId, it.key.second, it.value) }
    override suspend fun setCategories(groupId: Long, categories: String?) { this.categories[groupId] = categories }
    override suspend fun renameGroup(groupId: Long, name: String) { groups[groupId] = name }
    override suspend fun deleteMembersOfGroup(groupId: Long) { members.keys.removeAll { it.first == groupId } }
    override suspend fun deleteGroupById(groupId: Long) { groups.remove(groupId) }
    override suspend fun deleteRulesOfGroup(groupId: Long) { rules.values.removeAll { it.groupId == groupId } }
    override suspend fun getRulesWithExceptGroups() = rules.values.filter { it.exceptGroupIds != null }
    override suspend fun setExceptGroupIds(ruleId: Long, exceptGroupIds: String?) {
        rules[ruleId]?.let { rules[ruleId] = it.copy(exceptGroupIds = exceptGroupIds) }
    }
    override suspend fun insertGroupRule(rule: BlockRule): Long {
        val id = nextId++; rules[id] = rule.copy(id = id); return id
    }
    override suspend fun updateGroupRule(rule: BlockRule) { rules[rule.id] = rule }
}

class GroupSaveTest {

    private val dao = FakeGroupDao()
    private val repo = BlockRuleRepository(mockk<BlockRuleDao>(relaxed = true), dao)
    private val rule = BlockRule(mode = "DELAY", packageName = "ignored")

    // saveGroup(groupId, name, categories, includes, excludes, rule)
    private suspend fun BlockRuleRepository.save(
        id: Long?, name: String, includes: Set<String>, r: BlockRule = rule,
        categories: Set<AppCategory> = emptySet(), excludes: Set<String> = emptySet()
    ) = saveGroup(id, name, categories, includes, excludes, r)

    private fun packagesOf(id: Long) = dao.members.filter { it.key.first == id }.keys.map { it.second }.toSet()

    @Test
    fun `memberRowChanges splits writes and removes`() {
        val c = memberRowChanges(mapOf("a" to false, "b" to false), mapOf("b" to false, "c" to false))
        assertEquals(mapOf("c" to false), c.toWrite)
        assertEquals(setOf("a"), c.toRemove)
    }

    @Test
    fun `an include flipped to removed-by-you is a write, not a remove plus an add`() {
        val c = memberRowChanges(mapOf("a" to false), mapOf("a" to true))
        assertEquals(mapOf("a" to true), c.toWrite)
        assertEquals(emptySet<String>(), c.toRemove)
    }

    @Test
    fun `a row dropped entirely is a remove and an unchanged row writes nothing`() {
        val c = memberRowChanges(mapOf("a" to true, "b" to false), mapOf("b" to false))
        assertEquals(emptyMap<String, Boolean>(), c.toWrite)
        assertEquals(setOf("a"), c.toRemove)
    }

    @Test
    fun `memberRows maps includes to false and excludes to true`() {
        assertEquals(mapOf("a" to false, "b" to true), memberRows(setOf("a"), setOf("b")))
    }

    @Test
    fun `memberRows rejects a package that is both added and removed`() {
        assertThrows(IllegalArgumentException::class.java) { memberRows(setOf("a"), setOf("a")) }
    }

    @Test
    fun `saving a new group inserts it, its members and its rule with groupId set`() = runTest {
        val id = repo.save(null, "  Social ", setOf("a", "b"))

        assertEquals("Social", dao.groups[id])
        assertEquals(setOf("a", "b"), packagesOf(id))
        val saved = dao.rules.values.single()
        assertEquals(id, saved.groupId)
        assertEquals(null, saved.packageName)
    }

    @Test
    fun `saving an existing group replaces members as a set and updates its rule`() = runTest {
        val id = repo.save(null, "Social", setOf("a", "b"))
        val ruleId = dao.rules.keys.single()

        repo.save(id, "Feeds", setOf("b", "c"), dao.rules.getValue(ruleId).copy(mode = "HARD_BLOCK"))

        assertEquals("Feeds", dao.groups[id])
        assertEquals(setOf("b", "c"), packagesOf(id))
        assertEquals(1, dao.rules.size)
        assertEquals("HARD_BLOCK", dao.rules.getValue(ruleId).mode)
    }

    @Test
    fun `deleting a group removes its members and rules explicitly but not other groups'`() = runTest {
        val a = repo.save(null, "A", setOf("x"))
        val b = repo.save(null, "B", setOf("y"))

        repo.deleteGroupWithRules(a)

        assertEquals(setOf(b), dao.groups.keys)
        assertEquals(mapOf((b to "y") to false), dao.members)
        assertEquals(listOf(b), dao.rules.values.map { it.groupId })
    }

    // -- an allowed group's id leaves every All apps rule with the group (L1 on the fake DAO) --

    private fun allAppsRule(exceptGroupIds: String?) =
        BlockRule(mode = "HARD_BLOCK", allApps = true, exceptGroupIds = exceptGroupIds)

    private fun putRule(rule: BlockRule): Long {
        val id = dao.nextId++
        dao.rules[id] = rule.copy(id = id)
        return id
    }

    @Test
    fun `exceptGroupIdsWithout rewrites only the rules that list the group`() {
        val rules = listOf(
            allAppsRule("3,5,7").copy(id = 1),
            allAppsRule("5").copy(id = 2),
            allAppsRule("3").copy(id = 3),
            allAppsRule(null).copy(id = 4),
            allAppsRule("x,5,,").copy(id = 5)
        )
        assertEquals(
            mapOf(1L to "3,7", 2L to null, 5L to null),
            exceptGroupIdsWithout(5, rules)
        )
    }

    @Test
    fun `deleting a group takes its id off every All apps rule's allowed groups`() = runTest {
        val gone = repo.save(null, "Work", setOf("w"))
        val kept = repo.save(null, "Music", setOf("m"))
        val both = putRule(allAppsRule("$gone,$kept"))
        val onlyGone = putRule(allAppsRule("$gone"))
        val onlyKept = putRule(allAppsRule("$kept"))

        repo.deleteGroupWithRules(gone)

        assertEquals("$kept", dao.rules.getValue(both).exceptGroupIds)
        assertEquals(null, dao.rules.getValue(onlyGone).exceptGroupIds)
        assertEquals("$kept", dao.rules.getValue(onlyKept).exceptGroupIds)
        // The All apps rules themselves stay: deleting an allowed group never deletes the lock.
        assertEquals(true, dao.rules.getValue(onlyGone).allApps)
    }

    @Test
    fun `saving a group with an All apps rule throws instead of writing a two-target row`() {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                repo.save(null, "A", emptySet(), BlockRule(mode = "DELAY", allApps = true))
            }
        }
        assertEquals(emptyMap<Long, String>(), dao.groups)
    }

    // -- category groups: categories column + include / removed-by-you rows --

    @Test
    fun `saving a category group stores the categories CSV and the removed-by-you row`() = runTest {
        val id = repo.save(
            null, "Social", setOf("hand"),
            categories = setOf(AppCategory.VIDEO, AppCategory.SOCIAL), excludes = setOf("gone")
        )

        assertEquals("SOCIAL,VIDEO", dao.categories[id])
        assertEquals(mapOf((id to "hand") to false, (id to "gone") to true), dao.members)
    }

    @Test
    fun `saving with no categories leaves the column null`() = runTest {
        val id = repo.save(null, "Hand picked", setOf("a"))
        assertEquals(null, dao.categories[id])
    }

    @Test
    fun `re-saving flips an include to removed-by-you in place and drops a cleared row`() = runTest {
        val id = repo.save(null, "G", setOf("a", "b"), categories = setOf(AppCategory.GAME))

        repo.save(id, "G", setOf("b"), categories = emptySet(), excludes = setOf("a"))

        assertEquals(null, dao.categories[id])
        assertEquals(mapOf((id to "a") to true, (id to "b") to false), dao.members)

        repo.save(id, "G", emptySet())
        assertEquals(emptyMap<Pair<Long, String>, Boolean>(), dao.members)
    }

    @Test
    fun `saving a package as both added and removed throws before anything is written`() {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.save(null, "G", setOf("a"), excludes = setOf("a")) }
        }
        assertEquals(emptyMap<Long, String>(), dao.groups)
    }

    @Test
    fun `renaming trims the name`() = runTest {
        val id = repo.save(null, "A", emptySet())
        repo.renameGroup(id, " Z ")
        assertEquals("Z", dao.groups[id])
    }
}
