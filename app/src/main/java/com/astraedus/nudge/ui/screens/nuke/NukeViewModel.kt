package com.astraedus.nudge.ui.screens.nuke

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.di.IoDispatcher
import com.astraedus.nudge.domain.nuke.NukeKey
import com.astraedus.nudge.domain.nuke.NukeKeyKind
import com.astraedus.nudge.domain.nuke.NukePolicy
import com.astraedus.nudge.domain.nuke.NukeScan
import com.astraedus.nudge.domain.nuke.NukeState
import com.astraedus.nudge.service.EssentialApps
import com.astraedus.nudge.ui.nuke.NukeGate
import com.astraedus.nudge.ui.nuke.NukeUnlockState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One row of the Nuke picker. */
@Immutable
data class NukeAppRow(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val nuked: Boolean
)

@Immutable
data class NukeUiState(
    val loading: Boolean = true,
    /** Nuke is on right now. */
    val active: Boolean = false,
    /** A key is paired, and how (display only). */
    val hasKey: Boolean = false,
    val keyKind: NukeKeyKind? = null,
    /** Apps Nuke will actually block (safety floor already removed). */
    val nukedCount: Int = 0,
    /** Why "Nuke now" / scan-to-start cannot arm yet, or null when it can. */
    val armBlocker: NukePolicy.ArmBlocker? = null,
    /** The picker rows, filtered by [query]. Nuked apps first, then alphabetical. */
    val apps: List<NukeAppRow> = emptyList(),
    val query: String = "",
    /** Whether the one-time explainer has been dismissed. */
    val introSeen: Boolean = true
) {
    val canArm: Boolean get() = !active && armBlocker == null
}

/** What a scan the screen launches is FOR. The same camera, four different meanings. */
enum class NukeScanPurpose {
    /** "Scan to start / end Nuke": the paired key toggles it. */
    TOGGLE,

    /** Confirming a freshly generated QR was saved: must match the token on screen. */
    CONFIRM_NEW_QR,

    /** "Use a code I already have": whatever is scanned becomes the key. */
    PAIR_EXISTING
}

/** The pairing flow's state. */
sealed interface NukePairing {
    data object Idle : NukePairing

    /**
     * A new key has been generated and is on screen as a QR. It is NOT saved yet: the user must scan
     * it back once, which proves they printed/saved/sent it somewhere a camera can see. Pairing a
     * code they never kept would leave the emergency code as the only way out.
     *
     * The token lives only here, in memory, for the life of this screen. It is never persisted.
     */
    data class ShowingNewQr(val token: String) : NukePairing {
        /** Never print the key: a stray log line of this state would be a copy of the way out. */
        override fun toString(): String = "ShowingNewQr(token=<redacted>)"
    }
}

/**
 * The Nuke screen: the list, the key, and turning it on and off.
 *
 * Every weakening path goes through [gate], with [NukePolicy.isWeakening] deciding which ones do;
 * strengthening (arming, adding apps, pairing the FIRST key) is free. The camera is launched by the
 * screen; results come back through [onScanResult] tagged with what the scan was for.
 */
@HiltViewModel
class NukeViewModel @Inject constructor(
    private val preferences: NudgePreferences,
    private val installedAppsRepository: InstalledAppsRepository,
    private val essentialApps: EssentialApps,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    /** The one weakening gate for this screen. Reads the LIVE state at unlock time. */
    val gate = NukeGate(currentState = { preferences.nukeState.first() })
    val unlock: StateFlow<NukeUnlockState?> = gate.unlock

    private val query = MutableStateFlow("")
    private val installed = MutableStateFlow<List<InstalledAppsRepository.AppInfo>?>(null)
    private val deviceFloor = MutableStateFlow<Set<String>>(emptySet())

    private val _pairing = MutableStateFlow<NukePairing>(NukePairing.Idle)
    val pairing: StateFlow<NukePairing> = _pairing.asStateFlow()

    /** A scan the VM wants the screen to launch (after an unlock, say). Consumed by the screen. */
    private val _scanRequest = MutableStateFlow<NukeScanPurpose?>(null)
    val scanRequest: StateFlow<NukeScanPurpose?> = _scanRequest.asStateFlow()

    /** One short line for a snackbar. Consumed by the screen. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val uiState: StateFlow<NukeUiState> = combine(
        preferences.nukeState,
        preferences.nukeIntroSeen,
        installed,
        deviceFloor,
        query
    ) { state, introSeen, apps, floor, q ->
        val rows = (apps ?: emptyList())
            .filter { !NukePolicy.isProtected(it.packageName, floor) }
            .map { NukeAppRow(it.packageName, it.appName, it.icon, it.packageName in state.packages) }
            .filter {
                q.isBlank() || it.label.contains(q, ignoreCase = true) ||
                    it.packageName.contains(q, ignoreCase = true)
            }
            .sortedWith(compareByDescending<NukeAppRow> { it.nuked }.thenBy { it.label.lowercase() })
        NukeUiState(
            loading = apps == null,
            active = state.active,
            hasKey = state.hasKey,
            keyKind = state.keyKind,
            nukedCount = NukePolicy.enforceable(state.packages, floor).size,
            armBlocker = NukePolicy.armBlocker(state, floor),
            apps = rows,
            query = q,
            introSeen = introSeen
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NukeUiState())

    init {
        viewModelScope.launch {
            installed.value = installedAppsRepository.getInstalledApps()
        }
        viewModelScope.launch {
            deviceFloor.value = withContext(ioDispatcher) { essentialApps.packages() }
        }
    }

    private suspend fun state(): NukeState = preferences.nukeState.first()

    fun setQuery(value: String) {
        query.value = value
    }

    fun dismissIntro() {
        viewModelScope.launch { preferences.setNukeIntroSeen() }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun consumeScanRequest() {
        _scanRequest.value = null
    }

    /** Ask the screen to open the camera for [purpose]. */
    fun requestScan(purpose: NukeScanPurpose) {
        _scanRequest.value = purpose
    }

    // --- the list ------------------------------------------------------------------------------

    /** Add or remove one app. Adding is always free; removing while Nuke is on needs the key. */
    fun toggleApp(packageName: String) {
        viewModelScope.launch {
            val current = state()
            if (packageName in current.packages) {
                val proposed = current.copy(packages = current.packages - packageName)
                gate.run("Take this app off your Nuke list", NukePolicy.isWeakening(current, proposed)) {
                    preferences.removeNukePackages(listOf(packageName))
                }
            } else {
                preferences.addNukePackages(listOf(packageName))
            }
        }
    }

    // --- on and off ----------------------------------------------------------------------------

    /** "Nuke now": turning it ON is strengthening, so no key is needed, only a key to exist. */
    fun nukeNow() {
        viewModelScope.launch {
            when (NukePolicy.armBlocker(state(), deviceFloor.value)) {
                null -> {
                    preferences.setNukeActive(true)
                    _message.value = ARMED_MESSAGE
                }
                NukePolicy.ArmBlocker.NO_KEY -> _message.value = NO_KEY_MESSAGE
                NukePolicy.ArmBlocker.EMPTY_LIST -> _message.value = EMPTY_LIST_MESSAGE
            }
        }
    }

    /** "End Nuke" with the choice of scanning or typing. */
    fun endNuke() {
        viewModelScope.launch {
            val current = state()
            gate.run("End Nuke", NukePolicy.isWeakening(current, current.copy(active = false))) {
                preferences.setNukeActive(false)
                _message.value = DISARMED_MESSAGE
            }
        }
    }

    /** "Emergency: end without your code" -- straight to the 64-character code. */
    fun endWithEmergencyCode() {
        gate.requireEmergencyCode("End Nuke without your code") {
            preferences.setNukeActive(false)
            _message.value = DISARMED_MESSAGE
        }
    }

    // --- the key -------------------------------------------------------------------------------

    /**
     * Start pairing a NEW generated QR. While Nuke is on, replacing the key is a new way out, so it
     * costs the old key (or the emergency code) first.
     */
    fun startCreateQr() {
        viewModelScope.launch {
            val current = state()
            gate.run("Replace your Nuke code", weaken = current.active && current.hasKey) {
                _pairing.value = NukePairing.ShowingNewQr(NukeKey.generateToken())
            }
        }
    }

    /** Start pairing an existing barcode/QR. Same gate as [startCreateQr]. */
    fun startPairExisting() {
        viewModelScope.launch {
            val current = state()
            gate.run("Replace your Nuke code", weaken = current.active && current.hasKey) {
                _scanRequest.value = NukeScanPurpose.PAIR_EXISTING
            }
        }
    }

    fun cancelPairing() {
        _pairing.value = NukePairing.Idle
    }

    /** Remove the key. Ends Nuke too (no key, no Nuke), so while on it costs the key. */
    fun unpair() {
        viewModelScope.launch {
            val current = state()
            gate.run("Remove your Nuke code", NukePolicy.isWeakening(current, current.copy(keyHash = null, active = false))) {
                preferences.clearNukeKey()
                _pairing.value = NukePairing.Idle
                _message.value = "Nuke code removed."
            }
        }
    }

    // --- the unlock dialog (see [com.astraedus.nudge.ui.nuke.NukeUnlockHost]) ----------------

    fun onUnlockScanned(payload: String?) {
        viewModelScope.launch { gate.onScanned(payload) }
    }

    fun useEmergencyCode() = gate.useEmergencyCode()

    fun verifyEmergency(input: String) {
        viewModelScope.launch { gate.verifyEmergency(input) }
    }

    fun backToUnlockChoice() = gate.backToChoice()

    fun cancelUnlock() = gate.cancel()

    // --- scans ---------------------------------------------------------------------------------

    /**
     * A scan the screen launched came back. [payload] is null when the scanner was cancelled, the
     * camera was denied, or there is no camera; that is never an error and never changes anything.
     */
    fun onScanResult(purpose: NukeScanPurpose, payload: String?) {
        viewModelScope.launch {
            when (purpose) {
                NukeScanPurpose.TOGGLE -> onToggleScan(payload)
                NukeScanPurpose.CONFIRM_NEW_QR -> onConfirmNewQr(payload)
                NukeScanPurpose.PAIR_EXISTING -> onPairExisting(payload)
            }
        }
    }

    private suspend fun onToggleScan(payload: String?) {
        when (val outcome = NukeScan.resolveToggle(payload, state(), deviceFloor.value)) {
            NukeScan.ToggleOutcome.Arm -> {
                preferences.setNukeActive(true)
                _message.value = ARMED_MESSAGE
            }
            NukeScan.ToggleOutcome.Disarm -> {
                preferences.setNukeActive(false)
                _message.value = DISARMED_MESSAGE
            }
            NukeScan.ToggleOutcome.WrongKey -> _message.value = NukeGate.WRONG_KEY_MESSAGE
            NukeScan.ToggleOutcome.NoKey -> _message.value = NO_KEY_MESSAGE
            is NukeScan.ToggleOutcome.CannotArm -> _message.value = when (outcome.reason) {
                NukePolicy.ArmBlocker.NO_KEY -> NO_KEY_MESSAGE
                NukePolicy.ArmBlocker.EMPTY_LIST -> EMPTY_LIST_MESSAGE
            }
            NukeScan.ToggleOutcome.Cancelled -> Unit
        }
    }

    private suspend fun onConfirmNewQr(payload: String?) {
        val pairing = _pairing.value as? NukePairing.ShowingNewQr ?: return
        if (payload.isNullOrBlank()) return
        if (NukeKey.normalize(payload) != pairing.token) {
            _message.value = "That's not the code on screen. Scan the one you just saved."
            return
        }
        preferences.setNukeKey(NukeKey.hashOrNull(pairing.token)!!, NukeKeyKind.GENERATED_QR)
        _pairing.value = NukePairing.Idle
        _message.value = PAIRED_MESSAGE
    }

    private suspend fun onPairExisting(payload: String?) {
        val hash = NukeKey.hashOrNull(payload) ?: return
        preferences.setNukeKey(hash, NukeKeyKind.EXISTING_CODE)
        _pairing.value = NukePairing.Idle
        _message.value = PAIRED_MESSAGE
    }

    companion object {
        const val ARMED_MESSAGE = "Nuke is on."
        const val DISARMED_MESSAGE = "Nuke is off."
        const val NO_KEY_MESSAGE = "Pair a Nuke code first."
        const val EMPTY_LIST_MESSAGE = "Add at least one app to your Nuke list first."
        const val PAIRED_MESSAGE = "Paired. Keep that code somewhere inconvenient."
    }
}
