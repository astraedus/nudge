package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.lock.RuleWeakening
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.model.GroupMembership
import com.astraedus.nudge.domain.model.RuleTarget
import com.astraedus.nudge.domain.logging.NudgeLog
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * L1: rules only add (spec 1, "Precedence in one sentence"). Adding ANY rule, of every [RuleTarget]
 * kind, every mode, scheduled or not, with or without a daily limit, never turns a block into Allow
 * and never makes the deciding mode weaker. Run through the real [RuleEvaluator], [BlockEngine] and
 * [ScheduleEvaluator], for an ordinary app, an essential app and an app with no launcher entry.
 *
 * The catch this exists for: an All apps rule's allowed list read as "always allowed", lifting a
 * block another rule made.
 */
class RulesOnlyAddInvariantTest {

    private val pkg = "com.example.target"
    private val memberGroup = 7L
    private val otherGroup = 8L
    private val memberships = listOf(GroupMembership(memberGroup, pkg))

    // Wednesday 10:00, inside 09:00-17:00 and outside 20:00-22:00.
    private val now: Calendar = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 4, 10, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }
    private val engine = BlockEngine(ScheduleEvaluator(), NudgeLog.NoOp) { now.clone() as Calendar }
    private val evaluator = RuleEvaluator()

    /** One target per way a rule can relate to [pkg]. Every [RuleTarget] kind is here (pinned below). */
    private val targets: List<RuleTarget> = listOf(
        RuleTarget.App(pkg),
        RuleTarget.App("com.example.other"),
        RuleTarget.Group(memberGroup),
        RuleTarget.Group(otherGroup),
        RuleTarget.AllApps(emptySet(), emptySet()),
        RuleTarget.AllApps(setOf(pkg), emptySet()),
        RuleTarget.AllApps(emptySet(), setOf(memberGroup)),
        RuleTarget.AllApps(emptySet(), setOf(99L))
    )

    private data class Schedule(val days: List<Int>?, val start: Int?, val end: Int?)

    private val schedules = listOf(
        Schedule(null, null, null),
        Schedule(null, 9 * 60, 17 * 60),
        Schedule(null, 20 * 60, 22 * 60),
        Schedule(listOf(3), null, null)
    )

    private fun ruleOf(
        id: Long,
        target: RuleTarget,
        mode: BlockMode,
        schedule: Schedule,
        dailyLimit: Int?,
        features: List<String>?
    ): BlockRuleData {
        val base = BlockRuleData(
            id = id,
            packageName = null,
            groupId = null,
            mode = mode,
            delaySeconds = 15,
            dailyLimitMinutes = dailyLimit,
            enabled = true,
            scheduleDays = schedule.days,
            scheduleStartMinute = schedule.start,
            scheduleEndMinute = schedule.end,
            inAppFeatures = features
        )
        return when (target) {
            is RuleTarget.App -> base.copy(packageName = target.packageName)
            is RuleTarget.Group -> base.copy(groupId = target.groupId)
            is RuleTarget.AllApps -> base.copy(
                allApps = true,
                exceptPackages = target.exceptPackages,
                exceptGroupIds = target.exceptGroupIds
            )
        }
    }

    private val addedRules: List<BlockRuleData> = buildList {
        var id = 100L
        for (target in targets) for (mode in BlockMode.entries) for (schedule in schedules)
            for (limit in listOf(null, 30)) for (features in listOf(null, listOf("shorts"))) {
                add(ruleOf(id++, target, mode, schedule, limit, features))
            }
    }

    /** The rules already there: one per target and mode, all the time, no limit. */
    private val baseRuleSets: List<List<BlockRuleData>> = buildList {
        add(emptyList())
        var id = 1L
        for (target in targets) for (mode in BlockMode.entries) {
            add(listOf(ruleOf(id++, target, mode, schedules[0], null, null)))
        }
    }

    private val factsCases = listOf(
        CoverageFacts(essential = false, launchable = true),
        CoverageFacts(essential = true, launchable = true),
        CoverageFacts(essential = false, launchable = false)
    )

    private fun decide(rules: List<BlockRuleData>, facts: CoverageFacts, usageMs: Long, feature: String?) =
        engine.evaluate(
            pkg,
            evaluator.resolveRulesForPackage(pkg, rules, memberships, facts),
            usageMs,
            detectedFeature = feature
        )

    /** Allow is weakest; a block ranks by the one mode ladder Strict Mode uses. */
    private fun strength(decision: BlockDecision): Int = when (decision) {
        BlockDecision.Allow -> 0
        is BlockDecision.Block -> 1 + RuleWeakening.modeStrength(decision.mode.name)
    }

    @Test
    fun `the samples include every RuleTarget kind`() {
        assertEquals(
            RuleTarget::class.sealedSubclasses.toSet(),
            targets.map { it::class }.toSet()
        )
    }

    @Test
    fun `adding any rule never unblocks and never weakens the mode`() {
        var checked = 0
        var strengthened = 0
        for (base in baseRuleSets) for (added in addedRules) for (facts in factsCases)
            for (usage in listOf(0L, 40 * 60_000L)) for (feature in listOf(null, "shorts")) {
                val before = decide(base, facts, usage, feature)
                // Both positions: the snapshot's order is the table's, and a new rule must not
                // depend on landing last.
                for (after in listOf(base + added, listOf(added) + base)) {
                    val decided = decide(after, facts, usage, feature)
                    if (strength(decided) < strength(before)) {
                        fail("adding $added to $base for $facts usage=$usage feature=$feature: $before -> $decided")
                    }
                    if (strength(decided) > strength(before)) strengthened++
                    checked++
                }
            }
        // Not vacuous: the sweep did reach blocks that the added rule made stricter.
        if (strengthened == 0) fail("no case ever got stricter: the sweep tested nothing ($checked cases)")
    }
}
