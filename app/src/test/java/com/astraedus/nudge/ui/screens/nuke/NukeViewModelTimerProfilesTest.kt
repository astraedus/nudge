package com.astraedus.nudge.ui.screens.nuke

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.astraedus.nudge.MainDispatcherRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.nuke.NukeKey
import com.astraedus.nudge.domain.nuke.NukeKeyKind
import com.astraedus.nudge.domain.nuke.NukePolicy
import com.astraedus.nudge.domain.nuke.NukeProfile
import com.astraedus.nudge.domain.nuke.NukeState
import com.astraedus.nudge.service.EssentialApps
import com.astraedus.nudge.testing.FakeInstalledAppsRepository
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeNukeClock
import com.astraedus.nudge.testing.InMemoryPreferencesDataStore
import com.astraedus.nudge.testing.anApp
import com.astraedus.nudge.ui.nuke.NukeTimerCopy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Profiles (#183) and the timed Nuke (#184) through the REAL ViewModel, the REAL [NukeGate]
 * ([com.astraedus.nudge.ui.nuke.NukeGate]) and the REAL preferences over an in-memory store, with
 * the timer's clock driven by hand. The timer tests walk every key-gated weakening the screen
 * offers, so a new one that skipped the gate's timer check would have to skip this list too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NukeViewModelTimerProfilesTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val minute = 60_000L
    private val instagram = "com.instagram.android"
    private val tiktok = "com.zhiliaoapp.musically"
    private val chrome = "com.android.chrome"
    private val oemHome = "com.oem.home"
    private val token = NukeKey.generateToken()
    private val clock = FakeNukeClock()

    private var preferences: NudgePreferences = FakeNudgePreferences { setNukeIntroSeen() }
    private val vm: NukeViewModel by lazy { viewModel(preferences) }

    private fun viewModel(prefs: NudgePreferences): NukeViewModel {
        val apps = FakeInstalledAppsRepository(
            anApp(instagram, "Instagram"),
            anApp(tiktok, "TikTok"),
            anApp(chrome, "Chrome"),
            anApp(oemHome, "OEM Home")
        )
        val floor: EssentialApps = mockk()
        every { floor.packages(any()) } returns setOf(oemHome)
        return NukeViewModel(prefs, apps, floor, clock, dispatcher)
    }

    private suspend fun state(): NukeState = preferences.nukeState.first()

    private suspend fun paired(packages: Set<String> = setOf(instagram)) {
        preferences.setNukeKey(NukeKey.hashOrNull(token)!!, NukeKeyKind.GENERATED_QR)
        preferences.addNukePackages(packages)
    }

    /** Pick [minutes] and start Nuke with "Nuke now". */
    private suspend fun startWithTimer(minutes: Int) {
        vm.setTimerChoice(minutes)
        vm.nukeNow()
        assertTrue(state().active)
    }

    // --- the timer -------------------------------------------------------------------------------

    @Test
    fun `Nuke now starts the picked timer, and no timer when none is picked`() = runTest {
        paired()
        vm.nukeNow()
        assertNull(state().timer)
        preferences.setNukeActive(false)

        startWithTimer(45)
        assertEquals(45 * minute, state().timer!!.remainingMs(clock.now()))
    }

    @Test
    fun `scanning to start uses the picked timer too`() = runTest {
        paired()
        vm.setTimerChoice(15)
        vm.onScanResult(NukeScanPurpose.TOGGLE, token)
        assertTrue(state().active)
        assertEquals(15 * minute, state().timer!!.durationMs)
    }

    @Test
    fun `scan to end is refused before the end, with the time left, and accepted after`() = runTest {
        paired()
        startWithTimer(30)
        clock.advance(10 * minute)

        vm.onScanResult(NukeScanPurpose.TOGGLE, token)
        assertTrue(state().active)
        assertEquals(NukeTimerCopy.refusal(20 * minute), vm.message.value)

        clock.advance(20 * minute)
        vm.onScanResult(NukeScanPurpose.TOGGLE, token)
        assertFalse(state().active)
        assertNull("ending Nuke clears the timer", state().timer)
    }

    @Test
    fun `the timer ending does not end Nuke by itself`() = runTest {
        paired()
        startWithTimer(15)
        clock.advance(60 * minute)
        assertTrue(state().active)
        assertTrue(NukePolicy.isNuked(instagram, state(), setOf(oemHome)))
    }

    /** Every key-gated weakening on this screen, opened and then scanned with the RIGHT key. */
    @Test
    fun `every key-gated weakening is refused while the timer runs`() = runTest {
        paired(setOf(instagram, tiktok))
        startWithTimer(60)
        val before = state()

        val weakenings: List<Pair<String, () -> Unit>> = listOf(
            "end" to { vm.endNuke() },
            "remove an app" to { vm.toggleApp(instagram) },
            "replace the key with a new QR" to { vm.startCreateQr() },
            "replace the key with a barcode" to { vm.startPairExisting() },
            "remove the key" to { vm.unpair() }
        )
        weakenings.forEach { (name, open) ->
            open()
            assertNotNull("$name must ask for the key", vm.unlock.value)
            vm.onUnlockScanned(token)
            assertEquals("$name: refused with the time left", NukeTimerCopy.refusal(60 * minute), vm.unlock.value?.error)
            vm.cancelUnlock()
        }

        assertEquals("nothing changed", before, state())
        assertEquals(NukePairing.Idle, vm.pairing.value)
        assertNull(vm.scanRequest.value)
    }

    @Test
    fun `adding an app stays free while the timer runs`() = runTest {
        paired()
        startWithTimer(60)
        vm.toggleApp(chrome)
        assertTrue(chrome in state().packages)
        assertNull(vm.unlock.value)
    }

    @Test
    fun `after the timer the key unlocks the same weakenings again`() = runTest {
        paired(setOf(instagram, tiktok))
        startWithTimer(15)
        clock.advance(15 * minute)
        vm.toggleApp(instagram)
        vm.onUnlockScanned(token)
        assertFalse(instagram in state().packages)
        vm.endNuke()
        vm.onUnlockScanned(token)
        assertFalse(state().active)
    }

    @Test
    fun `the emergency code during a timer ends Nuke and clears the timer`() = runTest {
        paired()
        startWithTimer(120)
        vm.endWithEmergencyCode()
        vm.verifyEmergency(vm.unlock.value?.emergencyTarget!!)
        assertFalse(state().active)
        assertNull(state().timer)

        // And from inside the scan-or-type dialog, too.
        startWithTimer(120)
        vm.endNuke()
        vm.onUnlockScanned(token)
        vm.useEmergencyCode()
        vm.verifyEmergency(vm.unlock.value?.emergencyTarget!!)
        assertFalse(state().active)
        assertNull(state().timer)
    }

    @Test
    fun `a reboot carries the banked time forward and never ends the timer early`() = runTest {
        paired()
        startWithTimer(30)
        clock.advance(10 * minute)
        preferences.checkpointNukeTimer(clock.now()) // the screen going off
        clock.reboot(uptimeMs = minute)

        vm.onScanResult(NukeScanPurpose.TOGGLE, token)
        assertTrue(state().active)
        assertEquals(NukeTimerCopy.refusal(19 * minute), vm.message.value)
        val left = state().timer!!.remainingMs(clock.now())
        assertTrue("never more than the duration", left <= 30 * minute)
    }

    /**
     * The timer reads only the monotonic clock: nothing in this test (or the gate) has a wall clock
     * to move. Pinned at the source level by `NukeTimerClockContractTest`; here, moving the system
     * clock's only stand-in (the JVM default time zone, which every wall-clock read would see)
     * changes no answer.
     */
    @Test
    fun `changing the time zone changes nothing the timer decides`() = runTest {
        paired()
        startWithTimer(30)
        clock.advance(5 * minute)
        val zone = java.util.TimeZone.getDefault()
        try {
            listOf("Pacific/Kiritimati", "Pacific/Pago_Pago", "UTC").forEach { id ->
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(id))
                vm.onScanResult(NukeScanPurpose.TOGGLE, token)
                assertTrue(state().active)
                assertEquals(NukeTimerCopy.refusal(25 * minute), vm.message.value)
            }
        } finally {
            java.util.TimeZone.setDefault(zone)
        }
    }

    @Test
    fun `opening the screen banks the running timer's time`() = runTest {
        paired()
        startWithTimer(30)
        clock.advance(7 * minute)
        viewModel(preferences) // a second screen open: its init checkpoints
        assertEquals(7 * minute, state().timer!!.consumedMs)
    }

    @Test
    fun `the countdown ticks with the clock and clears when Nuke ends`() = runTest(dispatcher) {
        paired()
        val seen = mutableListOf<Long?>()
        val job = backgroundScope.launch { vm.timerLeftMs.collect { seen += it } }
        assertNull(seen.last())

        startWithTimer(30)
        assertEquals(30 * minute, seen.last())
        clock.advance(10 * minute)
        advanceTimeBy(minute + 1)
        assertEquals(20 * minute, seen.last())

        vm.endWithEmergencyCode()
        vm.verifyEmergency(vm.unlock.value?.emergencyTarget!!)
        assertNull(seen.last())
        job.cancel()
    }

    // --- upgrading -------------------------------------------------------------------------------

    @Test
    fun `a phone upgrading with Nuke on and only the old keys ends it with the key, no timer in the way`() = runTest {
        preferences = NudgePreferences(
            InMemoryPreferencesDataStore(
                preferencesOf(
                    booleanPreferencesKey("nuke_active") to true,
                    stringSetPreferencesKey("nuke_packages") to setOf(instagram),
                    stringPreferencesKey("nuke_key_hash") to NukeKey.hashOrNull(token)!!,
                    stringPreferencesKey("nuke_key_kind") to NukeKeyKind.GENERATED_QR.name,
                    booleanPreferencesKey("nuke_intro_seen") to true
                )
            )
        )
        assertTrue(state().active)
        assertNull(state().timer)
        vm.endNuke()
        vm.onUnlockScanned(token)
        assertFalse(state().active)
    }

    // --- profiles --------------------------------------------------------------------------------

    /** The chips the screen would show now (collected the way the screen collects). */
    private fun TestScope.chips(): List<NukeProfileChip> {
        val seen = mutableListOf<NukeUiState>()
        val job = backgroundScope.launch { vm.uiState.collect { seen += it } }
        job.cancel()
        return seen.last().profiles
    }

    @Test
    fun `saving a profile stores the list, and its chip shows as the one in use`() = runTest(dispatcher) {
        paired(setOf(instagram, tiktok))
        assertNull(vm.saveProfile("Social"))
        assertEquals(listOf(NukeProfile("Social", setOf(instagram, tiktok))), preferences.nukeProfiles.first())
        assertEquals(listOf(NukeProfileChip("Social", selected = true)), chips())

        vm.toggleApp(chrome)
        assertEquals("a different list is no profile", listOf(NukeProfileChip("Social", false)), chips())
    }

    @Test
    fun `a refused save says why and writes nothing`() = runTest {
        paired()
        assertEquals("Give it a name.", vm.saveProfile("   "))
        assertEquals(emptyList<NukeProfile>(), preferences.nukeProfiles.first())
    }

    @Test
    fun `applying a profile while off makes the list exactly that, and Undo puts it back`() = runTest {
        paired(setOf(instagram))
        preferences.editNukeProfiles { com.astraedus.nudge.domain.nuke.NukeProfiles.save(it, "Browse", setOf(chrome, tiktok)) }

        vm.applyProfile("browse")
        assertEquals(setOf(chrome, tiktok), state().packages)
        val applied = vm.applied.value!!
        assertEquals("Browse", applied.name)

        vm.undoApplyProfile(applied)
        assertEquals(setOf(instagram), state().packages)
    }

    @Test
    fun `starting from a profile locks exactly that set, floor excluded`() = runTest {
        paired(setOf(instagram))
        preferences.editNukeProfiles {
            com.astraedus.nudge.domain.nuke.NukeProfiles.save(it, "Mixed", setOf(chrome, oemHome))
        }
        vm.applyProfile("Mixed")
        vm.nukeNow()
        val floor = setOf(oemHome)
        assertTrue(state().active)
        assertEquals(setOf(chrome), NukePolicy.enforceable(state().packages, floor))
        assertFalse("the previous list is not blocked", NukePolicy.isNuked(instagram, state(), floor))
    }

    @Test
    fun `applying a profile while Nuke is on is refused and changes nothing`() = runTest {
        paired(setOf(instagram))
        preferences.editNukeProfiles { com.astraedus.nudge.domain.nuke.NukeProfiles.save(it, "Browse", setOf(chrome)) }
        vm.nukeNow()

        vm.applyProfile("Browse")
        assertEquals(setOf(instagram), state().packages)
        assertNull(vm.applied.value)
        assertEquals(NukeViewModel.PROFILE_WHILE_ON_MESSAGE, vm.message.value)
    }

    @Test
    fun `an Undo that lands after Nuke started is refused too`() = runTest {
        paired(setOf(instagram))
        preferences.editNukeProfiles { com.astraedus.nudge.domain.nuke.NukeProfiles.save(it, "Browse", setOf(chrome)) }
        vm.applyProfile("Browse")
        val applied = vm.applied.value!!
        vm.nukeNow()

        vm.undoApplyProfile(applied)
        assertEquals("the running list is what was started", setOf(chrome), state().packages)
    }

    @Test
    fun `editing or deleting the profile a running Nuke came from leaves its list alone`() = runTest {
        paired(setOf(instagram))
        preferences.editNukeProfiles { com.astraedus.nudge.domain.nuke.NukeProfiles.save(it, "Work", setOf(chrome, tiktok)) }
        vm.applyProfile("Work")
        startWithTimer(30)
        val running = state()

        assertNull(vm.renameProfile("Work", "Deep work"))
        vm.toggleApp(instagram) // the working list grows (free), then is saved over the profile
        assertNull(vm.saveProfile("Deep work"))
        vm.deleteProfile("Deep work")

        assertEquals(running.packages + instagram, state().packages)
        assertEquals(running.timer, state().timer)
        assertTrue(state().active)
        assertEquals(emptyList<NukeProfile>(), preferences.nukeProfiles.first())
        assertNull("no profile edit asked for the key", vm.unlock.value)
    }

    @Test
    fun `rename refuses a name another profile has`() = runTest {
        paired()
        assertNull(vm.saveProfile("One"))
        vm.toggleApp(chrome)
        assertNull(vm.saveProfile("Two"))
        assertEquals("You already have a profile with that name.", vm.renameProfile("Two", "one"))
    }
}
