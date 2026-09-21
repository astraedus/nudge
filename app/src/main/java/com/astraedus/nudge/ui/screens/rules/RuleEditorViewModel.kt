package com.astraedus.nudge.ui.screens.rules

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.service.InAppDetector
import com.astraedus.nudge.ui.components.DurationInput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import androidx.compose.runtime.Immutable
import javax.inject.Inject

@Immutable
data class RuleSummary(
    val id: Long,
    val mode: String,
    val enabled: Boolean,
    val description: String  // e.g. "Whole app: Delay 15s", "Shorts: Hard Block"
)

@Immutable
data class RuleEditorUiState(
    val packageName: String = "",
    val blockMode: BlockMode = BlockMode.DELAY,
    val delaySeconds: Int = 15,
    val dailyLimitEnabled: Boolean = false,
    val dailyLimitMinutes: Int = 30,
    val existingRuleId: Long? = null,
    val isSaved: Boolean = false,
    val isDeleted: Boolean = false,
    // Schedule fields
    val scheduleDays: Set<Int> = emptySet(),     // 1=Mon..7=Sun
    val scheduleStartHour: Int = 9,
    val scheduleStartMinuteOfHour: Int = 0,
    val scheduleEndHour: Int = 17,
    val scheduleEndMinuteOfHour: Int = 0,
    val scheduleEnabled: Boolean = false,
    // In-app feature blocking
    val inAppReels: Boolean = false,
    val inAppShorts: Boolean = false,
    val inAppExplore: Boolean = false,
    val inAppTikTokFeed: Boolean = false,
    val supportsInAppBlocking: Boolean = false,
    // Grayscale
    val grayscale: Boolean = false,
    // Interaction counter
    val showCounter: Boolean = true,
    // Auto-kick after N scrolls/taps
    val autoKickEnabled: Boolean = false,
    val autoKickAfter: Int = 30,
    val autoKickByInteractions: Boolean = true,
    // Auto-kick after N minutes of foreground time (free-form text, converted at save time)
    val autoKickAfterMinutesText: String = "",
    // Auto-kick cooldown duration, as user-facing minutes text (converted at save time)
    val autoKickCooldownMinutesText: String = "1",
    // Values as loaded from the DB, so an untouched field re-saves byte-identical (see
    // DurationInput.resolveCooldownSeconds/resolveMinutes) instead of silently rewriting an
    // off-grid value (e.g. a 150s cooldown from the old slider) to the nearest displayable minute.
    val originalAutoKickCooldownSeconds: Int = 60,
    val originalAutoKickAfterMinutes: Int? = null,
    // Show time remaining overlay
    val showTimeRemaining: Boolean = false,
    // Carried through untouched: this editor has no web-domain UI, but the rule it saves replaces
    // the loaded one wholesale, so not round-tripping this silently wiped an app's web blocking.
    val webDomains: String? = null,
    // Carried through for the same reason as [webDomains]: it is what those domains block with
    // (null = inherit the app-level mode), so dropping it here would silently downgrade a
    // website-only block back to "enforces nothing" whenever this editor saved.
    val webBlockMode: String? = null,
    // All rules for this package (for summary display)
    val allRulesForPackage: List<RuleSummary> = emptyList()
)

@HiltViewModel
class RuleEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val blockRuleRepository: BlockRuleRepository
) : ViewModel() {

    private val packageName: String = savedStateHandle.get<String>("packageName") ?: ""
    private val routeRuleId: Long = savedStateHandle.get<Long>("ruleId") ?: -1L
    private val requestedRuleId: Long? = routeRuleId.takeIf { it > 0L }
    private val isCreatingNewRule: Boolean = routeRuleId == 0L

    private val _uiState = MutableStateFlow(
        RuleEditorUiState(
            packageName = packageName,
            supportsInAppBlocking = packageName in InAppDetector.SUPPORTED_PACKAGES
        )
    )
    val uiState: StateFlow<RuleEditorUiState> = _uiState.asStateFlow()

    init {
        loadExistingRule()
        loadAllRulesForPackage()
    }

    private fun loadExistingRule() {
        if (isCreatingNewRule) return

        viewModelScope.launch {
            val rules = blockRuleRepository.getAllRules().firstOrNull() ?: emptyList()
            val existing = rules.find { it.id == requestedRuleId }
                ?: rules.find { it.packageName == packageName }
            if (existing != null) {
                val days = existing.scheduleDays
                    ?.split(",")
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.toSet()
                    ?: emptySet()

                val features = existing.inAppFeatures
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()

                val hasSchedule = existing.scheduleStartMinute != null && existing.scheduleEndMinute != null
                val startMinute = existing.scheduleStartMinute ?: 540  // 9:00 AM
                val endMinute = existing.scheduleEndMinute ?: 1020     // 5:00 PM

                _uiState.value = _uiState.value.copy(
                    blockMode = try {
                        BlockMode.valueOf(existing.mode)
                    } catch (_: IllegalArgumentException) {
                        BlockMode.DELAY
                    },
                    delaySeconds = existing.delaySeconds,
                    dailyLimitEnabled = existing.dailyLimitMinutes != null,
                    dailyLimitMinutes = existing.dailyLimitMinutes ?: 30,
                    existingRuleId = existing.id,
                    scheduleDays = days,
                    scheduleStartHour = startMinute / 60,
                    scheduleStartMinuteOfHour = startMinute % 60,
                    scheduleEndHour = endMinute / 60,
                    scheduleEndMinuteOfHour = endMinute % 60,
                    scheduleEnabled = hasSchedule || days.isNotEmpty(),
                    inAppReels = "REELS" in features,
                    inAppShorts = "SHORTS" in features,
                    inAppExplore = "EXPLORE" in features,
                    inAppTikTokFeed = "TIKTOK_FEED" in features,
                    grayscale = existing.grayscale,
                    showCounter = existing.showCounter,
                    autoKickEnabled = existing.autoKickAfter != null || existing.autoKickAfterMinutes != null,
                    autoKickByInteractions = existing.autoKickAfter != null,
                    autoKickAfter = existing.autoKickAfter ?: 30,
                    autoKickAfterMinutesText = DurationInput.minutesToText(existing.autoKickAfterMinutes),
                    autoKickCooldownMinutesText = DurationInput.cooldownSecondsToText(existing.autoKickCooldownSeconds),
                    originalAutoKickCooldownSeconds = existing.autoKickCooldownSeconds,
                    originalAutoKickAfterMinutes = existing.autoKickAfterMinutes,
                    showTimeRemaining = existing.showTimeRemaining,
                    webDomains = existing.webDomains,
                    webBlockMode = existing.webBlockMode
                )
            }
        }
    }

    private fun loadAllRulesForPackage() {
        viewModelScope.launch {
            blockRuleRepository.getRulesForPackage(packageName).collect { rules ->
                val summaries = rules.map { rule ->
                    val modeLabel = when (rule.mode) {
                        "HARD_BLOCK" -> "Hard Block"
                        "DELAY" -> "Delay ${rule.delaySeconds}s"
                        "HOLD" -> "Hold ${rule.delaySeconds}s"
                        "BREATHING" -> "Breathing ${rule.delaySeconds}s"
                        else -> rule.mode
                    }
                    val features = rule.inAppFeatures?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                    val featureLabel = if (features.isNotEmpty()) {
                        features.joinToString(", ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
                    } else "Whole app"

                    val extras = buildList {
                        if (rule.dailyLimitMinutes != null) add("${rule.dailyLimitMinutes}min/day limit")
                        if (rule.scheduleDays != null) add("Scheduled")
                        if (rule.grayscale) add("Grayscale")
                        if (rule.showCounter) add("Counter")
                        if (rule.autoKickAfter != null) add("Auto-kick@${rule.autoKickAfter}")
                        if (rule.autoKickAfterMinutes != null) add("Auto-kick@${rule.autoKickAfterMinutes}min")
                        if (rule.showTimeRemaining) add("Time remaining")
                    }
                    val extraStr = if (extras.isNotEmpty()) " + ${extras.joinToString(", ")}" else ""

                    RuleSummary(
                        id = rule.id,
                        mode = rule.mode,
                        enabled = rule.enabled,
                        description = "$featureLabel: $modeLabel$extraStr"
                    )
                }
                _uiState.value = _uiState.value.copy(allRulesForPackage = summaries)
            }
        }
    }

    fun toggleRuleEnabled(ruleId: Long, currentlyEnabled: Boolean) {
        viewModelScope.launch {
            val rules = blockRuleRepository.getAllRules().firstOrNull() ?: return@launch
            val rule = rules.find { it.id == ruleId } ?: return@launch
            blockRuleRepository.updateRule(rule.copy(enabled = !currentlyEnabled))
        }
    }

    fun deleteRuleById(ruleId: Long) {
        viewModelScope.launch {
            blockRuleRepository.deleteRule(ruleId)
        }
    }

    fun setBlockMode(mode: BlockMode) {
        _uiState.value = _uiState.value.copy(blockMode = mode)
    }

    fun setDelaySeconds(seconds: Int) {
        _uiState.value = _uiState.value.copy(delaySeconds = seconds)
    }

    fun setDailyLimitEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(dailyLimitEnabled = enabled)
    }

    fun setDailyLimitMinutes(minutes: Int) {
        _uiState.value = _uiState.value.copy(dailyLimitMinutes = minutes)
    }

    // --- Schedule ---

    fun setScheduleEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(scheduleEnabled = enabled)
    }

    fun toggleScheduleDay(day: Int) {
        val current = _uiState.value.scheduleDays
        val updated = if (day in current) current - day else current + day
        _uiState.value = _uiState.value.copy(scheduleDays = updated)
    }

    fun setScheduleStartTime(hour: Int, minute: Int) {
        _uiState.value = _uiState.value.copy(
            scheduleStartHour = hour,
            scheduleStartMinuteOfHour = minute
        )
    }

    fun setScheduleEndTime(hour: Int, minute: Int) {
        _uiState.value = _uiState.value.copy(
            scheduleEndHour = hour,
            scheduleEndMinuteOfHour = minute
        )
    }

    // --- In-app features ---

    fun setInAppReels(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(inAppReels = enabled)
    }

    fun setInAppShorts(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(inAppShorts = enabled)
    }

    fun setInAppExplore(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(inAppExplore = enabled)
    }

    fun setInAppTikTokFeed(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(inAppTikTokFeed = enabled)
    }

    // --- Grayscale ---

    fun setGrayscale(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(grayscale = enabled)
    }

    // --- Interaction counter ---

    fun setShowCounter(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(showCounter = enabled)
    }

    // --- Auto-kick ---

    fun setAutoKickEnabled(enabled: Boolean) {
        val state = _uiState.value
        // Switching the section on when neither trigger is configured would be a toggle that
        // silently does nothing. Fall back to the interaction trigger -- which is exactly what
        // this switch meant before the time trigger existed.
        val noTriggerConfigured = !state.autoKickByInteractions &&
            DurationInput.parseMinutes(state.autoKickAfterMinutesText) == null
        _uiState.value = state.copy(
            autoKickEnabled = enabled,
            autoKickByInteractions = state.autoKickByInteractions ||
                (enabled && noTriggerConfigured)
        )
    }

    fun setAutoKickAfter(count: Int) {
        _uiState.value = _uiState.value.copy(autoKickAfter = count)
    }

    fun setAutoKickByInteractions(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autoKickByInteractions = enabled)
    }

    fun setAutoKickAfterMinutesText(text: String) {
        _uiState.value = _uiState.value.copy(autoKickAfterMinutesText = text)
    }

    fun setAutoKickCooldownMinutesText(text: String) {
        _uiState.value = _uiState.value.copy(autoKickCooldownMinutesText = text)
    }

    // --- Show time remaining ---

    fun setShowTimeRemaining(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(showTimeRemaining = enabled)
    }

    // --- Save / Delete ---

    fun save() {
        viewModelScope.launch {
            val state = _uiState.value
            val rule = buildRule(state)
            if (state.existingRuleId != null) {
                blockRuleRepository.updateRule(rule)
            } else {
                blockRuleRepository.addRule(rule)
            }
            _uiState.value = _uiState.value.copy(isSaved = true)
        }
    }

    fun delete() {
        viewModelScope.launch {
            val ruleId = _uiState.value.existingRuleId ?: return@launch
            blockRuleRepository.deleteRule(ruleId)
            _uiState.value = _uiState.value.copy(isDeleted = true)
        }
    }

    companion object {
        /**
         * Pure editor-state -> entity mapping. Extracted from [save] so the save CONTRACT is
         * unit-testable on the JVM without a ViewModel, a repository or coroutines — this is where
         * the rules about which auto-kick trigger survives a save live, and they are too easy to
         * get quietly wrong to leave untested.
         *
         * Notable behaviours, all asserted in `RuleEditorRuleBuilderTest`:
         *  - the INTERACTION trigger additionally requires `showCounter`, because the counter
         *    machinery is what feeds it; the TIME trigger does not, since its whole purpose is
         *    passive use with no counter on screen;
         *  - the duration fields go through `DurationInput.resolve*`, so a save that did not touch
         *    them re-persists the exact prior value rather than a display-rounded one;
         *  - `webDomains` and `webBlockMode` are carried through from the loaded rule. This editor
         *    has no web-domain UI, and before they were threaded through here every save from this
         *    screen silently wiped an app's web blocking (or, for `webBlockMode`, downgraded a
         *    website-only block to one that enforces nothing).
         */
        internal fun buildRule(state: RuleEditorUiState): BlockRule {
            val scheduleDaysStr = if (state.scheduleEnabled && state.scheduleDays.isNotEmpty()) {
                state.scheduleDays.sorted().joinToString(",")
            } else null

            val scheduleStartMinute = if (state.scheduleEnabled) {
                state.scheduleStartHour * 60 + state.scheduleStartMinuteOfHour
            } else null

            val scheduleEndMinute = if (state.scheduleEnabled) {
                state.scheduleEndHour * 60 + state.scheduleEndMinuteOfHour
            } else null

            val features = buildList {
                if (state.inAppReels) add("REELS")
                if (state.inAppShorts) add("SHORTS")
                if (state.inAppExplore) add("EXPLORE")
                if (state.inAppTikTokFeed) add("TIKTOK_FEED")
            }
            val inAppFeaturesStr = if (features.isNotEmpty()) features.joinToString(",") else null

            return BlockRule(
                id = state.existingRuleId ?: 0,
                packageName = state.packageName,
                mode = state.blockMode.name,
                delaySeconds = state.delaySeconds,
                dailyLimitMinutes = if (state.dailyLimitEnabled) state.dailyLimitMinutes else null,
                enabled = true,
                scheduleDays = scheduleDaysStr,
                scheduleStartMinute = scheduleStartMinute,
                scheduleEndMinute = scheduleEndMinute,
                inAppFeatures = inAppFeaturesStr,
                grayscale = state.grayscale,
                showCounter = state.showCounter,
                autoKickAfter = if (
                    state.autoKickEnabled && state.showCounter && state.autoKickByInteractions
                ) state.autoKickAfter else null,
                showTimeRemaining = state.showTimeRemaining && state.dailyLimitEnabled,
                // Turning auto-kick off keeps the stored cooldown rather than snapping it back to
                // the 60s default: the value is inert while there is nothing to kick, and
                // rewriting it would both lose the user's setting and register as a protection
                // weakening (see RuleWeakening) the next time the rule is compared.
                autoKickCooldownSeconds = if (state.autoKickEnabled) {
                    DurationInput.resolveCooldownSeconds(
                        state.autoKickCooldownMinutesText,
                        state.originalAutoKickCooldownSeconds
                    )
                } else state.originalAutoKickCooldownSeconds,
                webDomains = state.webDomains,
                webBlockMode = state.webBlockMode,
                autoKickAfterMinutes = if (state.autoKickEnabled) {
                    DurationInput.resolveMinutes(
                        state.autoKickAfterMinutesText,
                        state.originalAutoKickAfterMinutes
                    )
                } else null
            )
        }
    }
}
