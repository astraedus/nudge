package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.dao.AppGroupDao
import com.astraedus.nudge.data.db.dao.BlockRuleDao
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.export.RuleExporter
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.data.repository.normalizedAllAppsRule
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
 * L4. All apps rules through a whole backup: export from one phone, import into another (spec
 * 5.8). The real [RuleExporter], [ImportRulesUseCase] and [BlockRuleRepository] run over an
 * in-memory stand-in for the two DAOs, so the repository's write checks (one target, the
 * normalised All apps row) are part of what is tested, and group ids really do differ between
 * the two phones.
 */
class ImportAllAppsRulesTest {

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

    private fun useCase(db: FakeDb) = ImportRulesUseCase(
        db.repository,
        mockk<UsageRepository>(relaxed = true),
        mockk<NudgePreferences>(relaxed = true),
        RuleExporter()
    )

    private suspend fun import(db: FakeDb, json: String): ImportOutcome {
        val useCase = useCase(db)
        return useCase.execute(useCase.preview(json).result)
    }

    private suspend fun weakens(db: FakeDb, json: String): Boolean {
        val useCase = useCase(db)
        return useCase.weakensProtection(useCase.preview(json).result)
    }

    private fun allApps(vararg except: String, groups: String? = null, enabled: Boolean = true) = BlockRule(
        mode = "HARD_BLOCK",
        allApps = true,
        exceptPackages = except.joinToString(",").ifEmpty { null },
        exceptGroupIds = groups,
        enabled = enabled
    )

    /** The phone a backup is made on: two groups, an app rule, a group rule and an All apps rule. */
    private fun sourcePhone(): FakeDb = FakeDb(firstId = 1).apply {
        val work = group("Work", "com.slack")
        val social = group("Social", "com.insta")
        rule(BlockRule(packageName = "com.yt", mode = "DELAY", delaySeconds = 15))
        rule(BlockRule(groupId = social, mode = "HARD_BLOCK"))
        rule(
            allApps("com.maps", "com.phone", groups = "$work,999").copy(
                scheduleDays = "1,2,3,4,5",
                scheduleStartMinute = 540,
                scheduleEndMinute = 1020,
                delaySeconds = 30
            )
        )
    }

    // --- Round trip ---

    @Test
    fun `an All apps rule survives a move to a phone where the group ids differ`() = runTest {
        val source = sourcePhone()
        val target = FakeDb(firstId = 500).apply { group("Other", "com.game") }

        val outcome = import(target, source.export())

        assertNull(outcome.error)
        assertEquals(3, outcome.importedCount)
        assertEquals(0, outcome.invalidCount)
        assertEquals(2, outcome.groupsCreated)

        val targetWork = target.groupId("Work")
        val sourceRule = source.rules.single { it.allApps }
        val imported = target.rules.single { it.allApps }
        // The stale id 999 had no group to name, so it is gone; Work is remapped to its new id.
        assertEquals(
            normalizedAllAppsRule(sourceRule).copy(id = imported.id, createdAt = imported.createdAt, exceptGroupIds = "$targetWork"),
            imported
        )
        assertTrue(targetWork != source.groupId("Work"))
        assertEquals(target.groupId("Social"), target.rules.single { it.groupId != null }.groupId)
        assertEquals("com.yt", target.rules.single { it.packageName != null }.packageName)
    }

    @Test
    fun `an All apps rule from a hand-edited file is stored with only the options it offers`() = runTest {
        val target = FakeDb(firstId = 1)
        import(
            target,
            """
            {"version": 1, "extendedRules": [{
                "allApps": true, "mode": "DELAY", "delaySeconds": 15,
                "keepPassOnShortTrips": true, "dailyLimitMinutes": 30, "grayscale": true,
                "webDomains": "a.com", "inAppFeatures": "REELS"
            }]}
            """.trimIndent()
        )

        val stored = target.rules.single()
        assertTrue(stored.allApps)
        assertFalse(stored.keepPassOnShortTrips)
        assertNull(stored.dailyLimitMinutes)
        assertFalse(stored.grayscale)
        assertNull(stored.webDomains)
        assertNull(stored.inAppFeatures)
        assertEquals(15, stored.delaySeconds)
    }

    // --- Allowed groups by name ---

    @Test
    fun `an allowed group with no group by that name is dropped, and a name already on this phone resolves`() = runTest {
        val target = FakeDb(firstId = 1)
        val other = target.group("Other", "com.game")

        import(
            target,
            """
            {"version": 1,
             "groups": [{"name": "Work", "members": ["com.slack"]}],
             "extendedRules": [
                {"allApps": true, "mode": "HARD_BLOCK", "exceptGroups": ["Work", "Ghost", "Other"]},
                {"allApps": true, "mode": "DELAY", "exceptGroups": ["Ghost"]}
             ]}
            """.trimIndent()
        )

        val (first, second) = target.rules
        assertEquals("${target.groupId("Work")},$other", first.exceptGroupIds)
        assertNull(second.exceptGroupIds)
    }

    @Test
    fun `a group rule whose group is nowhere is skipped and counted, not written with no target`() = runTest {
        val target = FakeDb(firstId = 1)

        val outcome = import(
            target,
            """
            {"version": 1, "rules": [
                {"groupName": "Ghost", "mode": "HARD_BLOCK"},
                {"packageName": "com.yt", "mode": "DELAY"}
            ]}
            """.trimIndent()
        )

        assertEquals(1, outcome.importedCount)
        assertEquals(1, outcome.invalidCount)
        assertEquals(listOf("Rule for group \"Ghost\": no group by that name"), outcome.invalidReasons)
        assertEquals(listOf("com.yt"), target.rules.map { it.packageName })
    }

    // --- Duplicates ---

    @Test
    fun `importing the same backup twice adds nothing the second time`() = runTest {
        val target = FakeDb(firstId = 500)
        val file = sourcePhone().export()

        import(target, file)
        val second = import(target, file)

        assertEquals(0, second.importedCount)
        assertEquals(3, second.duplicateCount)
        assertEquals(3, target.rules.size)
    }

    @Test
    fun `an All apps rule is a duplicate only with the same allowed apps and groups, in any order`() = runTest {
        val target = FakeDb(firstId = 1)
        val work = target.group("Work", "com.slack")
        val social = target.group("Social", "com.insta")
        target.rule(allApps("com.maps", "com.phone", groups = "$work,$social"))
        target.rule(BlockRule(packageName = "com.yt", mode = "HARD_BLOCK"))

        fun file(packages: String, groups: String) = """
            {"version": 1, "extendedRules": [
                {"allApps": true, "mode": "HARD_BLOCK", "exceptPackages": $packages, "exceptGroups": $groups}
            ]}
        """.trimIndent()

        val reordered = import(target, file("""["com.phone", "com.maps"]""", """["Social", "Work"]"""))
        assertEquals(1, reordered.duplicateCount)

        val morePackages = import(target, file("""["com.maps", "com.phone", "com.yt"]""", """["Work", "Social"]"""))
        assertEquals(1, morePackages.importedCount)

        val fewerGroups = import(target, file("""["com.maps", "com.phone"]""", """["Work"]"""))
        assertEquals(1, fewerGroups.importedCount)

        // An All apps rule never matches an app rule, whatever the mode.
        val bare = import(target, file("[]", "[]"))
        assertEquals(1, bare.importedCount)
        assertEquals(5, target.rules.size)
    }

    // --- Strict Mode gate ---

    private fun phoneWithAllowedSocial(enabled: Boolean = true): FakeDb = FakeDb(firstId = 1).apply {
        val social = group("Social", "com.insta")
        rule(allApps(groups = "$social", enabled = enabled))
    }

    private val socialGrows = """{"version": 1, "groups": [{"name": "Social", "members": ["com.insta", "com.tiktok"]}]}"""

    @Test
    fun `merging a new member into a group an enabled All apps rule allows weakens protection`() = runTest {
        assertTrue(weakens(phoneWithAllowedSocial(), socialGrows))
    }

    @Test
    fun `the same merge is free when that rule is off`() = runTest {
        assertFalse(weakens(phoneWithAllowedSocial(enabled = false), socialGrows))
    }

    @Test
    fun `a merge that adds no member, or a group new to this phone, is free`() = runTest {
        val phone = phoneWithAllowedSocial()
        assertFalse(weakens(phone, """{"version": 1, "groups": [{"name": "Social", "members": ["com.insta"]}]}"""))
        assertFalse(weakens(phone, """{"version": 1, "groups": [{"name": "Games", "members": ["com.tiktok"]}]}"""))
    }

    @Test
    fun `an imported All apps rule is added for free, even one allowing a group the file grows`() = runTest {
        // Nothing here allows Social yet: the rule allowing it arrives with the file, so no app
        // that was covered before stops being covered.
        val phone = FakeDb(firstId = 1).apply {
            group("Social", "com.insta")
            rule(BlockRule(packageName = "com.yt", mode = "HARD_BLOCK"))
        }
        val file = """
            {"version": 1,
             "groups": [{"name": "Social", "members": ["com.insta", "com.tiktok"]}],
             "extendedRules": [{"allApps": true, "mode": "HARD_BLOCK", "exceptGroups": ["Social"]}]}
        """.trimIndent()

        assertFalse(weakens(phone, file))
        assertFalse(weakens(phone, sourcePhone().export()))
    }
}
