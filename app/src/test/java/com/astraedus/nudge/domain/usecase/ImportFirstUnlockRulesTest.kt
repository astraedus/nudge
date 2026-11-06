package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.dao.AppGroupDao
import com.astraedus.nudge.data.db.dao.BlockRuleDao
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.export.ExportedSettings
import com.astraedus.nudge.data.export.RuleExporter
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.packages.EssentialPackages
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L4. First-unlock rules through a whole backup (spec 5.8, 5.3). The real [RuleExporter],
 * [ImportRulesUseCase] and [BlockRuleRepository] run over an in-memory stand-in for the two DAOs,
 * so the repository's write checks and its arming of every enabled write are part of what is
 * tested, and a group rule really has to find its group on the importing phone.
 */
class ImportFirstUnlockRulesTest {

    /** One phone's database: rules, groups and members, with ids handed out from [firstId]. */
    private class FakeDb(firstId: Long) {
        val rules = mutableListOf<BlockRule>()
        val groups = mutableListOf<AppGroup>()
        val members = mutableListOf<AppGroupMember>()
        private var nextId = firstId

        val repository: BlockRuleRepository = run {
            val ruleDao = mockk<BlockRuleDao>(relaxed = true)
            every { ruleDao.getAll() } answers { flowOf(rules.toList()) }
            coEvery { ruleDao.insert(any()) } answers {
                val row = firstArg<BlockRule>().copy(id = nextId++)
                rules += row
                row.id
            }
            coEvery { ruleDao.update(any()) } answers {
                val row = firstArg<BlockRule>()
                rules.replaceAll { if (it.id == row.id) row else it }
            }
            val groupDao = mockk<AppGroupDao>(relaxed = true)
            every { groupDao.getAll() } answers { flowOf(groups.toList()) }
            coEvery { groupDao.insert(any()) } answers {
                val row = firstArg<AppGroup>().copy(id = nextId++)
                groups += row
                row.id
            }
            coEvery { groupDao.addMember(any()) } answers { members += firstArg<AppGroupMember>() }
            every { groupDao.getMembersForGroup(any()) } answers {
                val id = firstArg<Long>()
                flowOf(members.filter { it.groupId == id })
            }
            BlockRuleRepository(ruleDao, groupDao)
        }

        fun group(name: String, vararg packages: String): Long {
            val id = nextId++
            groups += AppGroup(id = id, name = name)
            packages.forEach { members += AppGroupMember(id, it) }
            return id
        }

        fun rule(rule: BlockRule): Long {
            val id = nextId++
            rules += rule.copy(id = id)
            return id
        }

        fun groupId(name: String): Long = groups.single { it.name == name }.id

        fun export(): String = RuleExporter().exportRules(rules, groups, members.groupBy { it.groupId })
    }

    private fun useCase(db: FakeDb, phoneDayStartMinute: Int = 240) = ImportRulesUseCase(
        db.repository,
        mockk<UsageRepository>(relaxed = true),
        mockk<NudgePreferences> {
            coEvery { exportableSettings() } returns
                ExportedSettings(dayStartMinute = phoneDayStartMinute)
        },
        RuleExporter(),
        mockk<InstalledAppsRepository> { coEvery { getInstalledApps() } returns emptyList() },
        mockk<EssentialPackages> {
            coEvery { current() } returns emptySet()
            coEvery { browsers() } returns emptySet()
        }
    )

    private suspend fun import(db: FakeDb, json: String): ImportOutcome {
        val useCase = useCase(db)
        return useCase.execute(useCase.preview(json).result)
    }

    // --- Round trip ------------------------------------------------------------------------

    @Test
    fun `an imported first-unlock rule is armed at import time and keeps its window`() = runTest {
        val source = FakeDb(firstId = 1).apply {
            rule(
                BlockRule(
                    packageName = "com.reddit",
                    mode = "HARD_BLOCK",
                    firstUnlockMinutes = 30,
                    // The source phone's stamp must not ride in the file or be copied.
                    armedAt = 999
                )
            )
        }
        val target = FakeDb(firstId = 500)

        val outcome = import(target, source.export())

        assertNull(outcome.error)
        assertEquals(1, outcome.importedCount)
        val imported = target.rules.single()
        assertEquals("com.reddit", imported.packageName)
        assertEquals(30, imported.firstUnlockMinutes)
        assertTrue("the importing repository arms the row", imported.armedAt > 0)
        assertTrue("not the source phone's stamp", imported.armedAt != 999L)
    }

    @Test
    fun `a first-unlock group rule finds its group by name on this phone`() = runTest {
        val source = FakeDb(firstId = 1).apply {
            val social = group("Social", "com.insta")
            rule(BlockRule(groupId = social, mode = "HARD_BLOCK", firstUnlockMinutes = 45))
        }
        val target = FakeDb(firstId = 500)

        val outcome = import(target, source.export())

        assertNull(outcome.error)
        assertEquals(1, outcome.importedCount)
        val imported = target.rules.single()
        assertEquals(target.groupId("Social"), imported.groupId)
        assertEquals(45, imported.firstUnlockMinutes)
        assertTrue(imported.armedAt > 0)
    }

    @Test
    fun `a first-unlock All apps rule keeps its window and its except list`() = runTest {
        val target = FakeDb(firstId = 500)

        import(
            target,
            """
            {"version": 1, "extendedRules": [{
                "allApps": true, "mode": "HARD_BLOCK",
                "exceptPackages": ["com.maps"], "firstUnlockMinutes": 15
            }]}
            """.trimIndent()
        )

        val imported = target.rules.single()
        assertTrue(imported.allApps)
        assertEquals(15, imported.firstUnlockMinutes)
        assertEquals("com.maps", imported.exceptPackages)
        assertTrue(imported.armedAt > 0)
    }

    // --- Duplicates ------------------------------------------------------------------------

    @Test
    fun `the window is part of a rule's identity, so two lengths are not duplicates`() = runTest {
        val target = FakeDb(firstId = 1)
        val file = """
            {"version": 1, "extendedRules": [
                {"packageName": "com.reddit", "mode": "HARD_BLOCK", "firstUnlockMinutes": 30},
                {"packageName": "com.reddit", "mode": "HARD_BLOCK", "firstUnlockMinutes": 60}
            ]}
        """.trimIndent()

        val first = import(target, file)
        assertEquals(2, first.importedCount)

        val second = import(target, file)
        assertEquals(0, second.importedCount)
        assertEquals(2, second.duplicateCount)
        assertEquals(setOf(30, 60), target.rules.map { it.firstUnlockMinutes }.toSet())
    }

    // --- Strict Mode gate: dayStartMinute --------------------------------------------------

    private fun dayStartFile(dayStartMinute: Int) = """
        {"version": 1, "settings": {"dayStartMinute": $dayStartMinute}}
    """.trimIndent()

    private suspend fun weakens(db: FakeDb, dayStartMinute: Int, phoneDayStartMinute: Int = 240): Boolean {
        val useCase = useCase(db, phoneDayStartMinute)
        return useCase.weakensProtection(useCase.preview(dayStartFile(dayStartMinute)).result)
    }

    private fun phoneWithFirstUnlockRule(enabled: Boolean = true) = FakeDb(firstId = 1).apply {
        rule(
            BlockRule(
                packageName = "com.reddit",
                mode = "HARD_BLOCK",
                firstUnlockMinutes = 30,
                enabled = enabled
            )
        )
    }

    @Test
    fun `changing the day start is weakening while an enabled first-unlock rule exists, either direction`() = runTest {
        val phone = phoneWithFirstUnlockRule()
        assertTrue("later start", weakens(phone, 300))
        assertTrue("earlier start", weakens(phone, 180))
    }

    @Test
    fun `an unchanged day start is free even with a first-unlock rule`() = runTest {
        assertFalse(weakens(phoneWithFirstUnlockRule(), 240))
    }

    @Test
    fun `changing the day start is free when that phone's first-unlock rule is off or absent`() = runTest {
        assertFalse("rule switched off", weakens(phoneWithFirstUnlockRule(enabled = false), 300))

        val ordinaryPhone = FakeDb(firstId = 1).apply {
            rule(BlockRule(packageName = "com.yt", mode = "DELAY"))
        }
        assertFalse("no first-unlock rule at all", weakens(ordinaryPhone, 300))
    }

    @Test
    fun `a file that does not carry the day start never weakens on that axis`() = runTest {
        val useCase = useCase(phoneWithFirstUnlockRule())

        val parsed = useCase.preview(
            """{"version": 1, "settings": {"strictModeEnabled": true}}"""
        ).result

        assertFalse(useCase.weakensProtection(parsed))
    }
}
