package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.WebDomainMatcher
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.lightsoff.LightsOffWindow
import com.astraedus.nudge.domain.lightsoff.LightsOffWindowResolver
import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.model.GroupMembership
import kotlinx.coroutines.flow.first
import java.util.Calendar
import javax.inject.Inject

class EvaluateBlockUseCase @Inject constructor(
    private val blockRuleRepository: BlockRuleRepository,
    private val usageRepository: UsageRepository,
    private val blockEngine: BlockEngine,
    private val ruleEvaluator: RuleEvaluator,
    private val preferences: NudgePreferences,
    private val contentFilter: ContentFilter,
    private val lightsOffWindowResolver: LightsOffWindowResolver
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
        val allRules = blockRuleRepository.getEnabledRules().first()
        val allGroups = blockRuleRepository.getAllGroups().first()

        // Convert entity rules to domain data classes
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
                webDomains = rule.webDomains
            )
        }

        // Build group memberships from all groups
        val memberships = mutableListOf<GroupMembership>()
        for (group in allGroups) {
            val members = blockRuleRepository.getGroupMembers(group.id).first()
            for (member in members) {
                memberships.add(GroupMembership(groupId = member.groupId, packageName = member.packageName))
            }
        }

        val activeRules = ruleEvaluator.resolveRulesForPackage(packageName, ruleDataList, memberships)
        val dailyUsageMs = usageRepository.getDailyUsage(packageName).first()
        val lightsOff = resolveLightsOff()

        return blockEngine.evaluate(
            packageName = packageName,
            activeRules = activeRules,
            dailyUsageMs = dailyUsageMs,
            detectedFeature = detectedFeature,
            includeWholeAppRulesForFeature = includeWholeAppRulesForFeature,
            isLightsOffActive = lightsOff.active,
            isLightsOffWhitelisted = lightsOff.allows(packageName),
            lightsOffRuleName = lightsOff.ruleName
        )
    }

    /**
     * Lights Off ONLY, with no per-app rules considered.
     *
     * Needed because browser packages never reach [invoke] — the accessibility service routes them
     * straight to per-URL evaluation ([evaluateWebDomain]), which is correct for per-app rules
     * (whole-app blocking a browser would nuke all browsing) but would leave the entire web wide open
     * during a GLOBAL lockdown. The service asks this first for browsers, so an un-whitelisted browser
     * is blocked during Lights Off even before a URL has been read.
     *
     * Returns [BlockDecision.Allow] whenever Lights Off is off, outside its window, or the package is
     * allow-listed. The decision itself is still produced by [BlockEngine] (empty rule list) so step 0
     * stays the single source of truth for what a Lights Off block looks like.
     */
    suspend fun evaluateLightsOff(packageName: String): BlockDecision {
        val lightsOff = resolveLightsOff()
        if (!lightsOff.active) return BlockDecision.Allow
        return blockEngine.evaluate(
            packageName = packageName,
            activeRules = emptyList(),
            dailyUsageMs = 0L,
            isLightsOffActive = true,
            isLightsOffWhitelisted = lightsOff.allows(packageName),
            lightsOffRuleName = lightsOff.ruleName
        )
    }

    /**
     * Read the stored Lights Off state and resolve it against the current clock.
     *
     * The window logic itself lives in [LightsOffWindowResolver], shared with the accessibility
     * service's status notification, so what we ENFORCE and what we TELL the user can never diverge.
     * One [Calendar] is captured for the whole resolution so a single evaluation cannot straddle a
     * minute boundary and disagree with itself.
     */
    private suspend fun resolveLightsOff(): LightsOffWindow = lightsOffWindowResolver.resolve(
        enabled = preferences.lightsOffEnabled.first(),
        profile = preferences.lightsOffProfiles.first().firstOrNull(),
        manualUntilMs = preferences.lightsOffManualUntil.first(),
        now = Calendar.getInstance()
    )

    /**
     * Evaluate whether a detected web domain should be blocked.
     * Checks all enabled rules that have webDomains configured and matches
     * the detected URL against them.
     *
     * @param urlBarText The text from the browser's URL bar
     * @return BlockDecision and the matching rule's associated packageName (for usage tracking)
     */
    suspend fun evaluateWebDomain(urlBarText: String): WebDomainBlockResult {
        val allRules = blockRuleRepository.getEnabledRules().first()

        // Find rules with webDomains that match the detected URL
        val matchingRules = allRules.filter { rule ->
            rule.webDomains != null && WebDomainMatcher.matches(urlBarText, rule.webDomains)
        }

        if (matchingRules.isEmpty()) {
            // No explicit per-rule web domain match. Fall through to the generic
            // content filter (bundled blocklist + keywords) if it is enabled.
            return evaluateContentFilter(urlBarText)
        }

        // Convert matching rules to ActiveRules for BlockEngine evaluation
        val activeRules = matchingRules.map { rule ->
            ActiveRule(
                mode = try { BlockMode.valueOf(rule.mode) } catch (_: Exception) { BlockMode.HARD_BLOCK },
                delaySeconds = rule.delaySeconds,
                dailyLimitMinutes = rule.dailyLimitMinutes,
                enabled = rule.enabled,
                scheduleDays = rule.scheduleDays?.split(",")?.mapNotNull { it.trim().toIntOrNull() },
                scheduleStartMinute = rule.scheduleStartMinute,
                scheduleEndMinute = rule.scheduleEndMinute,
                inAppFeatures = null, // Web domain rules apply as whole-app rules
                grayscale = rule.grayscale,
                ruleName = buildWebDomainRuleName(rule.packageName, rule.mode)
            )
        }

        // Use the first matching rule's package for usage stats lookup
        val trackingPackage = matchingRules.first().packageName ?: "web"
        val dailyUsageMs = usageRepository.getDailyUsage(trackingPackage).first()

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
        val dailyUsageMs = usageRepository.getDailyUsage(trackingPackage).first()

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
            dailyUsageMs = dailyUsageMs
        )

        return WebDomainBlockResult(decision, trackingPackage)
    }

    private fun buildWebDomainRuleName(packageName: String?, mode: String): String {
        val modeName = when (mode) {
            "HARD_BLOCK" -> "Hard Block"
            "DELAY" -> "Delay"
            "BREATHING" -> "Breathing"
            else -> mode
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
