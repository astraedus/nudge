package com.astraedus.nudge.service

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.data.repository.RuleSnapshot
import com.astraedus.nudge.data.repository.RuleSnapshots
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.CoveredApps
import com.astraedus.nudge.domain.engine.FirstUnlockState
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.groups.AppCategoryCsv
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.ExceptCsv
import com.astraedus.nudge.domain.nuke.NukeEnforcement
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.domain.packages.FakeEssentialPackages
import com.astraedus.nudge.domain.packages.FakePackageFacts
import com.astraedus.nudge.domain.usecase.EvaluateBlockUseCase
import com.astraedus.nudge.testing.FIXED_EPOCH_MS
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeScreenTime
import com.astraedus.nudge.testing.FakeUsageRepository
import com.astraedus.nudge.testing.aBlockRule
import com.astraedus.nudge.testing.aGroupMember
import com.astraedus.nudge.testing.anAppGroup
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * L3 drift guard for [CounterCacheCoverage] (issue [#146](https://github.com/astraedus/nudge/issues/146)):
 * the counter cache must cover EXACTLY the apps block evaluation covers, so the clock runs for every
 * app whose evaluation would block on a spent budget and for no other.
 *
 * Both sides read one mixed fixture through their own production entry points: the cache through
 * [CounterCacheCoverage.load] (the service's loader), evaluation through the real
 * [EvaluateBlockUseCase] with the same device facts behind [FakePackageFacts] /
 * [FakeEssentialPackages]. If someone later gives the cache its own idea of group membership
 * (a second resolver, a forgotten "Removed by you", essentials under a group), a package here drifts
 * and the test names it.
 */
class CounterCacheCoverageDriftTest {

    private val instagram = "com.instagram.android"
    private val tiktok = "com.zhiliaoapp.musically"
    private val reddit = "com.reddit.frontpage"
    private val youtube = "com.google.android.youtube"
    private val maps = "com.google.android.apps.maps"
    private val chrome = "com.android.chrome"
    private val dialer = "com.google.android.dialer"
    private val notLaunchable = "com.example.background.only"

    private val social = 1L
    private val video = 2L
    private val allowed = 3L
    private val noClock = 4L

    private val launchable = mapOf(
        instagram to AppCategory.SOCIAL,
        tiktok to AppCategory.SOCIAL,
        reddit to AppCategory.SOCIAL,
        youtube to AppCategory.VIDEO,
        maps to AppCategory.MAPS,
        chrome to AppCategory.SOCIAL,
        dialer to AppCategory.UNDEFINED
    )
    private val essentials = setOf(dialer)
    private val browsers = setOf(chrome)
    private val universe = launchable.keys + notLaunchable

    /** Every kind of coverage at once: hand-picked, category, Removed by you, All apps, a disabled row, a direct rule, overlaps. */
    private fun mixedRules(): List<BlockRule> = listOf(
        // Category group: SOCIAL apps, reddit Removed by you, a limit.
        aBlockRule(id = 1, groupId = social, mode = BlockMode.HARD_BLOCK.name, dailyLimitMinutes = 30),
        // Hand-picked group with a stricter limit, overlapping tiktok, plus an essential and a non-launchable member.
        aBlockRule(id = 2, groupId = video, mode = BlockMode.DELAY.name, dailyLimitMinutes = 15),
        // A direct rule, looser than the category group, on a member.
        aBlockRule(id = 3, packageName = instagram, dailyLimitMinutes = 45),
        // All apps with a limit, allowing maps and the "allowed" group.
        aBlockRule(
            id = 4,
            allApps = true,
            mode = BlockMode.DELAY.name,
            dailyLimitMinutes = 120,
            exceptPackages = ExceptCsv.ofPackages(listOf(maps)),
            exceptGroupIds = ExceptCsv.ofGroupIds(listOf(allowed))
        ),
        // A disabled group limit: must count for neither side.
        aBlockRule(id = 5, groupId = allowed, dailyLimitMinutes = 1, enabled = false),
        // A group rule with no clock feature: no entry, and no limit for evaluation either.
        aBlockRule(id = 6, groupId = noClock, mode = BlockMode.DELAY.name)
    )

    private fun snapshotOf(rules: List<BlockRule>) = RuleSnapshot.of(
        enabledRules = rules,
        groups = listOf(
            anAppGroup("Social", id = social, categories = AppCategoryCsv.format(setOf(AppCategory.SOCIAL))),
            anAppGroup("Video", id = video),
            anAppGroup("Allowed", id = allowed),
            anAppGroup("Quiet", id = noClock)
        ),
        members = listOf(
            aGroupMember(social, reddit, excluded = true),
            aGroupMember(video, youtube),
            aGroupMember(video, tiktok),
            aGroupMember(video, dialer),
            aGroupMember(video, notLaunchable),
            aGroupMember(allowed, youtube),
            aGroupMember(noClock, maps)
        )
    )

    private suspend fun cacheFor(snapshot: RuleSnapshot) = CounterCacheCoverage.load(
        snapshot = snapshot,
        readLaunchable = { launchable },
        readEssentials = { essentials },
        readBrowsers = { browsers }
    )

    private fun evaluatorFor(snapshot: RuleSnapshot): EvaluateBlockUseCase {
        // Every app far past every limit, so a limit that applies always decides the block.
        val screenTime = FakeScreenTime().apply { universe.forEach { todayMs[it] = 24L * 60 * 60_000L } }
        return EvaluateBlockUseCase(
            ruleSnapshots = object : RuleSnapshots {
                override suspend fun current() = snapshot
                override suspend fun enabledRules() = snapshot.enabledRules
            },
            usageRepository = FakeUsageRepository(screenTime = screenTime),
            blockEngine = BlockEngine(ScheduleEvaluator(FirstUnlockState.NONE), NudgeLog.NoOp) {
                Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = FIXED_EPOCH_MS }
            },
            ruleEvaluator = RuleEvaluator(),
            scheduleEvaluator = ScheduleEvaluator(FirstUnlockState.NONE),
            preferences = FakeNudgePreferences(),
            contentFilter = mockk<ContentFilter>(),
            nukeEnforcement = NukeEnforcement.NEVER,
            essentialPackages = FakeEssentialPackages(essentials).apply { this.browsers = this@CounterCacheCoverageDriftTest.browsers },
            packageFacts = FakePackageFacts(launchable = launchable.keys, categories = launchable)
        )
    }

    @Test
    fun `the cache's daily limit for every app is exactly the limit evaluation blocks on`() = runTest {
        val snapshot = snapshotOf(mixedRules())
        val cache = cacheFor(snapshot)
        val evaluate = evaluatorFor(snapshot)

        val cacheSays = universe.associateWith { cache[it]?.dailyLimitMinutes }
        val engineSays = universe.associateWith { pkg ->
            (evaluate.invoke(pkg) as? BlockDecision.Block)?.dailyLimitMinutes
        }

        assertEquals(engineSays, cacheSays)
        // And the fixture really exercises the cases (a guard against a vacuous pass): strictest of
        // overlapping groups, a direct rule losing to a group, All apps alone, and the exclusions.
        assertEquals("two groups and All apps: the lowest", 15, cacheSays[tiktok])
        assertEquals("a category group beats a looser direct rule", 30, cacheSays[instagram])
        assertEquals("a browser joins no category group, All apps covers it", 120, cacheSays[chrome])
        assertEquals("Removed by you from the category group, All apps still covers it", 120, cacheSays[reddit])
        assertEquals("in All apps' allowed group: only its own group's limit", 15, cacheSays[youtube])
        assertEquals("allowed by All apps, its group has no limit", null, cacheSays[maps])
        assertEquals("essential: never under a group or All apps", null, cacheSays[dialer])
        assertEquals("a hand-added member needs no launcher entry", 15, cacheSays[notLaunchable])
    }

    @Test
    fun `the cache's app keys are exactly the apps covered by a rule that wants an entry`() = runTest {
        val rules = mixedRules()
        val cache = cacheFor(snapshotOf(rules))

        val wanting = rules.filter { CounterCacheRefresher.entryFor(it) != null }
        val wantingSnapshot = snapshotOf(wanting)
        val covered = CoveredApps.packages(
            ruleData = wantingSnapshot.ruleData,
            membership = wantingSnapshot.membership,
            launchable = launchable,
            essentials = essentials,
            browsers = browsers
        )

        assertEquals(covered, cache.keys)
    }
}
