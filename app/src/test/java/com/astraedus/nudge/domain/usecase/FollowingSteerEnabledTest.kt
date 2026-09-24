package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.model.BlockMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [EvaluateBlockUseCase.isFollowingSteerEnabled] — "does any rule that applies to this app right now
 * ask for the Following steer".
 *
 * ## Why this is not a `BlockDecision` field
 *
 * Every other per-rule capability reaches the service through [com.astraedus.nudge.domain.model.BlockDecision.Block].
 * The steer cannot: it runs while the app is being ALLOWED, and `Allow` carries no data at all.
 * Answering it through the engine would have meant inventing a decision meaning "not blocked, but do
 * something anyway" — the exact gap [BlockMode.NONE]'s grayscale flag already falls into, where a
 * flag is stored, read, and then inert because no decision carries it.
 *
 * So it is its own query, and what these tests pin is that it is a query about NOW: it resolves the
 * same rules the engine would and honours the same schedule, rather than being a bare
 * `SELECT followingSteer` that a user would experience as a toggle ignoring its own rule's window.
 */
class FollowingSteerEnabledTest {

    private val pkg = "com.instagram.android"

    private lateinit var blockRuleRepository: BlockRuleRepository
    private lateinit var useCase: EvaluateBlockUseCase

    @Before
    fun setUp() {
        blockRuleRepository = mockk()
        every { blockRuleRepository.getAllGroups() } returns flowOf(emptyList())

        useCase = EvaluateBlockUseCase(
            blockRuleRepository = blockRuleRepository,
            usageRepository = mockk<UsageRepository>(),
            blockEngine = BlockEngine(ScheduleEvaluator()),
            ruleEvaluator = RuleEvaluator(),
            scheduleEvaluator = ScheduleEvaluator(),
            preferences = mockk<NudgePreferences>(),
            contentFilter = mockk<ContentFilter>()
        )
    }

    private fun rule(
        followingSteer: Boolean,
        enabled: Boolean = true,
        packageName: String? = pkg,
        scheduleDays: String? = null,
        scheduleStartMinute: Int? = null,
        scheduleEndMinute: Int? = null
    ) = BlockRule(
        id = 1L,
        packageName = packageName,
        mode = BlockMode.NONE.name,
        enabled = enabled,
        followingSteer = followingSteer,
        scheduleDays = scheduleDays,
        scheduleStartMinute = scheduleStartMinute,
        scheduleEndMinute = scheduleEndMinute
    )

    private fun givenRules(vararg rules: BlockRule) {
        every { blockRuleRepository.getEnabledRules() } returns flowOf(rules.toList())
    }

    @Test
    fun `a rule opting in enables the steer`() = runTest {
        givenRules(rule(followingSteer = true))

        assertTrue(useCase.isFollowingSteerEnabled(pkg))
    }

    @Test
    fun `no rule opting in leaves the steer off`() = runTest {
        givenRules(rule(followingSteer = false))

        assertFalse(useCase.isFollowingSteerEnabled(pkg))
    }

    @Test
    fun `no rules at all leaves the steer off`() = runTest {
        givenRules()

        assertFalse(useCase.isFollowingSteerEnabled(pkg))
    }

    /**
     * The steer is per-rule, so it must not leak across apps. Without the package resolution this
     * would be a global switch that one Instagram rule turned on for YouTube as well.
     */
    @Test
    fun `a rule for another app does not enable the steer here`() = runTest {
        givenRules(rule(followingSteer = true, packageName = "com.google.android.youtube"))

        assertFalse(useCase.isFollowingSteerEnabled(pkg))
    }

    @Test
    fun `a disabled rule does not enable the steer`() = runTest {
        givenRules(rule(followingSteer = true, enabled = false))

        assertFalse(useCase.isFollowingSteerEnabled(pkg))
    }

    /**
     * THE schedule case, and the reason this goes through [ScheduleEvaluator] rather than reading the
     * column. A window of one minute that already closed is chosen over a hardcoded clock so the test
     * needs no time injection: whatever minute it runs in, `[00:00, 00:01)` is in the past unless the
     * suite happens to run in the first minute of the day — so the window is derived from "now" and
     * placed deliberately behind it.
     */
    @Test
    fun `a rule outside its schedule does not enable the steer`() = runTest {
        val now = java.util.Calendar.getInstance()
        val nowMinute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        // A closed 30-minute window ending well before now, wrapped into the day if we are early.
        val end = (nowMinute - 30).let { if (it < 60) nowMinute + 120 else it }
        val start = end - 30

        givenRules(
            rule(
                followingSteer = true,
                scheduleStartMinute = start,
                scheduleEndMinute = end
            )
        )

        assertFalse(
            "a rule whose window is [$start,$end) must not steer at minute $nowMinute",
            useCase.isFollowingSteerEnabled(pkg)
        )
    }

    @Test
    fun `a rule inside its schedule enables the steer`() = runTest {
        val now = java.util.Calendar.getInstance()
        val nowMinute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)

        givenRules(
            rule(
                followingSteer = true,
                scheduleStartMinute = (nowMinute - 1).coerceAtLeast(0),
                scheduleEndMinute = (nowMinute + 2).coerceAtMost(24 * 60)
            )
        )

        assertTrue(useCase.isFollowingSteerEnabled(pkg))
    }

    /**
     * One opting-in rule is enough even when another applicable rule does not. The alternative
     * reading — every rule must agree — would make the toggle un-turn-on-able for anyone who also has
     * a scheduled override, since the editor writes the flag on the app-level rule only.
     */
    @Test
    fun `one opting-in rule is enough among several`() = runTest {
        givenRules(
            rule(followingSteer = false),
            rule(followingSteer = true).copy(id = 2L)
        )

        assertTrue(useCase.isFollowingSteerEnabled(pkg))
    }
}
