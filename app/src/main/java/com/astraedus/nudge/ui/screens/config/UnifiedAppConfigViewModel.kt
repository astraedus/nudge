package com.astraedus.nudge.ui.screens.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.domain.lock.ChallengeState
import com.astraedus.nudge.domain.lock.RuleWeakening
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.FeatureMode
import com.astraedus.nudge.ui.components.DurationInput
import com.astraedus.nudge.ui.lock.StrictModeGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UnifiedAppConfigViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val blockRuleRepository: BlockRuleRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    nudgePreferences: NudgePreferences
) : ViewModel() {

    private val packageName: String = savedStateHandle.get<String>("packageName") ?: ""

    private val _uiState = MutableStateFlow(UnifiedAppConfigState(packageName = packageName))
    val uiState: StateFlow<UnifiedAppConfigState> = _uiState.asStateFlow()

    private val strictModeGate = StrictModeGate(nudgePreferences)

    /** Active Strict Mode unlock challenge, if a weakening action is pending. */
    val challenge: StateFlow<ChallengeState?> = strictModeGate.challenge

    /**
     * Snapshot of the existing default app rule at load time, used to decide whether a save
     * WEAKENS protection (and therefore needs the Strict Mode challenge). Null = no rule existed,
     * so a save is creation, never weakening.
     */
    private var existingDefaultRule: BlockRule? = null

    init {
        loadState()
    }

    private fun loadState() {
        viewModelScope.launch {
            // Resolve app name (single cached PackageManager lookup, off the main thread)
            val appName = installedAppsRepository.resolveAppName(packageName)

            // Get available features for this package
            val availableFeatures = UnifiedAppConfigState.FEATURES_BY_PACKAGE[packageName] ?: emptyList()

            // Load all direct rules for this package
            val allRules = blockRuleRepository.getRulesForPackage(packageName).first()
            val directRules = allRules.filter { it.packageName == packageName && it.groupId == null }

            // Separate into categories
            val defaultAppRule = directRules.firstOrNull {
                it.inAppFeatures.isNullOrEmpty() && it.scheduleDays == null
            }
            existingDefaultRule = defaultAppRule
            val scheduledAppRule = directRules.firstOrNull {
                it.inAppFeatures.isNullOrEmpty() && it.scheduleDays != null
            }
            val defaultFeatureRules = directRules.filter {
                !it.inAppFeatures.isNullOrEmpty() && it.scheduleDays == null
            }
            val scheduledFeatureRules = directRules.filter {
                !it.inAppFeatures.isNullOrEmpty() && it.scheduleDays != null
            }

            // Build feature overrides from default feature rules
            val featureOverrides = mutableMapOf<String, FeatureOverride>()
            for (rule in defaultFeatureRules) {
                val featureKey = rule.inAppFeatures ?: continue
                featureOverrides[featureKey] = FeatureOverride(
                    mode = mapBlockModeToFeatureMode(rule.mode),
                    delaySeconds = rule.delaySeconds,
                    autoKickEnabled = rule.autoKickAfter != null,
                    autoKickAfter = rule.autoKickAfter ?: 30,
                    autoKickCooldownMinutesText = DurationInput.cooldownSecondsToText(rule.autoKickCooldownSeconds),
                    originalAutoKickCooldownSeconds = rule.autoKickCooldownSeconds
                )
            }

            // Build scheduled feature overrides
            val scheduledFeatureOverridesMap = mutableMapOf<String, FeatureOverride>()
            for (rule in scheduledFeatureRules) {
                val featureKey = rule.inAppFeatures ?: continue
                scheduledFeatureOverridesMap[featureKey] = FeatureOverride(
                    mode = mapBlockModeToFeatureMode(rule.mode),
                    delaySeconds = rule.delaySeconds,
                    autoKickEnabled = rule.autoKickAfter != null,
                    autoKickAfter = rule.autoKickAfter ?: 30,
                    autoKickCooldownMinutesText = DurationInput.cooldownSecondsToText(rule.autoKickCooldownSeconds),
                    originalAutoKickCooldownSeconds = rule.autoKickCooldownSeconds
                )
            }

            // Parse schedule from scheduledAppRule
            val scheduleDays = scheduledAppRule?.scheduleDays
                ?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.toSet()
                ?: setOf(1, 2, 3, 4, 5)
            val scheduleStartMinute = scheduledAppRule?.scheduleStartMinute ?: 360
            val scheduleEndMinute = scheduledAppRule?.scheduleEndMinute ?: 540

            // Web domain state: check if default app rule has webDomains
            val existingWebDomains = defaultAppRule?.webDomains
            val webDomainEnabled = existingWebDomains != null
            val webDomainsValue = existingWebDomains
                ?: UnifiedAppConfigState.DEFAULT_WEB_DOMAINS[packageName]
                ?: ""

            _uiState.value = UnifiedAppConfigState(
                packageName = packageName,
                appName = appName,
                // Always-active settings (from default app rule)
                enabled = defaultAppRule?.enabled ?: true,
                dailyLimitEnabled = defaultAppRule?.dailyLimitMinutes != null,
                dailyLimitMinutes = defaultAppRule?.dailyLimitMinutes ?: 30,
                showCounter = defaultAppRule?.showCounter ?: true,
                showTimeRemaining = defaultAppRule?.showTimeRemaining ?: false,
                grayscale = defaultAppRule?.grayscale ?: false,
                // Web domain blocking
                webDomainEnabled = webDomainEnabled,
                webDomains = webDomainsValue,
                // Default behavior
                defaultMode = parseBlockMode(defaultAppRule?.mode),
                // If the saved rule is NONE there is no prior blocking choice to restore, so
                // offer DELAY when the user switches whole-app blocking back on.
                lastBlockingMode = parseBlockMode(defaultAppRule?.mode)
                    .takeIf { it != BlockMode.NONE } ?: BlockMode.DELAY,
                defaultDelaySeconds = defaultAppRule?.delaySeconds ?: 15,
                defaultAutoKickEnabled = defaultAppRule?.autoKickAfter != null || defaultAppRule?.autoKickAfterMinutes != null,
                defaultAutoKickByInteractions = defaultAppRule == null || defaultAppRule.autoKickAfter != null,
                defaultAutoKickAfter = defaultAppRule?.autoKickAfter ?: 30,
                defaultAutoKickAfterMinutesText = DurationInput.minutesToText(defaultAppRule?.autoKickAfterMinutes),
                defaultAutoKickCooldownMinutesText = DurationInput.cooldownSecondsToText(defaultAppRule?.autoKickCooldownSeconds ?: 60),
                originalAutoKickCooldownSeconds = defaultAppRule?.autoKickCooldownSeconds ?: 60,
                originalAutoKickAfterMinutes = defaultAppRule?.autoKickAfterMinutes,
                // Feature overrides
                availableFeatures = availableFeatures,
                featureOverrides = featureOverrides,
                // Scheduled override
                scheduledOverrideEnabled = scheduledAppRule != null,
                scheduleDays = scheduleDays,
                scheduleStartHour = scheduleStartMinute / 60,
                scheduleStartMinute = scheduleStartMinute % 60,
                scheduleEndHour = scheduleEndMinute / 60,
                scheduleEndMinute = scheduleEndMinute % 60,
                scheduledMode = parseBlockMode(scheduledAppRule?.mode),
                scheduledDelaySeconds = scheduledAppRule?.delaySeconds ?: 15,
                scheduledFeatureOverrides = scheduledFeatureOverridesMap,
                // UI state
                isLoading = false,
                isSaved = false,
                hasExistingRules = directRules.isNotEmpty(),
                showDeleteConfirmation = false
            )
        }
    }

    private fun parseBlockMode(mode: String?): BlockMode = when (mode) {
        "NONE" -> BlockMode.NONE
        "HARD_BLOCK" -> BlockMode.HARD_BLOCK
        "DELAY" -> BlockMode.DELAY
        "BREATHING" -> BlockMode.BREATHING
        // Includes null (no existing rule): a brand-new rule defaults to DELAY, not NONE, so
        // opening the editor for an unconfigured app still proposes actual protection.
        else -> BlockMode.DELAY
    }

    private fun mapBlockModeToFeatureMode(mode: String): FeatureMode = when (mode) {
        "HARD_BLOCK" -> FeatureMode.BLOCK
        "DELAY" -> FeatureMode.DELAY
        "BREATHING" -> FeatureMode.BREATHING
        else -> FeatureMode.INHERIT
    }

    // ═══ Save logic ═══

    /** Builds the app-level default rule that [save] would persist for the current state. */
    private fun buildDefaultRule(state: UnifiedAppConfigState): BlockRule {
        val webDomains = if (state.webDomainEnabled && state.webDomains.isNotBlank()) {
            state.webDomains.trim()
        } else null

        return BlockRule(
            packageName = packageName,
            mode = state.defaultMode.name,
            delaySeconds = state.defaultDelaySeconds,
            dailyLimitMinutes = if (state.dailyLimitEnabled) state.dailyLimitMinutes else null,
            enabled = state.enabled,
            showCounter = state.showCounter,
            showTimeRemaining = state.showTimeRemaining && state.dailyLimitEnabled,
            grayscale = state.grayscale,
            autoKickAfter = if (state.defaultAutoKickEnabled && state.defaultAutoKickByInteractions) state.defaultAutoKickAfter else null,
            autoKickAfterMinutes = if (state.defaultAutoKickEnabled) {
                DurationInput.resolveMinutes(state.defaultAutoKickAfterMinutesText, state.originalAutoKickAfterMinutes)
            } else null,
            autoKickCooldownSeconds = DurationInput.resolveCooldownSeconds(
                state.defaultAutoKickCooldownMinutesText,
                state.originalAutoKickCooldownSeconds
            ),
            webDomains = webDomains
        )
    }

    fun save() {
        viewModelScope.launch {
            val state = _uiState.value
            val newDefault = buildDefaultRule(state)
            val old = existingDefaultRule

            // A save only needs the challenge when it weakens an EXISTING rule. Creating a fresh
            // rule (no prior rule) is strengthening, so it saves freely even under Strict Mode.
            val weakens = old != null && RuleWeakening.isWeakening(old, newDefault)
            if (weakens) {
                strictModeGate.run(prompt = "Weaken blocking for this app") {
                    performSave(state, newDefault)
                }
            } else {
                performSave(state, newDefault)
            }
        }
    }

    private suspend fun performSave(state: UnifiedAppConfigState, newDefault: BlockRule) {
        // 1. Clean slate: delete all direct rules for this package
        blockRuleRepository.deleteDirectRulesForPackage(packageName)

        // 2. Create app-level default rule
        blockRuleRepository.addRule(newDefault)
        // Keep the snapshot in sync so a second save in the same session compares correctly.
        existingDefaultRule = newDefault

        // 3. Create feature override rules (non-INHERIT only)
        for ((featureKey, override) in state.featureOverrides) {
            if (override.mode == FeatureMode.INHERIT) continue
            val ruleMode = when (override.mode) {
                FeatureMode.BLOCK -> "HARD_BLOCK"
                FeatureMode.DELAY -> "DELAY"
                FeatureMode.BREATHING -> "BREATHING"
                else -> continue
            }
            blockRuleRepository.addRule(
                BlockRule(
                    packageName = packageName,
                    mode = ruleMode,
                    delaySeconds = override.delaySeconds,
                    enabled = state.enabled,
                    inAppFeatures = featureKey,
                    autoKickAfter = if (override.autoKickEnabled) override.autoKickAfter else null,
                    autoKickCooldownSeconds = DurationInput.resolveCooldownSeconds(
                        override.autoKickCooldownMinutesText,
                        override.originalAutoKickCooldownSeconds
                    ),
                    showCounter = state.showCounter
                )
            )
        }

        // 4. Create scheduled override rules
        if (state.scheduledOverrideEnabled) {
            val scheduleDaysStr = state.scheduleDays.sorted().joinToString(",")
            val startMin = state.scheduleStartHour * 60 + state.scheduleStartMinute
            val endMin = state.scheduleEndHour * 60 + state.scheduleEndMinute

            // Scheduled app-level rule
            blockRuleRepository.addRule(
                BlockRule(
                    packageName = packageName,
                    mode = state.scheduledMode.name,
                    delaySeconds = state.scheduledDelaySeconds,
                    enabled = state.enabled,
                    scheduleDays = scheduleDaysStr,
                    scheduleStartMinute = startMin,
                    scheduleEndMinute = endMin
                )
            )

            // Scheduled feature override rules
            for ((featureKey, override) in state.scheduledFeatureOverrides) {
                if (override.mode == FeatureMode.INHERIT) continue
                val ruleMode = when (override.mode) {
                    FeatureMode.BLOCK -> "HARD_BLOCK"
                    FeatureMode.DELAY -> "DELAY"
                    FeatureMode.BREATHING -> "BREATHING"
                    else -> continue
                }
                blockRuleRepository.addRule(
                    BlockRule(
                        packageName = packageName,
                        mode = ruleMode,
                        delaySeconds = override.delaySeconds,
                        enabled = state.enabled,
                        inAppFeatures = featureKey,
                        autoKickAfter = if (override.autoKickEnabled) override.autoKickAfter else null,
                        autoKickCooldownSeconds = DurationInput.resolveCooldownSeconds(
                            override.autoKickCooldownMinutesText,
                            override.originalAutoKickCooldownSeconds
                        ),
                        showCounter = state.showCounter,
                        scheduleDays = scheduleDaysStr,
                        scheduleStartMinute = startMin,
                        scheduleEndMinute = endMin
                    )
                )
            }
        }

        _uiState.value = state.copy(isSaved = true)
    }

    // ═══ Delete ═══

    fun deleteAllRules() {
        viewModelScope.launch {
            // Deleting an existing rule weakens protection; gate it under Strict Mode.
            // (No existing rule = nothing to weaken; delete proceeds freely.)
            if (existingDefaultRule != null) {
                strictModeGate.run(prompt = "Delete blocking for this app") {
                    performDelete()
                }
            } else {
                performDelete()
            }
        }
    }

    private suspend fun performDelete() {
        blockRuleRepository.deleteDirectRulesForPackage(packageName)
        existingDefaultRule = null
        _uiState.value = _uiState.value.copy(isSaved = true)
    }

    /** Called from the challenge dialog; runs the pending weakening action on exact match. */
    fun verifyChallenge(input: String) {
        viewModelScope.launch { strictModeGate.verifyAndRun(input) }
    }

    /** Called when the user cancels the challenge dialog. */
    fun cancelChallenge() {
        strictModeGate.cancel()
    }

    fun showDeleteConfirmation() {
        _uiState.value = _uiState.value.copy(showDeleteConfirmation = true)
    }

    fun dismissDeleteConfirmation() {
        _uiState.value = _uiState.value.copy(showDeleteConfirmation = false)
    }

    // ═══ Always-active settings ═══

    fun setEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(enabled = enabled)
    }

    fun setDailyLimitEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(dailyLimitEnabled = enabled)
    }

    fun setDailyLimitMinutes(minutes: Int) {
        _uiState.value = _uiState.value.copy(dailyLimitMinutes = minutes)
    }

    fun setShowCounter(show: Boolean) {
        _uiState.value = _uiState.value.copy(showCounter = show)
    }

    fun setShowTimeRemaining(show: Boolean) {
        _uiState.value = _uiState.value.copy(showTimeRemaining = show)
    }

    fun setGrayscale(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(grayscale = enabled)
    }

    fun setWebDomainEnabled(enabled: Boolean) {
        val state = _uiState.value
        if (enabled && state.webDomains.isBlank()) {
            // Auto-populate with known defaults for this package
            val defaults = UnifiedAppConfigState.DEFAULT_WEB_DOMAINS[packageName] ?: ""
            _uiState.value = state.copy(webDomainEnabled = true, webDomains = defaults)
        } else {
            _uiState.value = state.copy(webDomainEnabled = enabled)
        }
    }

    fun setWebDomains(domains: String) {
        _uiState.value = _uiState.value.copy(webDomains = domains)
    }

    // ═══ Default behavior ═══

    fun setDefaultMode(mode: BlockMode) {
        _uiState.value = _uiState.value.copy(
            defaultMode = mode,
            // Remember the last real blocking choice so toggling whole-app blocking off and back
            // on restores it instead of snapping to DELAY.
            lastBlockingMode = if (mode == BlockMode.NONE) _uiState.value.lastBlockingMode else mode
        )
    }

    /**
     * Turn whole-app blocking on/off. Off writes [BlockMode.NONE] on the app-level rule, which
     * keeps the daily limit, counter, overlays, grayscale and web domains intact while letting the
     * app itself open — the combination needed to block only Shorts/Reels.
     */
    fun setBlocksWholeApp(blocks: Boolean) {
        val current = _uiState.value
        _uiState.value = current.copy(
            defaultMode = if (blocks) current.lastBlockingMode else BlockMode.NONE
        )
    }

    fun setDefaultDelaySeconds(seconds: Int) {
        _uiState.value = _uiState.value.copy(defaultDelaySeconds = seconds)
    }

    fun setDefaultAutoKickEnabled(enabled: Boolean) {
        val state = _uiState.value
        // Switching the section on when neither trigger is configured would be a toggle that
        // silently does nothing. Fall back to the interaction trigger -- which is exactly what
        // this switch meant before the time trigger existed.
        val noTriggerConfigured = !state.defaultAutoKickByInteractions &&
            DurationInput.parseMinutes(state.defaultAutoKickAfterMinutesText) == null
        _uiState.value = state.copy(
            defaultAutoKickEnabled = enabled,
            defaultAutoKickByInteractions = state.defaultAutoKickByInteractions ||
                (enabled && noTriggerConfigured)
        )
    }

    fun setDefaultAutoKickByInteractions(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(defaultAutoKickByInteractions = enabled)
    }

    fun setDefaultAutoKickAfter(count: Int) {
        _uiState.value = _uiState.value.copy(defaultAutoKickAfter = count)
    }

    fun setDefaultAutoKickAfterMinutesText(text: String) {
        _uiState.value = _uiState.value.copy(defaultAutoKickAfterMinutesText = text)
    }

    fun setDefaultAutoKickCooldownMinutesText(text: String) {
        _uiState.value = _uiState.value.copy(defaultAutoKickCooldownMinutesText = text)
    }

    // ═══ Feature overrides ═══

    fun setFeatureOverride(featureKey: String, override: FeatureOverride) {
        val current = _uiState.value.featureOverrides.toMutableMap()
        current[featureKey] = override
        _uiState.value = _uiState.value.copy(featureOverrides = current)
    }

    // ═══ Scheduled override ═══

    fun setScheduledOverrideEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(scheduledOverrideEnabled = enabled)
    }

    fun setScheduleDays(days: Set<Int>) {
        _uiState.value = _uiState.value.copy(scheduleDays = days)
    }

    fun setScheduleStartTime(hour: Int, minute: Int) {
        _uiState.value = _uiState.value.copy(
            scheduleStartHour = hour,
            scheduleStartMinute = minute
        )
    }

    fun setScheduleEndTime(hour: Int, minute: Int) {
        _uiState.value = _uiState.value.copy(
            scheduleEndHour = hour,
            scheduleEndMinute = minute
        )
    }

    fun setScheduledMode(mode: BlockMode) {
        _uiState.value = _uiState.value.copy(scheduledMode = mode)
    }

    fun setScheduledDelaySeconds(seconds: Int) {
        _uiState.value = _uiState.value.copy(scheduledDelaySeconds = seconds)
    }

    fun setScheduledFeatureOverride(featureKey: String, override: FeatureOverride) {
        val current = _uiState.value.scheduledFeatureOverrides.toMutableMap()
        current[featureKey] = override
        _uiState.value = _uiState.value.copy(scheduledFeatureOverrides = current)
    }
}
