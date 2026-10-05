package com.astraedus.nudge.ui.redirect

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.RedirectAppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The redirect app's UI state, shared by the block overlay (the bubble) and Settings (the row), so
 * both surfaces open the same picker over the same list and save through the same refusal.
 *
 * Plain state holder, not a ViewModel: the overlay builds one per block delivery (with the block's
 * own packages excluded and the current choice already resolved, so the bubble is right on its
 * first frame), Settings keeps one in its ViewModel.
 *
 * @param blockPackages the packages of the block on screen, never offered and never accepted.
 */
@Stable
class RedirectAppController(
    private val repository: RedirectAppRepository,
    private val scope: CoroutineScope,
    private val blockPackages: Set<String> = emptySet(),
    initialTarget: InstalledAppsRepository.AppInfo? = null
) {
    /** What the bubble shows; null is the empty state. */
    var target: InstalledAppsRepository.AppInfo? by mutableStateOf(initialTarget)
        private set

    var pickerOpen: Boolean by mutableStateOf(false)
        private set

    /** The picker's rows; null while loading. */
    var candidates: List<InstalledAppsRepository.AppInfo>? by mutableStateOf(null)
        private set

    private var loadJob: Job? = null

    fun openPicker() {
        pickerOpen = true
        loadJob?.cancel()
        loadJob = scope.launch { candidates = repository.candidates(blockPackages) }
    }

    fun dismissPicker() {
        pickerOpen = false
    }

    fun choose(packageName: String) {
        pickerOpen = false
        scope.launch {
            repository.choose(packageName, blockPackages)
            target = repository.current(blockPackages)
        }
    }

    fun remove() {
        pickerOpen = false
        target = null
        scope.launch { repository.clear() }
    }

    /** Re-read the saved choice (Settings, on entry). */
    fun refresh() {
        scope.launch { target = repository.current(blockPackages) }
    }
}
