package com.astraedus.nudge.testing

import com.astraedus.nudge.data.db.dao.BlockRuleDao
import com.astraedus.nudge.data.db.entity.BlockRule
import kotlinx.coroutines.flow.Flow

/**
 * [BlockRuleDao] over [FakeRuleDatabase]'s `block_rules` table: each query is its SQL, read off
 * the in-memory rows. Build one through [FakeRuleDatabase] so it shares tables with the group DAO.
 */
class FakeBlockRuleDao internal constructor(private val db: FakeRuleDatabase) : BlockRuleDao {

    override fun getAll(): Flow<List<BlockRule>> = db.query(db.ruleTable) { it }

    override fun getEnabled(): Flow<List<BlockRule>> = db.query(db.ruleTable) { rows -> rows.filter { it.enabled } }

    override fun getByPackage(pkg: String): Flow<List<BlockRule>> =
        db.query(db.ruleTable) { rows -> rows.filter { it.packageName == pkg } }

    override fun getByGroupId(groupId: Long): Flow<List<BlockRule>> =
        db.query(db.ruleTable) { rows -> rows.filter { it.groupId == groupId } }

    override fun getAllAppsRules(): Flow<List<BlockRule>> =
        db.query(db.ruleTable) { rows -> rows.filter { it.allApps }.sortedBy { it.id } }

    override suspend fun getById(id: Long): BlockRule? = db.rules.firstOrNull { it.id == id }

    override suspend fun insert(rule: BlockRule): Long = db.insertRuleRow(rule)

    override suspend fun update(rule: BlockRule) = db.updateRuleRows({ it.id == rule.id }) { rule }

    override suspend fun delete(rule: BlockRule) = db.deleteRuleRows { it.id == rule.id }

    override suspend fun deleteById(id: Long) = db.deleteRuleRows { it.id == id }

    override suspend fun deleteByPackageName(packageName: String) =
        db.deleteRuleRows { it.packageName == packageName }

    override suspend fun setEnabledForPackage(packageName: String, enabled: Boolean) =
        db.updateRuleRows({ it.packageName == packageName }) { it.copy(enabled = enabled) }
}
