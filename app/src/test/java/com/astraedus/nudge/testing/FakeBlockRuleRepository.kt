package com.astraedus.nudge.testing

import com.astraedus.nudge.data.repository.BlockRuleRepository

/**
 * The REAL [BlockRuleRepository] over in-memory tables, for JVM tests. Its validation
 * (`checkedRow`, the legacy two-target repair, All apps normalisation) and the group DAO's
 * transactions are production code; only Room is swapped for [FakeRuleDatabase].
 *
 * ```
 * val db = FakeRuleDatabase()
 * db.seedRule(aBlockRule(packageName = "com.instagram.android"))
 * val useCase = SomeUseCase(db.repository)       // or FakeBlockRuleRepository(db)
 * ...
 * assertEquals("HARD_BLOCK", db.rules.single().mode)
 * ```
 */
fun FakeBlockRuleRepository(db: FakeRuleDatabase = FakeRuleDatabase()): BlockRuleRepository = db.repository
