package com.astraedus.nudge.data.repository

import com.astraedus.nudge.data.db.dao.AppGroupDao
import com.astraedus.nudge.data.db.dao.BlockRuleDao
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.model.RuleTarget
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * L1. The one-target invariant (spec 5.1) on every [BlockRuleRepository] rule write, and the All
 * apps row shape (spec 3.4) on every write that can carry one.
 */
class BlockRuleRepositoryTest {

    private val dao = mockk<BlockRuleDao>(relaxed = true)
    private val repo = BlockRuleRepository(dao, mockk<AppGroupDao>(relaxed = true))

    /** Every combination of the three target columns, with whether exactly one is set. */
    private val targets: List<Pair<BlockRule, Boolean>> = run {
        val out = mutableListOf<Pair<BlockRule, Boolean>>()
        for (pkg in listOf(null, "com.a")) for (group in listOf(null, 4L)) for (all in listOf(false, true)) {
            val count = listOf(pkg != null, group != null, all).count { it }
            out += BlockRule(id = 7, mode = "DELAY", packageName = pkg, groupId = group, allApps = all) to (count == 1)
        }
        out
    }

    /** The one malformed shape already on phones (old hand-edited imports): an app and a group. */
    private fun isLegacyAppAndGroup(rule: BlockRule) =
        rule.packageName != null && rule.groupId != null && !rule.allApps

    @Test
    fun `addRule and updateRule write exactly the well-formed targets and throw on the rest`() {
        for ((rule, wellFormed) in targets) {
            if (wellFormed) runBlocking { repo.addRule(rule) }
            else assertThrows("$rule", IllegalArgumentException::class.java) { runBlocking { repo.addRule(rule) } }

            if (wellFormed || isLegacyAppAndGroup(rule)) runBlocking { repo.updateRule(rule) }
            else assertThrows("$rule", IllegalArgumentException::class.java) { runBlocking { repo.updateRule(rule) } }
        }
        val wellFormedCount = targets.count { it.second }
        coVerify(exactly = wellFormedCount) { dao.insert(any()) }
        coVerify(exactly = wellFormedCount + 1) { dao.update(any()) }
    }

    @Test
    fun `updating a legacy app-and-group row writes the app rule the engine reads, instead of crashing`() = runTest {
        // A switch flipping such a row used to reach a throwing write (a crash). The engine has always
        // read it as the app (RuleTarget.of: the package wins), so the repair changes no behaviour.
        val legacy = BlockRule(id = 3, mode = "DELAY", packageName = "com.a", groupId = 4L, enabled = false)
        val row = slot<BlockRule>()
        coEvery { dao.update(capture(row)) } returns Unit

        repo.updateRule(legacy)

        assertEquals(legacy.copy(groupId = null), row.captured)
        assertEquals(
            RuleTarget.of(legacy.packageName, legacy.groupId, legacy.allApps, emptySet(), emptySet()),
            RuleTarget.of(row.captured.packageName, row.captured.groupId, row.captured.allApps, emptySet(), emptySet())
        )
    }

    @Test
    fun `saveAllAppsRule throws on anything but an All apps rule`() {
        for ((rule, _) in targets) {
            val isAllAppsOnly = rule.allApps && rule.packageName == null && rule.groupId == null
            if (isAllAppsOnly) continue
            assertThrows("$rule", IllegalArgumentException::class.java) {
                runBlocking { repo.saveAllAppsRule(rule) }
            }
        }
    }

    /** An All apps rule with every option it is never offered switched on. */
    private val loaded = BlockRule(
        id = 0,
        mode = "HARD_BLOCK",
        delaySeconds = 30,
        enabled = true,
        createdAt = 1234L,
        scheduleDays = "1,2",
        scheduleStartMinute = 540,
        scheduleEndMinute = 1020,
        allApps = true,
        exceptPackages = " com.a ,com.b,,com.a",
        exceptGroupIds = "3,x,3,4",
        keepPassOnShortTrips = true,
        dailyLimitMinutes = 30,
        autoKickAfter = 10,
        autoKickAfterMinutes = 5,
        remindAfterMinutes = 15,
        showCounter = true,
        showTimeRemaining = true,
        grayscale = true,
        webDomains = "a.com",
        webBlockMode = "DELAY",
        inAppFeatures = "REELS",
        followingSteer = true
    )

    private val expected = BlockRule(
        id = 0,
        mode = "HARD_BLOCK",
        delaySeconds = 30,
        enabled = true,
        createdAt = 1234L,
        scheduleDays = "1,2",
        scheduleStartMinute = 540,
        scheduleEndMinute = 1020,
        allApps = true,
        exceptPackages = "com.a,com.b",
        exceptGroupIds = "3,4",
        // Kept: a daily limit only adds, and the editor keeps it on save (orchestrator ruling).
        dailyLimitMinutes = 30
    )

    @Test
    fun `an All apps rule is stored with only the options its editor offers`() {
        assertEquals(expected, normalizedAllAppsRule(loaded))
    }

    @Test
    fun `every All apps write path stores the normalised row`() = runTest {
        val inserted = mutableListOf<BlockRule>()
        val updated = mutableListOf<BlockRule>()
        coEvery { dao.insert(capture(inserted)) } returns 1L
        coEvery { dao.update(capture(updated)) } returns Unit

        repo.saveAllAppsRule(loaded)
        repo.addRule(loaded)
        repo.saveAllAppsRule(loaded.copy(id = 9))
        repo.updateRule(loaded.copy(id = 9))

        assertEquals(listOf(expected, expected), inserted)
        assertEquals(listOf(expected.copy(id = 9), expected.copy(id = 9)), updated)
    }

    @Test
    fun `an app rule is written untouched`() = runTest {
        val row = slot<BlockRule>()
        coEvery { dao.insert(capture(row)) } returns 1L
        val appRule = BlockRule(packageName = "com.a", mode = "DELAY", keepPassOnShortTrips = true, dailyLimitMinutes = 30)
        repo.addRule(appRule)
        assertEquals(appRule, row.captured)
    }
}
