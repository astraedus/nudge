package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.data.repository.RepositoryRuleSnapshots
import com.astraedus.nudge.data.repository.RuleSnapshots
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.ContentFilterAllowlist
import com.astraedus.nudge.domain.ContentFilterBlocklist
import com.astraedus.nudge.domain.WebDomainMatcher
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.CoverageFacts
import com.astraedus.nudge.domain.engine.RuleCoverage
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.engine.WholeAppCoverage
import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.model.GroupMembership
import com.astraedus.nudge.domain.model.WebBlockMode
import com.astraedus.nudge.domain.nuke.NukeEnforcement
import com.astraedus.nudge.domain.packages.EssentialPackages
import com.astraedus.nudge.domain.packages.PackageFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject

class EvaluateBlockUseCase @Inject constructor(
    /**
     * The rule tables. Production injects the `@Singleton` `RuleSnapshotSource`, so once it is warm
     * an evaluation reads no database at all (spec 5.7); see that class for the cold start.
     */
    private val ruleSnapshots: RuleSnapshots,
    private val usageRepository: UsageRepository,
    private val blockEngine: BlockEngine,
    private val ruleEvaluator: RuleEvaluator,
    private val scheduleEvaluator: ScheduleEvaluator,
    private val preferences: NudgePreferences,
    private val contentFilter: ContentFilter,
    /**
     * Nuke Mode. No default, on purpose: every construction site has to say what Nuke is, because a
     * defaulted "never nuked" here is the silent no-op that would let every nuked app open.
     */
    private val nukeEnforcement: NukeEnforcement,
    /**
     * The one essentials list (spec 5.4): what group and All apps rules may never cover. No default:
     * a defaulted empty list would let an All apps rule block the phone app.
     */
    private val essentialPackages: EssentialPackages,
    /** Launchable or not, per package: what an All apps rule covers (spec 4.1). Cached per package. */
    private val packageFacts: PackageFacts
) {

    /**
     * A use case that reads [blockRuleRepository] on every call, exactly as evaluation did before
     * the snapshot. For hand-built instances (the use-case tests); it is not a no-op, only uncached:
     * same rows, same mapping, same decisions, one query per table per evaluation.
     */
    constructor(
        blockRuleRepository: BlockRuleRepository,
        usageRepository: UsageRepository,
        blockEngine: BlockEngine,
        ruleEvaluator: RuleEvaluator,
        scheduleEvaluator: ScheduleEvaluator,
        preferences: NudgePreferences,
        contentFilter: ContentFilter,
        nukeEnforcement: NukeEnforcement,
        essentialPackages: EssentialPackages,
        packageFacts: PackageFacts
    ) : this(
        ruleSnapshots = RepositoryRuleSnapshots(blockRuleRepository),
        usageRepository = usageRepository,
        blockEngine = blockEngine,
        ruleEvaluator = ruleEvaluator,
        scheduleEvaluator = scheduleEvaluator,
        preferences = preferences,
        contentFilter = contentFilter,
        nukeEnforcement = nukeEnforcement,
        essentialPackages = essentialPackages,
        packageFacts = packageFacts
    )

    /**
     * Evaluate whether a package should be blocked right now.
     * Combines rule resolution, daily usage lookup, and the block engine decision.
     *
     * @param detectedFeature If the accessibility service detected an in-app feature
     *   (e.g. "REELS", "SHORTS"), pass it here so the engine can match feature-level rules.
     * @param includeWholeAppRulesForFeature Whether feature evaluation should also consider
     *   whole-app rules. This is disabled after a whole-app delay has completed so in-app rules
     *   can still fire without looping the whole-app gate.
     * @param excludeDirectRules Resolve GROUP rules only, dropping rules that name [packageName]
     *   directly. The service passes true when it evaluates a browser covered by a group as an app
     *   (spec 5.5): a direct rule on a browser keeps its web-only meaning (spec follow-up F5), so it
     *   must not take part in the browser's app-level decision.
     */
    suspend fun invoke(
        packageName: String,
        detectedFeature: String? = null,
        includeWholeAppRulesForFeature: Boolean = true,
        excludeDirectRules: Boolean = false
    ): BlockDecision {
        // NUKE FIRST, and before any rule or usage read: a nuked app's rules are irrelevant, so there
        // is no reason to pay for them. Every app-level caller comes through here (the foreground
        // event, the content-change fallback, in-app feature detection, the Reels tab cover, the
        // daily-limit clock), so there is exactly one place Nuke joins evaluation.
        if (nukeEnforcement.isNuked(packageName)) {
            return blockEngine.evaluate(
                packageName = packageName,
                activeRules = emptyList(),
                dailyUsageMs = 0L,
                nuked = true
            )
        }

        val activeRules = resolveActiveRules(packageName, excludeDirectRules)
        val dailyUsageMs = dailyUsageMs(packageName, activeRules)

        return blockEngine.evaluate(
            packageName = packageName,
            activeRules = activeRules,
            dailyUsageMs = dailyUsageMs,
            detectedFeature = detectedFeature,
            includeWholeAppRulesForFeature = includeWholeAppRulesForFeature
        )
    }

    /**
     * Is [packageName] covered right now by a whole-app GROUP or ALL APPS rule ([WholeAppCoverage],
     * spec 5.5)?
     * The question that decides whether a BROWSER is evaluated as an app before its websites.
     *
     * Reads [ruleSnapshots] like every evaluation does, so it fails CLOSED exactly as they do: before
     * the in-memory snapshot's first emission (or while its collector is failing) `current()` reads
     * the tables directly. Answering from `RuleSnapshotSource.snapshot.value` instead would say "not
     * covered" for that window, and Chrome in a hard-blocked group would open freely in the first
     * moments after the process starts, while a cold Instagram in the same group is blocked.
     */
    suspend fun isCoveredWhole(packageName: String, now: Calendar = Calendar.getInstance()): Boolean {
        val snapshot = ruleSnapshots.current()
        val facts = coverageFacts(packageName, snapshot.ruleData, snapshot.memberships)
        return WholeAppCoverage.coversNow(
            packageName = packageName,
            rules = snapshot.ruleData,
            memberships = snapshot.memberships,
            facts = facts
        ) { rule -> scheduleEvaluator.isActiveAt(rule, now) }
    }

    /**
     * Every rule that applies to [packageName] right now, as [ActiveRule]s, schedules NOT yet
     * applied (the engine applies those).
     *
     * Shared by [invoke] and [isFollowingSteerEnabled]. The entity-to-domain mapping it used to do
     * inline now happens once per table change, in `RuleSnapshot.of` (`BlockRule.toRuleData`), so
     * this is in-memory filtering over a handful of rules.
     */
    private suspend fun resolveActiveRules(
        packageName: String,
        excludeDirectRules: Boolean = false
    ): List<ActiveRule> {
        val snapshot = ruleSnapshots.current()
        val rules = if (excludeDirectRules) {
            snapshot.ruleData.filter { it.packageName == null }
        } else {
            snapshot.ruleData
        }
        val facts = coverageFacts(packageName, rules, snapshot.memberships)
        return ruleEvaluator.resolveRulesForPackage(packageName, rules, snapshot.memberships, facts)
    }

    /**
     * The device facts coverage of [packageName] needs, read only when a rule can consult them
     * ([RuleCoverage.factsNeeded]): a package with only its own rules reads neither. Both reads are
     * in-memory caches once warm (the essentials list for 5 minutes, launchability per package until
     * an install broadcast), so this adds no database or binder call per event.
     *
     * A fact that is not needed is reported as false; no rule in [rules] reads it.
     */
    private suspend fun coverageFacts(
        packageName: String,
        rules: List<BlockRuleData>,
        memberships: List<GroupMembership>
    ): CoverageFacts {
        val needed = RuleCoverage.factsNeeded(rules, RuleCoverage.groupIdsOf(packageName, memberships))
        return CoverageFacts(
            essential = needed.essential && packageName in essentialPackages.current(),
            launchable = needed.launchable && packageFacts.factsFor(packageName).launchable
        )
    }

    /**
     * Whether any rule that applies to [packageName] RIGHT NOW opts into the Following steer.
     *
     * Not a [BlockDecision] field, and deliberately so: the steer runs while the app is being
     * ALLOWED, and `BlockDecision.Allow` carries nothing. Answering it through the engine would
     * have meant inventing a decision for "not blocked, but do something anyway", which is the
     * shape [BlockMode.NONE]'s grayscale flag already fails at.
     *
     * The schedule IS honoured, so a rule scoped to 9-5 does not steer at 9pm: a per-rule toggle
     * that ignored its own rule's schedule would be a different feature wearing the same switch.
     */
    suspend fun isFollowingSteerEnabled(packageName: String): Boolean =
        resolveActiveRules(packageName).any {
            it.enabled && it.followingSteer && scheduleEvaluator.isActiveNow(it)
        }

    /**
     * Evaluate whether a detected web domain should be blocked.
     * Checks all enabled rules that have webDomains configured and matches
     * the detected URL against them.
     *
     * Each matching rule enforces at its OWN web mode ([WebBlockMode.resolve]) rather than the
     * app-level mode (issue #21) — that is what lets a rule leave the app open while still
     * blocking the site. A rule whose web mode resolves to [BlockMode.NONE] enforces nothing and
     * is dropped here, so a URL covered only by such rules still falls through to the generic
     * content filter instead of being treated as "handled, allowed".
     *
     * @param urlBarText The text from the browser's URL bar
     * @return BlockDecision and the matching rule's associated packageName (for usage tracking)
     */
    suspend fun evaluateWebDomain(urlBarText: String): WebDomainBlockResult {
        val allRules = ruleSnapshots.enabledRules()

        // Find rules with webDomains that match the detected URL AND actually enforce something.
        val matchingRules = allRules.mapNotNull { rule ->
            if (rule.webDomains == null || !WebDomainMatcher.matches(urlBarText, rule.webDomains)) {
                return@mapNotNull null
            }
            val webMode = WebBlockMode.resolve(rule.mode, rule.webBlockMode)
            if (webMode == BlockMode.NONE) null else rule to webMode
        }

        if (matchingRules.isEmpty()) {
            // No enforcing per-rule web domain match. Fall through to the generic
            // content filter (bundled blocklist + keywords) if it is enabled.
            return evaluateContentFilter(urlBarText)
        }

        // Convert matching rules to ActiveRules for BlockEngine evaluation
        val activeRules = matchingRules.map { (rule, webMode) ->
            ActiveRule(
                mode = webMode,
                delaySeconds = rule.delaySeconds,
                dailyLimitMinutes = rule.dailyLimitMinutes,
                enabled = rule.enabled,
                scheduleDays = rule.scheduleDays?.split(",")?.mapNotNull { it.trim().toIntOrNull() },
                scheduleStartMinute = rule.scheduleStartMinute,
                scheduleEndMinute = rule.scheduleEndMinute,
                inAppFeatures = null, // Web domain rules apply as whole-app rules
                grayscale = rule.grayscale,
                ruleName = buildWebDomainRuleName(rule.packageName, webMode)
            )
        }

        // Use the first matching rule's package for usage stats lookup
        val trackingPackage = matchingRules.first().first.packageName ?: "web"
        val dailyUsageMs = dailyUsageMs(trackingPackage, activeRules)

        val decision = blockEngine.evaluate(
            packageName = trackingPackage,
            activeRules = activeRules,
            dailyUsageMs = dailyUsageMs
        )

        return WebDomainBlockResult(decision, trackingPackage)
    }

    /**
     * Generic content-filter evaluation: when enabled, blocks [urlBarText] if it
     * matches the bundled blocklist or a high-signal keyword, using the
     * user-configured content-filter mode. The rule name surfaced on the overlay
     * is intentionally generic ("Restricted content").
     */
    private suspend fun evaluateContentFilter(urlBarText: String): WebDomainBlockResult {
        if (!preferences.contentFilterEnabled.first()) {
            return WebDomainBlockResult(BlockDecision.Allow, null)
        }
        val strict = preferences.contentFilterStrictKeywords.first()
        // The user's own blocks are live whenever the filter is on, independent of the strict
        // toggle. Their exceptions are needed for the strict layer and for those blocks; with both
        // absent there is nothing to read.
        val userBlocklist = ContentFilterBlocklist(preferences.contentFilterBlocklist.first())
        val allowlist = if (strict || !userBlocklist.isEmpty) {
            ContentFilterAllowlist(preferences.contentFilterAllowlist.first())
        } else {
            ContentFilterAllowlist.EMPTY
        }
        if (!contentFilter.isBlocked(urlBarText, strict, allowlist, userBlocklist)) {
            return WebDomainBlockResult(BlockDecision.Allow, null)
        }

        val mode = try {
            BlockMode.valueOf(preferences.contentFilterMode.first())
        } catch (_: Exception) {
            BlockMode.HARD_BLOCK
        }

        // Track usage under a synthetic "web" package, consistent with how
        // web-domain rules without an associated app are tracked.
        val trackingPackage = "web"

        val activeRule = ActiveRule(
            mode = mode,
            delaySeconds = CONTENT_FILTER_DELAY_SECONDS,
            dailyLimitMinutes = null,
            enabled = true,
            inAppFeatures = null,
            grayscale = false,
            ruleName = "Restricted content"
        )

        val decision = blockEngine.evaluate(
            packageName = trackingPackage,
            activeRules = listOf(activeRule),
            dailyUsageMs = dailyUsageMs(trackingPackage, listOf(activeRule))
        )

        return WebDomainBlockResult(decision, trackingPackage)
    }

    /**
     * Today's foreground time for [packageName], the number the daily budget is spent against.
     *
     * This MUST come from `UsageStatsManager` (issue #14). The obvious-looking alternative — the
     * Room `usage_events` table — cannot work: that table logs block/allow *decisions* and holds no
     * foreground duration at all. It once carried a `durationMs` column that was never written, so
     * summing it returned 0 for every package forever (column deleted in issue #22).
     * Feeding that 0 to [BlockEngine] made `dailyUsageMs >= limit` permanently false, so the
     * daily-limit HARD_BLOCK never fired and the "X left today" line on the overlay was pinned at
     * the full limit. `TimeRemainingHandler` already reads the correct source, which is why the
     * limit appeared to work — but only for rules that had opted into the time-remaining overlay.
     *
     * The read is a synchronous binder call, hence [Dispatchers.IO]; it returns 0 without throwing
     * when Usage Access has not been granted, which fails toward *allowing* the app. That is the
     * right direction: a permission the user has not granted must not manufacture a block.
     *
     * Skipped entirely when no rule in [rules] carries a daily limit, because then [BlockEngine]
     * cannot consult the number. That matters: unlike the local Room `SUM()` this replaced,
     * `queryEvents` returns the whole device's event log for the day and is filtered in Kotlin, and
     * this runs on the accessibility hot path (debounced to ~1/s per package, re-entered on every
     * content change while a feed is being scrolled). Paying for it on a 3GB Pixel 3 when the
     * result is provably discarded is the kind of cost that shows up as jank.
     */
    private suspend fun dailyUsageMs(packageName: String, rules: List<ActiveRule>): Long {
        if (rules.none { it.dailyLimitMinutes != null }) return 0L
        return withContext(Dispatchers.IO) { usageRepository.getDailyForegroundTimeMs(packageName) }
    }

    private fun buildWebDomainRuleName(packageName: String?, mode: BlockMode): String {
        val modeName = when (mode) {
            BlockMode.HARD_BLOCK -> "Hard Block"
            BlockMode.DELAY -> "Delay"
            BlockMode.HOLD -> "Hold"
            BlockMode.BREATHING -> "Breathing"
            BlockMode.NONE -> "Off" // unreachable: NONE rules are filtered out before this point
        }
        return "Web - $modeName"
    }

    private companion object {
        // Default delay applied when content filter mode is DELAY (no per-rule
        // delay exists for the generic filter).
        const val CONTENT_FILTER_DELAY_SECONDS = 15
    }
}

data class WebDomainBlockResult(
    val decision: BlockDecision,
    val trackingPackage: String?
)
