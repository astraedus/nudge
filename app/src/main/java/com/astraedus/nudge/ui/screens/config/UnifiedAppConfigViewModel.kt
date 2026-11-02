package com.astraedus.nudge.ui.screens.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.domain.lock.ChallengeState
import com.astraedus.nudge.domain.engine.CoverageFacts
import com.astraedus.nudge.domain.lock.RuleWeakening
import com.astraedus.nudge.domain.packages.EssentialPackages
import com.astraedus.nudge.domain.packages.PackageFacts
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.FeatureMode
import com.astraedus.nudge.ui.components.DurationInput
import com.astraedus.nudge.ui.components.UsageReminderInput
import com.astraedus.nudge.ui.lock.StrictModeGate
import com.astraedus.nudge.data.repository.RuleArming
import com.astraedus.nudge.domain.engine.FirstUnlockWindow
import com.astraedus.nudge.domain.usecase.SetDayStartMinuteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UnifiedAppConfigViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val blockRuleRepository: BlockRuleRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val essentialPackages: EssentialPackages,
    private val packageFacts: PackageFacts,
    private val nudgePreferences: NudgePreferences,
    private val setDayStartMinuteUseCase: SetDayStartMinuteUseCase
) : ViewModel() {

    private val packageName: String = savedStateHandle.get<String>("packageName") ?: ""

    private val _uiState = MutableStateFlow(UnifiedAppConfigState(packageName = packageName))
    val uiState: StateFlow<UnifiedAppConfigState> = _uiState.asStateFlow()

    private val strictModeGate = StrictModeGate(nudgePreferences)

    /** Active Strict Mode unlock challenge, if a weakening action is pending. */
    val challenge: StateFlow<ChallengeState?> = strictModeGate.challenge

    /**
     * The groups that block this app right now, for "Also covered by" (spec 3.7): read-only, live,
     * so a group edited from here is current on the way back. Empty hides the section.
     */
    val coveredBy: StateFlow<List<CoveringGroup>> = flow {
        val facts = deviceFacts()
        emitAll(
            combine(
                blockRuleRepository.getAllGroups(),
                blockRuleRepository.getAllGroupMembers(),
                blockRuleRepository.getAllRules()
            ) { groups, members, rules -> coveringGroups(packageName, facts, groups, members, rules) }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * This app's device facts: essential (never in a group or All apps rule), launchable, its
     * category (what a category group matches) and whether it is a browser (which a category never
     * brings into a group). Read once per screen, off the main thread.
     */
    private suspend fun deviceFacts(): CoverageFacts {
        val fact = packageFacts.factsFor(packageName)
        return CoverageFacts(
            essential = packageName in essentialPackages.current(),
            launchable = fact.launchable,
            category = fact.category,
            browser = packageName in essentialPackages.browsers()
        )
    }

    /**
     * The All apps rules that cover this app right now, for "Also covered by" next to the groups
     * (spec 3.7). Coverage is [com.astraedus.nudge.domain.engine.RuleCoverage]'s, fed this app's
     * device facts once. The flow body, not the property initialiser, touches the repositories, so
     * nothing is read until the section is collected.
     */
    val coveredByAllApps: StateFlow<List<CoveringAllApps>> = flow {
        val labels = installedAppsRepository.getInstalledApps().associate { it.packageName to it.appName }
        val facts = deviceFacts()
        emitAll(
            combine(
                blockRuleRepository.getAllGroups(),
                blockRuleRepository.getAllGroupMembers(),
                blockRuleRepository.getAllRules()
            ) { groups, members, rules ->
                coveringAllApps(packageName, groups, members, rules, labels, facts)
            }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Snapshot of the existing default app rule at load time, used to decide whether a save
     * WEAKENS protection (and therefore needs the Strict Mode challenge). Null = no rule existed,
     * so a save is creation, never weakening.
     */
    private var existingDefaultRule: BlockRule? = null

    /**
     * The scheduled override at load time, for the SAME reason as [existingDefaultRule]: a save that
     * shortens its first-unlock window, changes its kind or turns it off must take the challenge
     * (spec 5.6). Null = no override loaded, so writing one is creation, never weakening.
     */
    private var existingScheduledRule: BlockRule? = null

    /**
     * The two global first-unlock facts this screen displays: where the logical day starts, and the
     * stamp that decides whether an override saved now waits for tomorrow. See
     * [refreshFirstUnlockHints].
     */
    @Volatile private var logicalDayStartMinute: Int = FirstUnlockWindow.DEFAULT_DAY_START_MINUTE
    @Volatile private var firstUnlockStamp: Long = FirstUnlockWindow.NO_STAMP

    init {
        loadState()
        viewModelScope.launch {
            nudgePreferences.dayStartMinute.collect { minute ->
                logicalDayStartMinute = minute
                refreshFirstUnlockHints()
            }
        }
        viewModelScope.launch {
            nudgePreferences.firstUnlockAt.collect { stamp ->
                firstUnlockStamp = stamp
                refreshFirstUnlockHints()
            }
        }
    }

    /**
     * "Starts at your next first unlock." is true exactly when today's unlock has already happened:
     * saving stamps `armedAt` after it, so the window cannot open until tomorrow (spec 5.3).
     */
    private fun refreshFirstUnlockHints() {
        _uiState.update {
            it.copy(
                dayStartMinute = logicalDayStartMinute,
                startsAtNextUnlock = it.scheduledOverrideEnabled &&
                    it.scheduledOverrideFirstUnlock &&
                    FirstUnlockWindow.startsAtNextUnlock(
                        nowMs = System.currentTimeMillis(),
                        stampMs = firstUnlockStamp,
                        dayStartMinute = logicalDayStartMinute,
                        zone = ZoneId.systemDefault()
                    )
            )
        }
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
            // A scheduled rule is either a days-and-times window or a first-unlock one (spec 3.5).
            // `scheduleDays != null` stays in the test on purpose: a days-only rule (days set, no
            // times) used to count as the scheduled override, and treating it as the default would
            // have a save rewrite it as always-on. A first-unlock rule always carries days too, and
            // an imported one may carry none, which the other two checks catch.
            fun isScheduled(rule: BlockRule): Boolean =
                rule.firstUnlockMinutes != null ||
                    rule.scheduleStartMinute != null ||
                    rule.scheduleEndMinute != null ||
                    rule.scheduleDays != null

            val defaultAppRule = directRules.firstOrNull {
                it.inAppFeatures.isNullOrEmpty() && !isScheduled(it)
            }
            existingDefaultRule = defaultAppRule
            val scheduledAppRule = directRules.firstOrNull {
                it.inAppFeatures.isNullOrEmpty() && isScheduled(it)
            }
            existingScheduledRule = scheduledAppRule
            val defaultFeatureRules = directRules.filter {
                !it.inAppFeatures.isNullOrEmpty() && !isScheduled(it)
            }
            val scheduledFeatureRules = directRules.filter {
                !it.inAppFeatures.isNullOrEmpty() && isScheduled(it)
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

            // What the websites block with. A stored webBlockMode wins; otherwise they inherit
            // the app-level mode, so seed the picker with that (falling back to DELAY when the
            // app-level mode is NONE and there is no stored choice to show).
            val appLevelMode = parseBlockMode(defaultAppRule?.mode)
            val webBlockMode = defaultAppRule?.webBlockMode
                ?.let { parseBlockMode(it) }
                ?.takeIf { it != BlockMode.NONE }
                ?: appLevelMode.takeIf { it != BlockMode.NONE }
                ?: BlockMode.DELAY

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
                webBlockMode = webBlockMode,
                // Default behavior
                defaultMode = appLevelMode,
                // If the saved rule is NONE there is no prior blocking choice to restore, so
                // offer DELAY when the user switches whole-app blocking back on.
                lastBlockingMode = appLevelMode.takeIf { it != BlockMode.NONE } ?: BlockMode.DELAY,
                defaultDelaySeconds = defaultAppRule?.delaySeconds ?: 15,
                defaultAutoKickEnabled = defaultAppRule?.autoKickAfter != null || defaultAppRule?.autoKickAfterMinutes != null,
                defaultAutoKickByInteractions = defaultAppRule == null || defaultAppRule.autoKickAfter != null,
                defaultAutoKickAfter = defaultAppRule?.autoKickAfter ?: 30,
                defaultAutoKickAfterMinutesText = DurationInput.minutesToText(defaultAppRule?.autoKickAfterMinutes),
                defaultAutoKickCooldownMinutesText = DurationInput.cooldownSecondsToText(defaultAppRule?.autoKickCooldownSeconds ?: 60),
                originalAutoKickCooldownSeconds = defaultAppRule?.autoKickCooldownSeconds ?: 60,
                originalAutoKickAfterMinutes = defaultAppRule?.autoKickAfterMinutes,
                reminderEnabled = defaultAppRule?.remindAfterMinutes != null,
                remindAfterMinutesText = DurationInput.minutesToText(defaultAppRule?.remindAfterMinutes),
                originalRemindAfterMinutes = defaultAppRule?.remindAfterMinutes,
                // Feature overrides
                availableFeatures = availableFeatures,
                featureOverrides = featureOverrides,
                // Scheduled override
                scheduledOverrideEnabled = scheduledAppRule != null,
                scheduledOverrideFirstUnlock = scheduledAppRule?.firstUnlockMinutes != null,
                firstUnlockMinutes = scheduledAppRule?.firstUnlockMinutes
                    ?: FirstUnlockWindow.DEFAULT_FIRST_UNLOCK_MINUTES,
                scheduleDays = scheduleDays,
                scheduleStartHour = scheduleStartMinute / 60,
                scheduleStartMinute = scheduleStartMinute % 60,
                scheduleEndHour = scheduleEndMinute / 60,
                scheduleEndMinute = scheduleEndMinute % 60,
                scheduledMode = parseBlockMode(scheduledAppRule?.mode),
                scheduledDelaySeconds = scheduledAppRule?.delaySeconds ?: 15,
                scheduledFeatureOverrides = scheduledFeatureOverridesMap,
                // Platform surface capabilities -- read off the default app rule; falling back
                // to the same defaults as a fresh BlockRule when there is no existing rule.
                tabVanish = defaultAppRule?.tabVanish ?: true,
                followingSteer = defaultAppRule?.followingSteer ?: false,
                keepPassOnShortTrips = defaultAppRule?.keepPassOnShortTrips ?: false,
                // UI state
                isLoading = false,
                isSaved = false,
                hasExistingRules = directRules.isNotEmpty(),
                showDeleteConfirmation = false
            )
            // The override now has a kind, so an existing first-unlock window opened after today's
            // unlock already says "Starts at your next first unlock." before any edit.
            refreshFirstUnlockHints()
        }
    }

    /**
     * The stored `mode` column as a [BlockMode]. Matched against the enum rather than hand-listed,
     * so a mode added to [BlockMode] is readable back off a saved rule without a second edit here.
     *
     * Anything unrecognised, INCLUDING null (no existing rule), falls to DELAY rather than NONE, so
     * opening the editor for an unconfigured app still proposes actual protection.
     */
    private fun parseBlockMode(mode: String?): BlockMode =
        BlockMode.entries.firstOrNull { it.name == mode } ?: BlockMode.DELAY

    private fun mapBlockModeToFeatureMode(mode: String): FeatureMode =
        FeatureMode.fromBlockMode(mode)

    // ═══ Save logic ═══

    /**
     * Builds the scheduled override rule that [save] would persist, or null when the override is
     * off. One construction, used by the save AND by the weakening check: a gate that judged a
     * different row from the one written would be the one way Strict Mode could disagree with what
     * actually lands on disk.
     *
     * A first-unlock override keeps its days, takes the window length and writes NO clock times
     * (spec 3.5): one kind of schedule or the other.
     */
    internal fun buildScheduledRule(state: UnifiedAppConfigState): BlockRule? {
        if (!state.scheduledOverrideEnabled) return null
        val scheduleDaysStr = state.scheduleDays.sorted().joinToString(",")
        val startMin = state.scheduleStartHour * 60 + state.scheduleStartMinute
        val endMin = state.scheduleEndHour * 60 + state.scheduleEndMinute
        val firstUnlockMinutes = if (state.scheduledOverrideFirstUnlock) {
            state.firstUnlockMinutes
        } else {
            null
        }
        // `performSave` deletes and re-adds this row, so the repository sees it as new and honours
        // whatever stamp is carried here (RuleArming). Carrying is what lets a stronger edit keep a
        // running window; a rule that only NOW becomes a first-unlock one carries NEVER_ARMED, so
        // the repository arms it for the next unlock.
        val wasFirstUnlock = existingScheduledRule?.firstUnlockMinutes != null
        val armedAt = if (firstUnlockMinutes != null && wasFirstUnlock) {
            existingScheduledRule?.armedAt ?: RuleArming.NEVER_ARMED
        } else if (firstUnlockMinutes == null) {
            existingScheduledRule?.armedAt ?: RuleArming.NEVER_ARMED
        } else {
            RuleArming.NEVER_ARMED
        }
        return BlockRule(
            packageName = packageName,
            mode = state.scheduledMode.name,
            delaySeconds = state.scheduledDelaySeconds,
            enabled = state.enabled,
            scheduleDays = scheduleDaysStr,
            scheduleStartMinute = if (firstUnlockMinutes != null) null else startMin,
            scheduleEndMinute = if (firstUnlockMinutes != null) null else endMin,
            firstUnlockMinutes = firstUnlockMinutes,
            armedAt = armedAt,
            tabVanish = state.tabVanish,
            keepPassOnShortTrips = state.effectiveKeepPassOnShortTrips
        )
    }

    /** Builds the app-level default rule that [save] would persist for the current state. */
    internal fun buildDefaultRule(state: UnifiedAppConfigState): BlockRule {
        val webDomains = if (state.webDomainEnabled && state.webDomains.isNotBlank()) {
            state.webDomains.trim()
        } else null

        // Persist an independent web mode ONLY when the app-level mode cannot express one — i.e.
        // when whole-app blocking is off. While it is on, websites inherit the app's mode (null),
        // so the two can never drift apart or need a second edit to stay in sync.
        val webBlockMode = if (webDomains != null && !state.blocksWholeApp) {
            state.webBlockMode.name
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
            // The usage reminder lives on the app-level default rule only, like the time-based
            // kick it is measured beside: the clock is per package, so a copy on a feature or
            // scheduled rule could only ever duplicate this one.
            remindAfterMinutes = UsageReminderInput.resolve(
                state.reminderEnabled,
                state.remindAfterMinutesText,
                state.originalRemindAfterMinutes
            ),
            webDomains = webDomains,
            webBlockMode = webBlockMode,
            // followingSteer is only ever meaningful on the app-level default rule: the steer is
            // consulted while the app is ALLOWED, not as part of a block decision, and the
            // app-level rule is the one that always exists. tabVanish is also set here, but
            // performSave additionally copies it onto every other rule this view model
            // persists -- see the comment there.
            tabVanish = state.tabVanish,
            followingSteer = state.followingSteer,
            // Unlike followingSteer, also copied onto every other rule in performSave: the engine
            // reads it off whichever rule DECIDED the block, exactly like tabVanish.
            keepPassOnShortTrips = state.effectiveKeepPassOnShortTrips,
            // Carried across the delete and re-add in performSave, so an ordinary save does not
            // re-arm the package's rules (RuleArming). The default rule is never a first-unlock
            // rule, so it can only ever keep the stamp it had.
            armedAt = existingDefaultRule?.armedAt ?: RuleArming.NEVER_ARMED
        )
    }

    fun save() {
        viewModelScope.launch {
            val state = _uiState.value
            val newDefault = buildDefaultRule(state)
            val old = existingDefaultRule

            // A save only needs the challenge when it weakens an EXISTING rule. Creating a fresh
            // rule (no prior rule) is strengthening, so it saves freely even under Strict Mode.
            val defaultWeakens = old != null && RuleWeakening.isWeakening(old, newDefault)
            // The scheduled override is a rule in its own right (spec 5.6): shortening its
            // first-unlock window, changing its kind, or turning the override off opens time that
            // was blocked. Judged against the row the save will actually write.
            val newScheduled = buildScheduledRule(state)
            val oldScheduled = existingScheduledRule
            val scheduledWeakens = oldScheduled != null && RuleWeakening.isWeakening(
                oldScheduled,
                newScheduled ?: oldScheduled.copy(enabled = false)
            )
            val weakens = defaultWeakens || scheduledWeakens
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
            val ruleMode = override.mode.toBlockMode()?.name ?: continue
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
                    // The engine reads tabVanish off the rule that DECIDED the block. A feature
                    // override rule for a vanishable feature can be the deciding rule, so it must
                    // carry the same tabVanish value as every other rule for this package -- else
                    // whether the cover shows would depend on which rule happened to win, a coin
                    // flip from the user's point of view.
                    tabVanish = state.tabVanish,
                    // Same reasoning (#110): the deciding rule's value is the one handed to the
                    // grant, so it must not depend on which of this app's rules happened to win.
                    keepPassOnShortTrips = state.effectiveKeepPassOnShortTrips
                )
            )
        }

        // 4. Create scheduled override rules
        val scheduledRule = buildScheduledRule(state)
        // Keep the snapshot in sync so a second save in the same session compares correctly, the
        // same reason [existingDefaultRule] is replaced above.
        existingScheduledRule = scheduledRule
        if (scheduledRule != null) {
            // Scheduled app-level rule. tabVanish carries here too -- same reasoning as the
            // feature override rules above: this rule can be the one that decides a HARD_BLOCK
            // during the scheduled window, so it needs the same flag as every other rule for
            // this package. followingSteer does NOT go here: it belongs on the app-level
            // DEFAULT rule only (see buildDefaultRule), never on a scheduled rule.
            blockRuleRepository.addRule(scheduledRule)

            // Scheduled feature override rules
            for ((featureKey, override) in state.scheduledFeatureOverrides) {
                if (override.mode == FeatureMode.INHERIT) continue
                val ruleMode = override.mode.toBlockMode()?.name ?: continue
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
                        // The schedule is the parent rule's, read off the one construction, so a
                        // feature override can never be in a different window from the rule that
                        // owns it (first-unlock included).
                        scheduleDays = scheduledRule.scheduleDays,
                        scheduleStartMinute = scheduledRule.scheduleStartMinute,
                        scheduleEndMinute = scheduledRule.scheduleEndMinute,
                        firstUnlockMinutes = scheduledRule.firstUnlockMinutes,
                        // Same reasoning as every other rule persisted here: tabVanish must be
                        // uniform across all of this package's rules, since the engine reads it
                        // off whichever rule decides the block.
                        tabVanish = state.tabVanish,
                        keepPassOnShortTrips = state.effectiveKeepPassOnShortTrips
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

    /**
     * Mode the configured websites block with. Only reachable (and only persisted) while
     * whole-app blocking is off — with it on, websites follow the app's own mode.
     */
    fun setWebBlockMode(mode: BlockMode) {
        if (mode == BlockMode.NONE) return
        _uiState.value = _uiState.value.copy(webBlockMode = mode)
    }

    // ═══ Default behavior ═══

    fun setDefaultMode(mode: BlockMode) {
        val current = _uiState.value
        _uiState.value = current.copy(
            defaultMode = mode,
            // Remember the last real blocking choice so toggling whole-app blocking off and back
            // on restores it instead of snapping to DELAY.
            lastBlockingMode = if (mode == BlockMode.NONE) current.lastBlockingMode else mode,
            // Websites inherit the app's mode while the app is blocked, so keep the web picker on
            // the same value: switching whole-app blocking off afterwards must not silently
            // change what the websites do.
            webBlockMode = if (mode == BlockMode.NONE) current.webBlockMode else mode
        )
    }

    /**
     * Turn whole-app blocking on/off. Off writes [BlockMode.NONE] on the app-level rule, which
     * keeps the daily limit, counter, overlays AND web-domain blocking intact while letting the
     * app itself open — the combination needed to block only Shorts/Reels, or to block a site
     * without blocking its app (issue #21). Websites then enforce at [UnifiedAppConfigState.webBlockMode],
     * persisted on the rule. Grayscale does NOT survive the switch; see [BlockMode.NONE].
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

    fun setReminderEnabled(enabled: Boolean) {
        val state = _uiState.value
        _uiState.value = state.copy(
            reminderEnabled = enabled,
            remindAfterMinutesText = UsageReminderInput.textAfterToggle(enabled, state.remindAfterMinutesText)
        )
    }

    fun setRemindAfterMinutesText(text: String) {
        _uiState.value = _uiState.value.copy(remindAfterMinutesText = text)
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
        refreshFirstUnlockHints()
    }

    fun setScheduledOverrideFirstUnlock(firstUnlock: Boolean) {
        _uiState.value = _uiState.value.copy(scheduledOverrideFirstUnlock = firstUnlock)
        refreshFirstUnlockHints()
    }

    fun setFirstUnlockMinutes(minutes: Int) {
        _uiState.value = _uiState.value.copy(firstUnlockMinutes = minutes)
    }

    /**
     * "New day starts at". One global setting, written through the use case that owns the Strict
     * Mode question: moving it can push today's unlock out of an enabled first-unlock rule's
     * logical day, which is weakening (spec 5.6).
     */
    fun setDayStartMinute(minute: Int) {
        viewModelScope.launch {
            val write: suspend () -> Unit = {
                setDayStartMinuteUseCase.execute(minute)
                logicalDayStartMinute = minute
                refreshFirstUnlockHints()
            }
            if (setDayStartMinuteUseCase.requiresUnlock(minute)) {
                strictModeGate.run(prompt = "Change when your day starts", action = write)
            } else {
                write()
            }
        }
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

    // ═══ Platform surface capabilities ═══

    fun setTabVanish(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(tabVanish = enabled)
    }

    fun setFollowingSteer(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(followingSteer = enabled)
    }

    /**
     * "Keep the pass on short trips" (#110). Turning it ON under Strict Mode is a weakening
     * ([com.astraedus.nudge.domain.lock.RuleWeakening]), gated at [save] like every other axis.
     */
    fun setKeepPassOnShortTrips(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(keepPassOnShortTrips = enabled)
    }
}
