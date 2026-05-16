package com.astraedus.nudge.ui.screens.rules

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.service.InAppDetector
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
    // Auto-kick cooldown duration (seconds)
    val autoKickCooldownSeconds: Int = 60,
    // Show time remaining overlay
    val showTimeRemaining: Boolean = false,
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
                    autoKickEnabled = existing.autoKickAfter != null,
                    autoKickAfter = existing.autoKickAfter ?: 30,
                    autoKickCooldownSeconds = existing.autoKickCooldownSeconds,
                    showTimeRemaining = existing.showTimeRemaining
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
        _uiState.value = _uiState.value.copy(autoKickEnabled = enabled)
    }

    fun setAutoKickAfter(count: Int) {
        _uiState.value = _uiState.value.copy(autoKickAfter = count)
    }

    fun setAutoKickCooldownSeconds(seconds: Int) {
        _uiState.value = _uiState.value.copy(autoKickCooldownSeconds = seconds)
    }

    // --- Show time remaining ---

    fun setShowTimeRemaining(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(showTimeRemaining = enabled)
    }

    // --- Save / Delete ---

    fun save() {
        viewModelScope.launch {
            val state = _uiState.value

            // Build schedule fields
            val scheduleDaysStr = if (state.scheduleEnabled && state.scheduleDays.isNotEmpty()) {
                state.scheduleDays.sorted().joinToString(",")
            } else null

            val scheduleStartMinute = if (state.scheduleEnabled) {
                state.scheduleStartHour * 60 + state.scheduleStartMinuteOfHour
            } else null

            val scheduleEndMinute = if (state.scheduleEnabled) {
                state.scheduleEndHour * 60 + state.scheduleEndMinuteOfHour
            } else null

            // Build in-app features string
            val features = buildList {
                if (state.inAppReels) add("REELS")
                if (state.inAppShorts) add("SHORTS")
                if (state.inAppExplore) add("EXPLORE")
                if (state.inAppTikTokFeed) add("TIKTOK_FEED")
            }
            val inAppFeaturesStr = if (features.isNotEmpty()) features.joinToString(",") else null

            val rule = BlockRule(
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
                autoKickAfter = if (state.showCounter && state.autoKickEnabled) state.autoKickAfter else null,
                showTimeRemaining = state.showTimeRemaining && state.dailyLimitEnabled,
                autoKickCooldownSeconds = if (state.showCounter && state.autoKickEnabled)
                    state.autoKickCooldownSeconds else 60
            )
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
}
