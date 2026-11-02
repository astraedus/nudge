package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.export.RuleExporter
import com.astraedus.nudge.domain.packages.EssentialPackages
import com.astraedus.nudge.testing.FakeInstalledAppsRepository
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeRuleDatabase
import com.astraedus.nudge.testing.FakeUsageRepository
import com.astraedus.nudge.testing.aBlockRule
import com.astraedus.nudge.testing.anAppGroup
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What a backup file is allowed to leave behind.
 *
 * [issue #43](https://github.com/astraedus/nudge/issues/43): the export collected only the ENABLED
 * rules, so a rule the user had switched off -- which is how people park a rule they mean to come
 * back to -- was silently absent from their backup, and a restore onto a wiped phone lost it. On
 * the app's only backup path, "some of your configuration is not in this file" has to be a bug.
 *
 * Uses the real [RuleExporter]: the thing being pinned is what reaches the FILE, and a fake
 * serializer would happily keep passing while the file lost a rule.
 */
class ExportRulesUseCaseTest {

    private val db = FakeRuleDatabase()
    private lateinit var essentials: EssentialPackages
    private lateinit var useCase: ExportRulesUseCase

    @Before
    fun setUp() {
        essentials = mockk()
        coEvery { essentials.current() } returns emptySet()
        coEvery { essentials.browsers() } returns emptySet()

        useCase = ExportRulesUseCase(
            db.repository,
            FakeUsageRepository(),
            FakeNudgePreferences(),
            RuleExporter(),
            FakeInstalledAppsRepository(),
            essentials
        )
    }

    private fun withRules(vararg rules: BlockRule) {
        rules.forEach { db.seedRule(it) }
    }

    @Test
    fun `a rule the user switched off is still in the backup, switched off`() = runTest {
        withRules(
            aBlockRule(packageName = "com.instagram.android", mode = "HARD_BLOCK"),
            aBlockRule(packageName = "com.zhiliaoapp.musically", mode = "DELAY", enabled = false)
        )

        val exported = RuleExporter().importRules(useCase.invoke())

        assertEquals(
            listOf("com.instagram.android", "com.zhiliaoapp.musically"),
            exported.rules.map { it.packageName }
        )
        assertTrue(exported.rules.single { it.packageName == "com.instagram.android" }.enabled)
        assertFalse(exported.rules.single { it.packageName == "com.zhiliaoapp.musically" }.enabled)
    }

    /**
     * The regression itself, stated as the collection rather than the result: the use case must ask
     * for ALL rules. Asking for the enabled ones is the whole defect: every rule seeded here is
     * disabled, so the real repository's `getEnabledRules` would hand back none and the size
     * assertion below fails loudly rather than quietly exporting less.
     */
    @Test
    fun `the export reads every rule, not the enabled ones`() = runTest {
        withRules(
            aBlockRule(packageName = "com.a", mode = "DELAY", enabled = false),
            aBlockRule(packageName = "com.b", mode = "DELAY", enabled = false)
        )

        val exported = RuleExporter().importRules(useCase.invoke())

        assertEquals(2, exported.rules.size)
        assertTrue("a backup of only disabled rules is still a backup", exported.rules.none { it.enabled })
    }

    @Test
    fun `a disabled rule keeps the rest of its configuration`() = runTest {
        withRules(
            aBlockRule(
                packageName = "com.reddit.frontpage",
                mode = "BREATHING",
                delaySeconds = 30,
                dailyLimitMinutes = 20,
                enabled = false,
                scheduleDays = "1,2,3,4,5",
                scheduleStartMinute = 540,
                scheduleEndMinute = 1020,
                grayscale = true,
                webDomains = "reddit.com",
                webBlockMode = "HARD_BLOCK"
            )
        )

        val rule = RuleExporter().importRules(useCase.invoke()).rules.single()

        assertFalse(rule.enabled)
        assertEquals("BREATHING", rule.mode)
        assertEquals(30, rule.delaySeconds)
        assertEquals(20, rule.dailyLimitMinutes)
        assertEquals("1,2,3,4,5", rule.scheduleDays)
        assertEquals(540, rule.scheduleStartMinute)
        assertEquals(1020, rule.scheduleEndMinute)
        assertTrue(rule.grayscale)
        assertEquals("reddit.com", rule.webDomains)
        assertEquals("HARD_BLOCK", rule.webBlockMode)
    }

    /**
     * A disabled rule that belongs to a group must still resolve to its group NAME. Groups are
     * exported by name for portability, and the mapping is built from the groups query rather than
     * from the rules, so including disabled rules cannot orphan one.
     */
    @Test
    fun `a disabled rule in a group still names its group`() = runTest {
        db.seedGroup(anAppGroup("Socials", id = 7), "com.instagram.android")
        withRules(aBlockRule(groupId = 7, mode = "HARD_BLOCK", enabled = false))

        val exported = RuleExporter().importRules(useCase.invoke())

        assertEquals("Socials", exported.rules.single().groupName)
        assertFalse(exported.rules.single().enabled)
        assertEquals(listOf("com.instagram.android"), exported.groups.single().members)
    }
}
