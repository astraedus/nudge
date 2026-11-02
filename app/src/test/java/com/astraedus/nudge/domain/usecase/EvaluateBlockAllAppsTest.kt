package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.data.repository.RuleSnapshot
import com.astraedus.nudge.data.repository.RuleSnapshotSource
import com.astraedus.nudge.data.repository.RuleSnapshots
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.nuke.NukeEnforcement
import com.astraedus.nudge.domain.nuke.NukePolicy
import com.astraedus.nudge.domain.nuke.NukeState
import com.astraedus.nudge.domain.packages.FakeEssentialPackages
import com.astraedus.nudge.domain.packages.FakePackageFacts
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeUsageRepository
import com.astraedus.nudge.testing.aBlockRule
import com.astraedus.nudge.testing.aGroupMember
import com.astraedus.nudge.testing.anAppGroup
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L3: All apps rules through the REAL [EvaluateBlockUseCase] (spec 4.2's precedence matrix, 5.7's
 * no-reads-once-warm, Nuke over an allowed list).
 *
 * The device facts are the counting fakes: launchable = [launchable], essential = [dialer].
 */
class EvaluateBlockAllAppsTest {

    private val youtube = "com.google.android.youtube"
    private val maps = "com.google.android.apps.maps"
    private val spotify = "com.spotify.music"
    private val dialer = "com.google.android.dialer"
    private val covered = "com.example.covered"
    private val social = 7L

    private val launchable = setOf(youtube, maps, spotify, dialer, covered)

    private val usageRepository = FakeUsageRepository()
    private val preferences = FakeNudgePreferences()

    private fun allApps(id: Long, mode: BlockMode = BlockMode.HARD_BLOCK, except: String? = null) =
        aBlockRule(id = id, allApps = true, exceptPackages = except, mode = mode.name)

    private class Device(launchable: Set<String>, essentials: Set<String>) {
        val essentialPackages = FakeEssentialPackages(essentials)
        val packageFacts = FakePackageFacts(launchable)
    }

    private fun useCase(
        snapshots: RuleSnapshots,
        device: Device = Device(launchable, setOf(dialer)),
        nuke: NukeEnforcement = NukeEnforcement.NEVER
    ) = EvaluateBlockUseCase(
        ruleSnapshots = snapshots,
        usageRepository = usageRepository,
        blockEngine = BlockEngine(ScheduleEvaluator()),
        ruleEvaluator = RuleEvaluator(),
        scheduleEvaluator = ScheduleEvaluator(),
        preferences = preferences,
        contentFilter = mockk<ContentFilter>(),
        nukeEnforcement = nuke,
        essentialPackages = device.essentialPackages,
        packageFacts = device.packageFacts
    )

    private fun fixed(
        rules: List<BlockRule>,
        groups: List<AppGroup> = emptyList(),
        members: List<AppGroupMember> = emptyList()
    ): RuleSnapshots {
        val snapshot = RuleSnapshot.of(enabledRules = rules, groups = groups, members = members)
        return object : RuleSnapshots {
            override suspend fun current() = snapshot
            override suspend fun enabledRules() = snapshot.enabledRules
        }
    }

    private fun block(decision: BlockDecision): BlockDecision.Block {
        assertTrue("expected a block, got $decision", decision is BlockDecision.Block)
        return decision as BlockDecision.Block
    }

    // --- Spec 4.2, one test per matrix row ---------------------------------------------------

    @Test
    fun `allowed by All apps but in a Hard group gives the group's Hard block`() = runTest {
        val useCase = useCase(
            fixed(
                rules = listOf(
                    allApps(1, BlockMode.DELAY, except = maps),
                    aBlockRule(id = 2, groupId = social, mode = BlockMode.HARD_BLOCK.name)
                ),
                groups = listOf(anAppGroup("Social", id = social)),
                members = listOf(aGroupMember(social, maps))
            )
        )
        val decision = block(useCase.invoke(maps))
        assertEquals(BlockMode.HARD_BLOCK, decision.mode)
        assertEquals("Social", decision.ruleName)
        assertEquals("the group decided, so no All apps line", null, decision.allApps)
    }

    @Test
    fun `allowed by All apps but with its own Shorts rule gives the Shorts rule on Shorts only`() = runTest {
        val useCase = useCase(
            fixed(
                listOf(
                    allApps(1, except = youtube),
                    aBlockRule(id = 2, packageName = youtube, mode = BlockMode.BREATHING.name, inAppFeatures = "SHORTS")
                )
            )
        )
        assertEquals("the app itself is allowed", BlockDecision.Allow, useCase.invoke(youtube))
        val shorts = block(useCase.invoke(youtube, detectedFeature = "SHORTS"))
        assertEquals(BlockMode.BREATHING, shorts.mode)
        assertEquals("SHORTS", shorts.feature)
    }

    @Test
    fun `two All apps rules with different allowed lists block when either covers`() = runTest {
        val useCase = useCase(
            fixed(
                listOf(
                    allApps(1, BlockMode.DELAY, except = "$maps,$spotify"),
                    allApps(2, BlockMode.HARD_BLOCK, except = maps)
                )
            )
        )
        assertEquals("allowed by both", BlockDecision.Allow, useCase.invoke(maps))
        assertEquals("allowed by one, covered by the other", BlockMode.HARD_BLOCK, block(useCase.invoke(spotify)).mode)
        assertEquals("covered by both: strictest wins", BlockMode.HARD_BLOCK, block(useCase.invoke(youtube)).mode)
    }

    @Test
    fun `an essential app under All apps is allowed`() = runTest {
        val useCase = useCase(fixed(listOf(allApps(1))))
        assertEquals(BlockDecision.Allow, useCase.invoke(dialer))
        assertEquals(BlockMode.HARD_BLOCK, block(useCase.invoke(youtube)).mode)
    }

    @Test
    fun `an essential app with its own app rule gets that rule`() = runTest {
        val useCase = useCase(
            fixed(listOf(allApps(1), aBlockRule(id = 2, packageName = dialer, mode = BlockMode.DELAY.name)))
        )
        val decision = block(useCase.invoke(dialer))
        assertEquals(BlockMode.DELAY, decision.mode)
        assertEquals(null, decision.allApps)
    }

    // --- Nuke --------------------------------------------------------------------------------

    /** Being on an allowed list is "this rule does not cover it", never "always allowed". */
    @Test
    fun `a nuked app that the All apps rule allows is still nuked`() = runTest {
        val state = NukeState(active = true, packages = setOf(maps), keyHash = "k")
        val useCase = useCase(
            fixed(listOf(allApps(1, except = maps))),
            nuke = NukeEnforcement { pkg -> NukePolicy.isNuked(pkg, state, emptySet()) }
        )
        assertEquals(BlockEngine.NUKE_DECISION, useCase.invoke(maps))
        assertEquals("not nuked: the rule's own answer", BlockMode.HARD_BLOCK, block(useCase.invoke(youtube)).mode)
    }

    // --- No reads once warm (spec 5.7) -------------------------------------------------------

    private class Table<T>(initial: T) {
        val rows = MutableStateFlow(initial)
        var opens = 0
            private set
        val flow: Flow<T> = flow {
            opens++
            emitAll(rows)
        }
    }

    private val rules = Table(
        listOf(
            allApps(1, BlockMode.DELAY, except = maps).copy(exceptGroupIds = "$social"),
            aBlockRule(id = 2, packageName = youtube, mode = BlockMode.HARD_BLOCK.name)
        )
    )
    private val groups = Table(listOf(anAppGroup("Music", id = social)))
    private val members = Table(listOf(aGroupMember(social, spotify)))
    private var perGroupReads = 0

    // Deliberately a stub, not FakeRuleDatabase: the no-reads-once-warm check counts how often each
    // table flow is opened, which the fake does not model.
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

    private fun TestScope.warmSource(): RuleSnapshotSource {
        val source = RuleSnapshotSource(repository, backgroundScope, NudgeLog.NoOp) { 0L }
        runCurrent()
        assertNotNull("warm after one pass of the scheduler", source.snapshot.value)
        return source
    }

    private fun totalOpens() = rules.opens + groups.opens + members.opens

    @Test
    fun `once warm, All apps evaluation and browser routing read no table`() = runTest {
        val useCase = useCase(warmSource())
        val opensWhenWarm = totalOpens()
        assertEquals("one open per table to warm", 3, opensWhenWarm)

        repeat(50) {
            assertEquals(BlockMode.HARD_BLOCK, block(useCase.invoke(youtube)).mode)
            assertEquals(BlockMode.DELAY, block(useCase.invoke(covered)).mode)
            assertEquals(BlockDecision.Allow, useCase.invoke(maps))
            assertEquals("a member of the allowed group", BlockDecision.Allow, useCase.invoke(spotify))
            assertEquals(BlockDecision.Allow, useCase.invoke(dialer))
            useCase.isCoveredWhole(youtube)
        }

        assertEquals("300 evaluations opened no table", opensWhenWarm, totalOpens())
        assertEquals("the per-group member query is never used once warm", 0, perGroupReads)
    }

    /** The lazy facts read (`RuleCoverage.factsNeeded`): app rules alone ask the device nothing. */
    @Test
    fun `a package with only its own app rule reads no device fact`() = runTest {
        val device = Device(launchable, setOf(dialer))
        val useCase = useCase(
            fixed(
                rules = listOf(
                    aBlockRule(id = 1, packageName = youtube, mode = BlockMode.HARD_BLOCK.name),
                    aBlockRule(id = 2, groupId = social, mode = BlockMode.HARD_BLOCK.name)
                ),
                groups = listOf(anAppGroup("Social", id = social)),
                members = listOf(aGroupMember(social, spotify))
            ),
            device
        )
        repeat(10) { assertEquals(BlockMode.HARD_BLOCK, block(useCase.invoke(youtube)).mode) }
        assertEquals(0, device.essentialPackages.reads)
        assertEquals(0, device.packageFacts.reads)

        // The counterfactual: a group member needs the essentials list, and only that.
        useCase.invoke(spotify)
        assertEquals(1, device.essentialPackages.reads)
        assertEquals(0, device.packageFacts.reads)
    }

    /** With an All apps rule on, every package needs both facts: launchable reaches the cache port. */
    @Test
    fun `an All apps rule reads both facts through the ports, once per evaluation`() = runTest {
        val device = Device(launchable, setOf(dialer))
        val useCase = useCase(fixed(listOf(allApps(1))), device)
        repeat(5) { useCase.invoke(youtube) }
        assertEquals(5, device.essentialPackages.reads)
        assertEquals(5, device.packageFacts.reads)
    }

    /** A newly installed app is covered on its first evaluation: launchable is asked, not remembered. */
    @Test
    fun `an app installed after the rule was made is covered on its first evaluation`() = runTest {
        val device = Device(launchable, setOf(dialer))
        val useCase = useCase(fixed(listOf(allApps(1))), device)
        val fresh = "com.example.justinstalled"
        assertEquals("not installed yet", BlockDecision.Allow, useCase.invoke(fresh))

        device.packageFacts.launchable = launchable + fresh
        assertEquals(BlockMode.HARD_BLOCK, block(useCase.invoke(fresh)).mode)
    }
}
