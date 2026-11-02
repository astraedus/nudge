package com.astraedus.nudge.testing

import com.astraedus.nudge.data.db.entity.BlockRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared fakes behave like the storage they stand in for. Each fake runs the real class, so
 * these pin only the in-memory boundary: ids, constraints, invalidation, batching.
 */
class FakesTest {

    // ---- NudgePreferences --------------------------------------------------------------------

    @Test
    fun `preferences start at the shipped defaults and keep what a setter wrote`() = runTest {
        val shipped = FakeNudgePreferences()
        val seeded = FakeNudgePreferences { setGlobalEnabled(false) }

        assertTrue(shipped.isGlobalEnabled.first())
        assertFalse(seeded.isGlobalEnabled.first())

        seeded.setGlobalEnabled(true)
        assertTrue(seeded.isGlobalEnabled.first())
    }

    @Test
    fun `two preference fakes never share state`() = runTest {
        val a = FakeNudgePreferences { setGlobalEnabled(false) }
        val b = FakeNudgePreferences()

        assertFalse(a.isGlobalEnabled.first())
        assertTrue(b.isGlobalEnabled.first())
    }

    @Test
    fun `an edit that changes nothing is not a write`() = runTest {
        val store = InMemoryPreferencesDataStore()
        store.updateData { it }

        assertEquals(0, store.writes)
    }

    // ---- InstalledAppsRepository -------------------------------------------------------------

    @Test
    fun `installed apps are listed by label and cached until refresh`() = runTest {
        val device = FakeInstalledApps(anApp("b.pkg", "beta"), anApp("a.pkg", "Alpha"))
        val repo = FakeInstalledAppsRepository(device)

        assertEquals(listOf("a.pkg", "b.pkg"), repo.getInstalledApps().map { it.packageName })

        device.install(anApp("c.pkg", "Charlie"))
        assertEquals(2, repo.getInstalledApps().size)
        assertEquals(1, device.launcherQueries)

        repo.refresh()
        assertEquals(3, repo.getInstalledApps().size)
        assertEquals(1L, repo.changes.value)
    }

    @Test
    fun `an unlabelled package resolves to its package name`() = runTest {
        val device = FakeInstalledApps(anApp("a.pkg", "Alpha")).apply { labelOnly("svc.pkg", "Service") }
        val repo = FakeInstalledAppsRepository(device)

        assertEquals("Alpha", repo.resolveAppName("a.pkg"))
        assertEquals("Service", repo.resolveAppName("svc.pkg"))
        assertEquals("gone.pkg", repo.resolveAppName("gone.pkg"))
        assertNull(repo.resolveIcon("gone.pkg"))
    }

    // ---- BlockRuleRepository / DAOs ----------------------------------------------------------

    @Test
    fun `rule ids autoincrement, are never reused, and a duplicate insert throws`() = runTest {
        val db = FakeRuleDatabase()
        val first = db.repository.addRule(aBlockRule(packageName = "a.pkg"))
        db.repository.deleteRule(first)
        val second = db.repository.addRule(aBlockRule(packageName = "b.pkg"))

        assertEquals(1L, first)
        assertEquals(2L, second)
        assertThrows(IllegalStateException::class.java) { db.seedRule(aBlockRule(id = 2L, packageName = "c.pkg")) }
    }

    @Test
    fun `rule queries re-emit after a write`() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeRuleDatabase()
        val seen = mutableListOf<List<BlockRule>>()
        val job = launch { db.repository.getEnabledRules().toList(seen) }

        db.repository.addRule(aBlockRule(packageName = "a.pkg"))
        db.repository.setEnabledForPackage("a.pkg", false)
        job.cancel()

        assertEquals(listOf(0, 1, 0), seen.map { it.size })
    }

    @Test
    fun `the group DAO's real transactions run on the shared tables`() = runTest {
        val db = FakeRuleDatabase()
        val groupId = db.repository.saveGroup(null, "Social", emptySet(), setOf("a.pkg", "b.pkg"), emptySet(), aBlockRule())
        val allApps = db.seedRule(aBlockRule(allApps = true, exceptGroupIds = "$groupId"))

        assertEquals(setOf("a.pkg", "b.pkg"), db.members.map { it.packageName }.toSet())
        assertEquals(groupId, db.rules.single { it.groupId != null }.groupId)
        assertEquals(listOf(groupId), db.repository.getRulesForPackage("a.pkg").first().map { it.groupId })

        db.repository.deleteGroupWithRules(groupId)

        assertTrue(db.groups.isEmpty())
        assertTrue(db.members.isEmpty())
        assertEquals(listOf(allApps.id), db.rules.map { it.id })
        assertNull(db.ruleWithId(allApps.id).exceptGroupIds)
    }

    @Test
    fun `a member of a missing group is a foreign key failure, and deleting a group cascades`() {
        val db = FakeRuleDatabase()
        assertThrows(IllegalStateException::class.java) { db.seedMember(aGroupMember(9L, "a.pkg")) }

        val group = db.seedGroup(anAppGroup("Social"), "a.pkg")
        db.seedMember(aGroupMember(group.id, "a.pkg", excluded = true))
        assertEquals(listOf(aGroupMember(group.id, "a.pkg", excluded = true)), db.members)
    }

    // ---- UsageRepository ---------------------------------------------------------------------

    @Test
    fun `usage events are stored, counted and batched as written`() = runTest {
        val dao = FakeUsageEventDao()
        val usage = FakeUsageRepository(events = dao)

        usage.insertEvents(listOf(aUsageEvent("a.pkg", wasBlocked = true), aUsageEvent("b.pkg", userChangedMind = true)))
        usage.logEvent(aUsageEvent("c.pkg", wasBlocked = true))

        assertEquals(listOf(1L, 2L, 3L), dao.events.map { it.id })
        assertEquals(1, dao.insertAllBatches.size)
        assertEquals(2, usage.getAllTimeShownCount().first())
        assertEquals(1, usage.getAllTimeChangedMindCount().first())
        assertEquals(3L, usage.observeLatestEventId().first())
    }

    @Test
    fun `a failing usage table writes nothing, and range queries are counted`() = runTest {
        val dao = FakeUsageEventDao()
        val usage = FakeUsageRepository(events = dao)
        dao.failWritesWith = IllegalStateException("db closed")

        val failure = runCatching { usage.logEvent(aUsageEvent("a.pkg")) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(dao.events.isEmpty())

        usage.getEventsSince(0L)
        usage.getEventsSince(0L)
        assertEquals(2, dao.eventsSinceQueries)
    }

    @Test
    fun `foreground time comes from the screen-time source and reads are counted`() {
        val screenTime = FakeScreenTime().apply { todayMs["a.pkg"] = 90_000L }
        val usage = FakeUsageRepository(screenTime = screenTime)

        assertEquals(90_000L, usage.getDailyForegroundTimeMs("a.pkg"))
        assertEquals(0L, usage.getDailyForegroundTimeMs("b.pkg"))
        assertEquals(2, screenTime.reads)
    }
}
