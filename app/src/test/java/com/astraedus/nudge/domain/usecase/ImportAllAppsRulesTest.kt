package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.export.RuleExporter
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.normalizedAllAppsRule
import com.astraedus.nudge.domain.packages.EssentialPackages
import com.astraedus.nudge.testing.FakeInstalledAppsRepository
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeRuleDatabase
import com.astraedus.nudge.testing.FakeUsageRepository
import com.astraedus.nudge.testing.aBlockRule
import com.astraedus.nudge.testing.anAppGroup
import io.mockk.coEvery
import io.mockk.mockk
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

    /**
     * One phone's database: the shared [FakeRuleDatabase], seeded with explicit ids handed out from
     * [firstId] (so the source phone's ids differ from ids the target assigns on import).
     */
    private class Phone(firstId: Long = 1) {
        val db = FakeRuleDatabase()
        val repository = db.repository
        val rules get() = db.rules
        val groups get() = db.groups
        private var nextId = firstId

        fun group(name: String, vararg packages: String): Long {
            val id = nextId++
            db.seedGroup(anAppGroup(name, id = id), *packages)
            return id
        }

        fun rule(rule: BlockRule): Long {
            val id = nextId++
            db.seedRule(rule.copy(id = id))
            return id
        }

        fun groupId(name: String): Long = groups.single { it.name == name }.id

        fun export(): String = RuleExporter().exportRules(rules, groups, db.members.groupBy { it.groupId })
    }

    private fun useCase(db: Phone) = ImportRulesUseCase(
        db.repository,
        FakeUsageRepository(),
        FakeNudgePreferences(),
        RuleExporter(),
        FakeInstalledAppsRepository(),
        mockk<EssentialPackages> {
            coEvery { current() } returns emptySet()
            coEvery { browsers() } returns emptySet()
        }
    )

    private suspend fun import(db: Phone, json: String): ImportOutcome {
        val useCase = useCase(db)
        return useCase.execute(useCase.preview(json).result)
    }

    private suspend fun weakens(db: Phone, json: String): Boolean {
        val useCase = useCase(db)
        return useCase.weakensProtection(useCase.preview(json).result)
    }

    private fun allApps(vararg except: String, groups: String? = null, enabled: Boolean = true) = aBlockRule(
        mode = "HARD_BLOCK",
        allApps = true,
        exceptPackages = except.joinToString(",").ifEmpty { null },
        exceptGroupIds = groups,
        enabled = enabled
    )

    /** The phone a backup is made on: two groups, an app rule, a group rule and an All apps rule. */
    private fun sourcePhone(): Phone = Phone(firstId = 500).apply {
        val work = group("Work", "com.slack")
        val social = group("Social", "com.insta")
        rule(aBlockRule(packageName = "com.yt", mode = "DELAY"))
        rule(aBlockRule(groupId = social, mode = "HARD_BLOCK"))
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
        val target = Phone().apply { group("Other", "com.game") }

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
            normalizedAllAppsRule(sourceRule).copy(
                id = imported.id,
                createdAt = imported.createdAt,
                exceptGroupIds = "$targetWork",
                // Import arms the rule (spec 5.8): a Morning Lock restored at 09:00 starts at the
                // next first unlock, not on the unlock this phone already had.
                armedAt = imported.armedAt
            ),
            imported
        )
        assertTrue("imported rules are armed now", imported.armedAt > 0)
        assertTrue(targetWork != source.groupId("Work"))
        assertEquals(target.groupId("Social"), target.rules.single { it.groupId != null }.groupId)
        assertEquals("com.yt", target.rules.single { it.packageName != null }.packageName)
    }

    @Test
    fun `an All apps rule from a hand-edited file is stored with only the options it offers`() = runTest {
        val target = Phone()
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
        assertEquals(30, stored.dailyLimitMinutes) // only adds, so it is kept
        assertFalse(stored.grayscale)
        assertNull(stored.webDomains)
        assertNull(stored.inAppFeatures)
        assertEquals(15, stored.delaySeconds)
    }

    // --- Allowed groups by name ---

    @Test
    fun `an allowed group with no group by that name is dropped, and a name already on this phone resolves`() = runTest {
        val target = Phone()
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
        val target = Phone()

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
        val target = Phone()
        val file = sourcePhone().export()

        import(target, file)
        val second = import(target, file)

        assertEquals(0, second.importedCount)
        assertEquals(3, second.duplicateCount)
        assertEquals(3, target.rules.size)
    }

    @Test
    fun `an All apps rule is a duplicate only with the same allowed apps and groups, in any order`() = runTest {
        val target = Phone()
        val work = target.group("Work", "com.slack")
        val social = target.group("Social", "com.insta")
        target.rule(allApps("com.maps", "com.phone", groups = "$work,$social"))
        target.rule(aBlockRule(packageName = "com.yt", mode = "HARD_BLOCK"))

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

    private fun phoneWithAllowedSocial(enabled: Boolean = true): Phone = Phone().apply {
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
        val phone = Phone().apply {
            group("Social", "com.insta")
            rule(aBlockRule(packageName = "com.yt", mode = "HARD_BLOCK"))
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
