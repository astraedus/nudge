package com.astraedus.nudge.ui.screens.nuke

import com.astraedus.nudge.MainDispatcherRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.domain.nuke.NukeKey
import com.astraedus.nudge.domain.nuke.NukeKeyKind
import com.astraedus.nudge.domain.nuke.NukeState
import com.astraedus.nudge.service.EssentialApps
import com.astraedus.nudge.ui.nuke.NukeGate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The Nuke screen's decisions, driven through the REAL ViewModel against an in-memory stand-in for
 * the preferences (the same writes DataStore would take, including its one invariant: never on
 * without a key).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NukeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val instagram = "com.instagram.android"
    private val token = NukeKey.generateToken()

    private val stored = MutableStateFlow(NukeState())
    private val introSeen = MutableStateFlow(true)
    private var globalEnabledWrites = 0

    private lateinit var preferences: NudgePreferences
    private lateinit var vm: NukeViewModel

    @Before
    fun setUp() {
        preferences = mockk()
        every { preferences.nukeState } returns stored
        every { preferences.nukeIntroSeen } returns introSeen
        coEvery { preferences.setNukeActive(any()) } coAnswers {
            val on = firstArg<Boolean>()
            stored.value = stored.value.copy(active = on && stored.value.hasKey)
        }
        coEvery { preferences.addNukePackages(any()) } coAnswers {
            stored.value = stored.value.copy(packages = stored.value.packages + firstArg<Collection<String>>())
        }
        coEvery { preferences.removeNukePackages(any()) } coAnswers {
            stored.value = stored.value.copy(packages = stored.value.packages - firstArg<Collection<String>>().toSet())
        }
        coEvery { preferences.setNukeKey(any(), any()) } coAnswers {
            stored.value = stored.value.copy(keyHash = firstArg(), keyKind = secondArg())
        }
        coEvery { preferences.clearNukeKey() } coAnswers {
            stored.value = stored.value.copy(keyHash = null, keyKind = null, active = false)
        }
        coEvery { preferences.setNukeIntroSeen() } coAnswers { introSeen.value = true }

        val apps: InstalledAppsRepository = mockk()
        coEvery { apps.getInstalledApps() } returns listOf(
            InstalledAppsRepository.AppInfo(instagram, "Instagram", null),
            InstalledAppsRepository.AppInfo("com.google.android.dialer", "Phone", null),
            InstalledAppsRepository.AppInfo("com.oem.home", "OEM Home", null)
        )
        val floor: EssentialApps = mockk()
        every { floor.packages(any()) } returns setOf("com.oem.home")

        vm = NukeViewModel(preferences, apps, floor, dispatcher)
    }

    private fun paired(active: Boolean = false, packages: Set<String> = setOf(instagram)) {
        stored.value = NukeState(active = active, packages = packages, keyHash = NukeKey.hashOrNull(token))
    }

    // --- arming ---------------------------------------------------------------------------------

    @Test
    fun `Nuke now needs a key`() = runTest {
        stored.value = NukeState(packages = setOf(instagram))
        vm.nukeNow()
        assertFalse(stored.value.active)
        assertEquals(NukeViewModel.NO_KEY_MESSAGE, vm.message.value)
    }

    @Test
    fun `Nuke now needs a non-empty list`() = runTest {
        paired(packages = emptySet())
        vm.nukeNow()
        assertFalse(stored.value.active)
        assertEquals(NukeViewModel.EMPTY_LIST_MESSAGE, vm.message.value)
    }

    @Test
    fun `Nuke now with a key and a list arms without asking for the key`() = runTest {
        paired()
        vm.nukeNow()
        assertTrue(stored.value.active)
        assertNull(vm.unlock.value)
    }

    @Test
    fun `scanning the key arms it, scanning it again disarms it`() = runTest {
        paired()
        vm.onScanResult(NukeScanPurpose.TOGGLE, token)
        assertTrue(stored.value.active)
        vm.onScanResult(NukeScanPurpose.TOGGLE, token)
        assertFalse(stored.value.active)
    }

    @Test
    fun `a wrong code does not disarm`() = runTest {
        paired(active = true)
        vm.onScanResult(NukeScanPurpose.TOGGLE, "4006381333931")
        assertTrue(stored.value.active)
        assertEquals(NukeGate.WRONG_KEY_MESSAGE, vm.message.value)
    }

    @Test
    fun `a cancelled scan does nothing`() = runTest {
        paired(active = true)
        vm.onScanResult(NukeScanPurpose.TOGGLE, null)
        assertTrue(stored.value.active)
        assertNull(vm.message.value)
    }

    // --- ending it --------------------------------------------------------------------------------

    @Test
    fun `the emergency code disarms`() = runTest {
        paired(active = true)
        vm.endWithEmergencyCode()
        val code = vm.unlock.value?.emergencyTarget
        assertNotNull(code)
        vm.verifyEmergency("not it")
        assertTrue(stored.value.active)
        vm.verifyEmergency(code!!)
        assertFalse(stored.value.active)
        assertEquals(NukeViewModel.DISARMED_MESSAGE, vm.message.value)
    }

    @Test
    fun `End Nuke asks for the key, and the key ends it`() = runTest {
        paired(active = true)
        vm.endNuke()
        assertTrue(stored.value.active)
        assertNotNull(vm.unlock.value)
        vm.onUnlockScanned(token)
        assertFalse(stored.value.active)
    }

    // --- the list ---------------------------------------------------------------------------------

    @Test
    fun `adding an app is free even while Nuke is on`() = runTest {
        paired(active = true)
        vm.toggleApp("com.reddit.frontpage")
        assertTrue("com.reddit.frontpage" in stored.value.packages)
        assertNull(vm.unlock.value)
    }

    @Test
    fun `removing an app while Nuke is on needs the key`() = runTest {
        paired(active = true)
        vm.toggleApp(instagram)
        assertTrue(instagram in stored.value.packages)
        assertNotNull(vm.unlock.value)
        vm.onUnlockScanned("wrong")
        assertTrue(instagram in stored.value.packages)
        vm.onUnlockScanned(token)
        assertFalse(instagram in stored.value.packages)
    }

    @Test
    fun `removing an app while Nuke is off is free`() = runTest {
        paired(active = false)
        vm.toggleApp(instagram)
        assertFalse(instagram in stored.value.packages)
        assertNull(vm.unlock.value)
    }

    @Test
    fun `the picker never offers the phone, the launcher or this device's floor`() = runTest(dispatcher) {
        val collected = mutableListOf<NukeUiState>()
        val job = backgroundScope.launch { vm.uiState.collect { collected += it } }
        val rows = collected.last().apps.map { it.packageName }
        assertEquals(listOf(instagram), rows)
        job.cancel()
    }

    // --- the key ----------------------------------------------------------------------------------

    @Test
    fun `a generated QR is saved only after the SAME code is scanned back`() = runTest {
        vm.startCreateQr()
        val shown = vm.pairing.value as NukePairing.ShowingNewQr
        assertNull(stored.value.keyHash)

        vm.onScanResult(NukeScanPurpose.CONFIRM_NEW_QR, "4006381333931")
        assertNull("a different code must not pair", stored.value.keyHash)

        vm.onScanResult(NukeScanPurpose.CONFIRM_NEW_QR, shown.token)
        assertEquals(NukeKey.hashOrNull(shown.token), stored.value.keyHash)
        assertEquals(NukeKeyKind.GENERATED_QR, stored.value.keyKind)
        assertEquals(NukePairing.Idle, vm.pairing.value)
    }

    @Test
    fun `the payload is never what gets stored`() = runTest {
        vm.onScanResult(NukeScanPurpose.PAIR_EXISTING, "4006381333931")
        assertEquals(NukeKey.hashOrNull("4006381333931"), stored.value.keyHash)
        assertFalse(stored.value.keyHash!!.contains("4006381333931"))
        assertEquals(NukeKeyKind.EXISTING_CODE, stored.value.keyKind)
    }

    @Test
    fun `replacing the key while Nuke is on needs the old key first`() = runTest {
        paired(active = true)
        vm.startCreateQr()
        assertEquals(NukePairing.Idle, vm.pairing.value)
        assertNotNull(vm.unlock.value)
        vm.onUnlockScanned(token)
        assertTrue(vm.pairing.value is NukePairing.ShowingNewQr)
    }

    @Test
    fun `replacing with an existing barcode while on needs the old key first`() = runTest {
        paired(active = true)
        vm.startPairExisting()
        assertNull(vm.scanRequest.value)
        vm.onUnlockScanned(token)
        assertEquals(NukeScanPurpose.PAIR_EXISTING, vm.scanRequest.value)
    }

    @Test
    fun `pairing the first key is free`() = runTest {
        vm.startPairExisting()
        assertEquals(NukeScanPurpose.PAIR_EXISTING, vm.scanRequest.value)
        assertNull(vm.unlock.value)
    }

    @Test
    fun `removing the key while Nuke is on needs the key, and ends Nuke`() = runTest {
        paired(active = true)
        vm.unpair()
        assertTrue(stored.value.hasKey)
        vm.onUnlockScanned(token)
        assertFalse(stored.value.hasKey)
        assertFalse(stored.value.active)
    }

    @Test
    fun `removing the key while Nuke is off is free`() = runTest {
        paired(active = false)
        vm.unpair()
        assertFalse(stored.value.hasKey)
        assertNull(vm.unlock.value)
        assertEquals(0, globalEnabledWrites)
    }
}
