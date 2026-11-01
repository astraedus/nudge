package com.astraedus.nudge.data.repository

import com.astraedus.nudge.data.db.dao.AppGroupDao
import com.astraedus.nudge.data.db.dao.BlockRuleDao
import com.astraedus.nudge.data.db.entity.BlockRule
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

    @Test
    fun `addRule and updateRule write exactly the well-formed targets and throw on the rest`() {
        for ((rule, wellFormed) in targets) {
            for (write in listOf<suspend (BlockRule) -> Unit>({ repo.addRule(it) }, { repo.updateRule(it) })) {
                if (wellFormed) {
                    runBlocking { write(rule) }
                } else {
                    assertThrows("$rule", IllegalArgumentException::class.java) { runBlocking { write(rule) } }
                }
            }
        }
        val wellFormedCount = targets.count { it.second }
        coVerify(exactly = wellFormedCount) { dao.insert(any()) }
        coVerify(exactly = wellFormedCount) { dao.update(any()) }
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
        exceptGroupIds = "3,4"
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
