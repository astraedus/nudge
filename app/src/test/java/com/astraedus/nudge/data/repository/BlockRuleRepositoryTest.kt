package com.astraedus.nudge.data.repository

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.model.RuleTarget
import com.astraedus.nudge.testing.FakeRuleDatabase
import com.astraedus.nudge.testing.aBlockRule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1. The one-target invariant (spec 5.1) on every [BlockRuleRepository] rule write, and the All
 * apps row shape (spec 3.4) on every write that can carry one.
 */
class BlockRuleRepositoryTest {

    private val db = FakeRuleDatabase()
    private val repo = db.repository

    /** Every combination of the three target columns, with whether exactly one is set. */
    private val targets: List<Pair<BlockRule, Boolean>> = run {
        val out = mutableListOf<Pair<BlockRule, Boolean>>()
        for (pkg in listOf(null, "com.a")) for (group in listOf(null, 4L)) for (all in listOf(false, true)) {
            val count = listOf(pkg != null, group != null, all).count { it }
            out += aBlockRule(id = 7, packageName = pkg, groupId = group, allApps = all) to (count == 1)
        }
        out
    }

    /**
     * The row a test expects for [rule], given what the repository actually stored: identical
     * except the arming stamp every enabled write takes (spec 5.3). Comparing everything but that
     * stamp is what these tests are about; the stamp has its own cases below.
     */
    private fun BlockRule.withArmingFrom(stored: BlockRule): BlockRule =
        if (enabled) copy(armedAt = stored.armedAt) else this

    /** The one malformed shape already on phones (old hand-edited imports): an app and a group. */
    private fun isLegacyAppAndGroup(rule: BlockRule) =
        rule.packageName != null && rule.groupId != null && !rule.allApps

    @Test
    fun `addRule and updateRule write exactly the well-formed targets and throw on the rest`() {
        for ((rule, wellFormed) in targets) {
            // addRule: a well-formed target is stored as given, anything else throws and stores nothing.
            val added = FakeRuleDatabase()
            if (wellFormed) runBlocking { added.repository.addRule(rule) }
            else assertThrows("$rule", IllegalArgumentException::class.java) {
                runBlocking { added.repository.addRule(rule) }
            }
            val expectedRows = if (wellFormed) {
                listOf(rule.withArmingFrom(added.rules.single()))
            } else {
                emptyList()
            }
            assertEquals("$rule", expectedRows, added.rules)

            // updateRule: over a row already stored (seeded raw, so a malformed row can exist).
            val stored = FakeRuleDatabase().also { it.seedRule(rule) }
            val edited = rule.copy(enabled = false)
            if (wellFormed || isLegacyAppAndGroup(rule)) {
                runBlocking { stored.repository.updateRule(edited) }
                val written = if (wellFormed) edited else edited.copy(groupId = null)
                assertEquals("$rule", listOf(written), stored.rules)
            } else {
                assertThrows("$rule", IllegalArgumentException::class.java) {
                    runBlocking { stored.repository.updateRule(edited) }
                }
                assertEquals("$rule", listOf(rule), stored.rules)
            }
        }
    }

    @Test
    fun `updating a legacy app-and-group row writes the app rule the engine reads, instead of crashing`() = runTest {
        // A switch flipping such a row used to reach a throwing write (a crash). The engine has always
        // read it as the app (RuleTarget.of: the package wins), so the repair changes no behaviour.
        val legacy = aBlockRule(id = 3, packageName = "com.a", groupId = 4L, enabled = false)
        db.seedRule(legacy)

        repo.updateRule(legacy)

        val row = db.ruleWithId(3)
        assertEquals(legacy.copy(groupId = null), row)
        assertEquals(
            RuleTarget.of(legacy.packageName, legacy.groupId, legacy.allApps, emptySet(), emptySet()),
            RuleTarget.of(row.packageName, row.groupId, row.allApps, emptySet(), emptySet())
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
    private val loaded = aBlockRule(
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

    private val expected = aBlockRule(
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
        // The two insert paths (id 0), each takes the next id.
        repo.saveAllAppsRule(loaded)
        repo.addRule(loaded)
        assertEquals(listOf(expected.copy(id = 1), expected.copy(id = 2)), db.rules)

        // Armed on write (spec 5.3): the row differs from the input by exactly that stamp.
        assertEquals(
            listOf(expected.copy(id = 1), expected.copy(id = 2))
                .zip(db.rules) { want, stored -> want.withArmingFrom(stored) },
            db.rules
        )
        assertTrue("an enabled write is armed now", db.rules.all { it.armedAt > 0 })

        // The two update paths (id 9), each over a stale stored row.
        val writes: List<suspend (BlockRuleRepository) -> Unit> = listOf(
            { it.saveAllAppsRule(loaded.copy(id = 9)) },
            { it.updateRule(loaded.copy(id = 9)) }
        )
        for (write in writes) {
            val stale = FakeRuleDatabase().also { it.seedRule(aBlockRule(id = 9, allApps = true)) }
            write(stale.repository)
            assertEquals(
                listOf(expected.copy(id = 9).withArmingFrom(stale.rules.single())),
                stale.rules
            )
            assertTrue("an enabled write is armed now", stale.rules.all { it.armedAt > 0 })
        }
    }

    @Test
    fun `an app rule is written untouched`() = runTest {
        val appRule = aBlockRule(packageName = "com.a", keepPassOnShortTrips = true, dailyLimitMinutes = 30)
        repo.addRule(appRule)
        val stored = db.rules.single()
        // Untouched apart from the arming stamp every enabled write gets.
        assertEquals(appRule.withArmingFrom(stored), stored)
        assertTrue(stored.armedAt > 0)
    }

    @Test
    fun `a disabled write keeps its old arming stamp`() = runTest {
        db.seedRule(aBlockRule(id = 3, packageName = "com.a", enabled = false, armedAt = 1_000L))

        repo.updateRule(aBlockRule(id = 3, packageName = "com.a", enabled = false, mode = "HARD_BLOCK"))

        // Nothing reads armedAt until the rule is enabled, and enabling stamps again. Re-arming a
        // disabled row would be harmless, but keeping it makes "save = re-arm" mean what it says.
        assertEquals(1_000L, db.ruleWithId(3).armedAt)
    }

    @Test
    fun `the Manage Apps switch re-arms only when it switches on`() = runTest {
        db.seedRule(aBlockRule(packageName = "com.a", enabled = false, armedAt = 1_000L))

        repo.setEnabledForPackage("com.a", true)

        assertTrue("an enable is armed now", db.rules.single().armedAt > 1_000L)

        val armedAt = db.rules.single().armedAt
        repo.setEnabledForPackage("com.a", false)
        assertEquals("a disable keeps the stamp it had", armedAt, db.rules.single().armedAt)
    }
}
