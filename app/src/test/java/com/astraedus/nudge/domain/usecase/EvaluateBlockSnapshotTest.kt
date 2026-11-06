package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.domain.engine.FirstUnlockState
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.data.repository.RepositoryRuleSnapshots
import com.astraedus.nudge.data.repository.RuleSnapshotSource
import com.astraedus.nudge.data.repository.RuleSnapshots
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.nuke.NukeEnforcement
import com.astraedus.nudge.domain.packages.FakeEssentialPackages
import com.astraedus.nudge.domain.packages.FakePackageFacts
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeUsageRepository
import com.astraedus.nudge.testing.aBlockRule
import com.astraedus.nudge.testing.aGroupMember
import com.astraedus.nudge.testing.anAppGroup
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L3: block evaluation through the production [RuleSnapshotSource] (spec section 5.7).
 *
 * The repository's three table flows are fakes that COUNT how often they are opened. A Room `Flow`
 * runs its query once per collection and again on each invalidation of its table, so "opened once,
 * re-emitting on change" is exactly "read the table once, then only when it changed". The existing
 * `EvaluateBlock*Test`s stay on the per-call reader, unchanged, as the proof the decisions did not
 * move; [snapshot and per-call reads decide alike] ties the two together.
 */
class EvaluateBlockSnapshotTest {

    private val appPkg = "com.example.ruled"
    private val groupPkg = "com.example.grouped"
    private val freePkg = "com.example.free"
    private val groupId = 7L

    private val appRule = aBlockRule(id = 1, packageName = appPkg, mode = BlockMode.HARD_BLOCK.name)
    private val groupRule = aBlockRule(
        id = 2,
        groupId = groupId,
        mode = BlockMode.DELAY.name,
        delaySeconds = 30,
        webDomains = "example.com"
    )

    /** One watched table: a Room query whose opens are counted. */
    private class Table<T>(initial: T) {
        val rows = MutableStateFlow(initial)
        var opens = 0
            private set
        val flow: Flow<T> = flow {
            opens++
            emitAll(rows)
        }
    }

    private val rules = Table(listOf(appRule, groupRule))
    private val groups = Table(listOf(anAppGroup("Social", id = groupId)))
    private val members = Table(listOf(aGroupMember(groupId, groupPkg)))
    private var perGroupReads = 0

    // Deliberately a stub, not FakeRuleDatabase: these checks are about the table FLOWS themselves
    // (how often each is opened, one that fails, one that never answers), which the fake does not model.
    private val repository: BlockRuleRepository = mockk {
        every { getEnabledRules() } answers { rules.flow }
        every { getAllGroups() } answers { groups.flow }
        every { getAllGroupMembers() } answers { members.flow }
        every { getGroupMembers(any()) } answers {
            perGroupReads++
            val id = firstArg<Long>()
            flowOf(members.rows.value.filter { it.groupId == id })
        }
    }

    private val usageRepository = FakeUsageRepository()

    private val preferences = FakeNudgePreferences()

    private fun useCase(snapshots: RuleSnapshots) = EvaluateBlockUseCase(
        ruleSnapshots = snapshots,
        usageRepository = usageRepository,
        blockEngine = BlockEngine(ScheduleEvaluator(FirstUnlockState.NONE)),
        ruleEvaluator = RuleEvaluator(),
        scheduleEvaluator = ScheduleEvaluator(FirstUnlockState.NONE),
        preferences = preferences,
        contentFilter = mockk<ContentFilter>(),
        nukeEnforcement = NukeEnforcement.NEVER,
        essentialPackages = FakeEssentialPackages(),
        packageFacts = FakePackageFacts()
    )

    private fun TestScope.warmSource(): RuleSnapshotSource {
        val source = RuleSnapshotSource(repository, backgroundScope, NudgeLog.NoOp) { 0L }
        runCurrent()
        assertNotNull("the snapshot should be warm after one pass of the scheduler", source.snapshot.value)
        return source
    }

    private fun totalOpens() = rules.opens + groups.opens + members.opens

    @Test
    fun `once warm, an evaluation reads no table`() = runTest {
        val useCase = useCase(warmSource())
        val opensWhenWarm = totalOpens()
        assertEquals("one open per table to warm", 3, opensWhenWarm)

        repeat(50) {
            assertMode(BlockMode.HARD_BLOCK, useCase.invoke(appPkg))
            assertMode(BlockMode.DELAY, useCase.invoke(groupPkg))
            assertEquals(BlockDecision.Allow, useCase.invoke(freePkg))
            useCase.isFollowingSteerEnabled(appPkg)
            assertMode(BlockMode.DELAY, useCase.evaluateWebDomain("https://example.com/feed").decision)
        }

        assertEquals("250 evaluations opened no table", opensWhenWarm, totalOpens())
        assertEquals("the per-group member query is never used once warm", 0, perGroupReads)
    }

    @Test
    fun `a rule edit reaches the next evaluation without a restart or a re-read of the other tables`() = runTest {
        val source = warmSource()
        val useCase = useCase(source)
        assertEquals(BlockDecision.Allow, useCase.invoke(freePkg))

        rules.rows.value = rules.rows.value + aBlockRule(id = 3, packageName = freePkg, mode = BlockMode.HARD_BLOCK.name)
        runCurrent()
        assertMode(BlockMode.HARD_BLOCK, useCase.invoke(freePkg))

        members.rows.value = emptyList()
        runCurrent()
        assertEquals("leaving the group lifts the group rule", BlockDecision.Allow, useCase.invoke(groupPkg))

        assertEquals("a change re-emits on the open query; nothing is reopened", 3, totalOpens())
    }

    @Test
    fun `before the first snapshot, evaluation reads the tables directly and still blocks`() = runTest {
        // The member table never answers, so the combined snapshot can never be built: the state an
        // evaluation sees in the first moments after the process starts.
        every { repository.getAllGroupMembers() } returns flow { awaitCancellation() }
        val source = RuleSnapshotSource(repository, backgroundScope, NudgeLog.NoOp) { 0L }
        runCurrent()
        assertNull(source.snapshot.value)

        val useCase = useCase(source)
        // An empty-snapshot design would ALLOW both of these. The cold path must not.
        assertMode(BlockMode.HARD_BLOCK, useCase.invoke(appPkg))
        assertMode(BlockMode.DELAY, useCase.invoke(groupPkg))
        assertMode(BlockMode.DELAY, useCase.evaluateWebDomain("example.com").decision)
        assertTrue("the cold path is the per-group read", perGroupReads > 0)
    }

    @Test
    fun `a table that fails to load is retried until the snapshot warms`() = runTest {
        var attempts = 0
        every { repository.getAllGroupMembers() } returns flow {
            if (attempts++ == 0) throw IOException("disk I/O error")
            emitAll(members.rows)
        }
        val source = RuleSnapshotSource(repository, backgroundScope, NudgeLog.NoOp) { 0L }
        runCurrent()

        assertNotNull("retried and warmed", source.snapshot.value)
        assertEquals(2, attempts)
        assertMode(BlockMode.DELAY, useCase(source).invoke(groupPkg))
    }

    @Test
    fun `snapshot and per-call reads decide alike`() = runTest {
        val cached = useCase(warmSource())
        val perCall = useCase(RepositoryRuleSnapshots(repository))

        rules.rows.value = listOf(
            appRule,
            groupRule,
            aBlockRule(id = 4, packageName = groupPkg, mode = BlockMode.BREATHING.name, inAppFeatures = "REELS"),
            aBlockRule(id = 5, packageName = freePkg, mode = "NOT_A_MODE"),
            aBlockRule(id = 6, groupId = 99L, mode = BlockMode.HARD_BLOCK.name) // a group that does not exist
        )
        runCurrent()

        for (pkg in listOf(appPkg, groupPkg, freePkg, "com.example.other")) {
            for (feature in listOf(null, "REELS", "SHORTS")) {
                assertEquals("$pkg / $feature", perCall.invoke(pkg, feature), cached.invoke(pkg, feature))
            }
            assertEquals(pkg, perCall.isFollowingSteerEnabled(pkg), cached.isFollowingSteerEnabled(pkg))
        }
        for (url in listOf("example.com", "https://example.com/x", "other.org")) {
            assertEquals(url, perCall.evaluateWebDomain(url), cached.evaluateWebDomain(url))
        }
    }

    private fun assertMode(expected: BlockMode, decision: BlockDecision) {
        assertTrue("expected a $expected block, got $decision", decision is BlockDecision.Block)
        assertEquals(expected, (decision as BlockDecision.Block).mode)
    }
}
