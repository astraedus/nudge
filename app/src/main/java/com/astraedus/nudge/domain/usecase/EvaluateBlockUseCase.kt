package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.WebDomainMatcher
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.model.GroupMembership
import com.astraedus.nudge.domain.model.WebBlockMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

class EvaluateBlockUseCase @Inject constructor(
    private val blockRuleRepository: BlockRuleRepository,
    private val usageRepository: UsageRepository,
    private val blockEngine: BlockEngine,
    private val ruleEvaluator: RuleEvaluator,
    private val scheduleEvaluator: ScheduleEvaluator,
    private val preferences: NudgePreferences,
    private val contentFilter: ContentFilter
) {

    /**
     * Evaluate whether a package should be blocked right now.
     * Combines rule resolution, daily usage lookup, and the block engine decision.
     *
     * @param detectedFeature If the accessibility service detected an in-app feature
     *   (e.g. "REELS", "SHORTS"), pass it here so the engine can match feature-level rules.
     * @param includeWholeAppRulesForFeature Whether feature evaluation should also consider
     *   whole-app rules. This is disabled after a whole-app delay has completed so in-app rules
     *   can still fire without looping the whole-app gate.
     */
    suspend fun invoke(
        packageName: String,
        detectedFeature: String? = null,
        includeWholeAppRulesForFeature: Boolean = true
    ): BlockDecision {
        val activeRules = resolveActiveRules(packageName)
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
     * Every rule that applies to [packageName] right now, as [ActiveRule]s, schedules NOT yet
     * applied (the engine applies those).
     *
     * Extracted because a second caller appeared ([isFollowingSteerEnabled]) and this is the entire
     * entity-to-domain mapping: a copy of it in that caller would be ~25 lines that silently stop
     * agreeing the next time a column is added, which is exactly what happened to the four
     * hand-written `when` blocks that `FeatureMode.toBlockMode` now replaces.
     */
    private suspend fun resolveActiveRules(packageName: String): List<ActiveRule> {
        val allRules = blockRuleRepository.getEnabledRules().first()
        val allGroups = blockRuleRepository.getAllGroups().first()

        val ruleDataList = allRules.map { rule ->
            BlockRuleData(
                id = rule.id,
                packageName = rule.packageName,
                groupId = rule.groupId,
                mode = try { BlockMode.valueOf(rule.mode) } catch (_: Exception) { BlockMode.HARD_BLOCK },
                delaySeconds = rule.delaySeconds,
                dailyLimitMinutes = rule.dailyLimitMinutes,
                enabled = rule.enabled,
                scheduleDays = rule.scheduleDays?.split(",")?.mapNotNull { it.trim().toIntOrNull() },
                scheduleStartMinute = rule.scheduleStartMinute,
                scheduleEndMinute = rule.scheduleEndMinute,
                inAppFeatures = rule.inAppFeatures?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() },
                grayscale = rule.grayscale,
                webDomains = rule.webDomains,
                tabVanish = rule.tabVanish,
                followingSteer = rule.followingSteer
            )
        }

        val memberships = mutableListOf<GroupMembership>()
        for (group in allGroups) {
            val members = blockRuleRepository.getGroupMembers(group.id).first()
            for (member in members) {
                memberships.add(GroupMembership(groupId = member.groupId, packageName = member.packageName))
            }
        }

        return ruleEvaluator.resolveRulesForPackage(packageName, ruleDataList, memberships)
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
        val allRules = blockRuleRepository.getEnabledRules().first()

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
        if (!contentFilter.isBlocked(urlBarText, strict)) {
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
