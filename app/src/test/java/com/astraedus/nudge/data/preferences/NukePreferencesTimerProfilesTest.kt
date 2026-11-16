package com.astraedus.nudge.data.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.astraedus.nudge.domain.nuke.NukeKey
import com.astraedus.nudge.domain.nuke.NukeKeyKind
import com.astraedus.nudge.domain.nuke.NukeProfile
import com.astraedus.nudge.domain.nuke.NukeProfiles
import com.astraedus.nudge.domain.nuke.NukeState
import com.astraedus.nudge.domain.nuke.NukeTimer
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeNukeClock
import com.astraedus.nudge.testing.InMemoryPreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L4: the REAL [NudgePreferences] over an in-memory store, for the profile (#183) and timer (#184)
 * writes. What these pin is the storage contract: what an upgrading phone reads, which writes
 * share a transaction, and that nothing corrupt can trap the key or touch the running list.
 */
class NukePreferencesTimerProfilesTest {

    private val minute = 60_000L
    private val clock = FakeNukeClock()
    private val token = NukeKey.generateToken()
    private val instagram = "com.instagram.android"

    private suspend fun NudgePreferences.paired(packages: Set<String> = setOf(instagram)) {
        setNukeKey(NukeKey.hashOrNull(token)!!, NukeKeyKind.GENERATED_QR)
        addNukePackages(packages)
    }

    private suspend fun NudgePreferences.state(): NukeState = nukeState.first()

    // --- upgrading from before #183/#184 ----------------------------------------------------------

    /**
     * A phone on the previous release has ONLY the old Nuke keys, under their on-disk names. It
     * must read exactly the state it had: on, the same list, the same key, no timer, no profiles,
     * no timer choice. (The key still ending it is pinned through the real gate in
     * `NukeViewModelTest`.)
     */
    @Test
    fun `a store holding only the old Nuke keys reads as the old state, with no timer and no profiles`() = runTest {
        val hash = NukeKey.hashOrNull(token)!!
        val prefs = NudgePreferences(
            InMemoryPreferencesDataStore(
                preferencesOf(
                    booleanPreferencesKey("nuke_active") to true,
                    stringSetPreferencesKey("nuke_packages") to setOf(instagram),
                    stringPreferencesKey("nuke_key_hash") to hash,
                    stringPreferencesKey("nuke_key_kind") to NukeKeyKind.EXISTING_CODE.name,
                    booleanPreferencesKey("nuke_intro_seen") to true
                )
            )
        )
        assertEquals(
            NukeState(active = true, packages = setOf(instagram), keyHash = hash, keyKind = NukeKeyKind.EXISTING_CODE),
            prefs.state()
        )
        assertEquals(emptyList<NukeProfile>(), prefs.nukeProfiles.first())
        assertEquals(0, prefs.nukeTimerChoiceMinutes.first())
    }

    // --- the timer -------------------------------------------------------------------------------

    @Test
    fun `arming with a timer stores it, and the state reads it back`() = runTest {
        val prefs = FakeNudgePreferences { paired() }
        val timer = NukeTimer.start(30, clock.now())!!
        prefs.armNuke(timer)
        assertTrue(prefs.state().active)
        assertEquals(timer, prefs.state().timer)
    }

    @Test
    fun `arming never turns Nuke on without a key, timer or not`() = runTest {
        val prefs = FakeNudgePreferences { addNukePackages(setOf(instagram)) }
        prefs.armNuke(NukeTimer.start(30, clock.now()))
        assertFalse(prefs.state().active)
        assertNull(prefs.state().timer)
    }

    @Test
    fun `arming a Nuke that is already on cannot replace its timer with a shorter one or none`() = runTest {
        val prefs = FakeNudgePreferences { paired() }
        val long = NukeTimer.start(120, clock.now())!!
        prefs.armNuke(long)
        prefs.armNuke(NukeTimer.start(15, clock.now()))
        assertEquals(long, prefs.state().timer)
        prefs.armNuke(null)
        assertEquals(long, prefs.state().timer)
        prefs.setNukeActive(true)
        assertEquals(long, prefs.state().timer)
    }

    @Test
    fun `ending Nuke clears the timer, so the next start begins clean`() = runTest {
        val prefs = FakeNudgePreferences { paired() }
        prefs.armNuke(NukeTimer.start(30, clock.now()))
        prefs.setNukeActive(false)
        assertNull(prefs.state().timer)
        prefs.setNukeActive(true)
        assertTrue(prefs.state().active)
        assertNull("no auto-restored timer", prefs.state().timer)
    }

    @Test
    fun `removing the key ends Nuke and clears the timer`() = runTest {
        val prefs = FakeNudgePreferences { paired() }
        prefs.armNuke(NukeTimer.start(30, clock.now()))
        prefs.clearNukeKey()
        assertFalse(prefs.state().active)
        assertNull(prefs.state().timer)
    }

    @Test
    fun `a checkpoint banks time and keeps what is left`() = runTest {
        val prefs = FakeNudgePreferences { paired() }
        prefs.armNuke(NukeTimer.start(30, clock.now()))
        clock.advance(10 * minute)
        prefs.checkpointNukeTimer(clock.now())
        val timer = prefs.state().timer!!
        assertEquals(10 * minute, timer.consumedMs)
        clock.reboot(uptimeMs = minute)
        assertEquals("the banked 10 minutes survive a reboot", 19 * minute, timer.remainingMs(clock.now()))
    }

    @Test
    fun `a checkpoint with Nuke off, or no timer, writes nothing`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val prefs = NudgePreferences(store)
        prefs.paired()
        val before = store.writes
        prefs.checkpointNukeTimer(clock.now())
        prefs.setNukeActive(true)
        val armed = store.writes
        prefs.checkpointNukeTimer(clock.now())
        assertEquals(before + 1, armed)
        assertEquals(armed, store.writes)
    }

    @Test
    fun `a corrupt stored timer reads as no timer, and Nuke stays on`() = runTest {
        val hash = NukeKey.hashOrNull(token)!!
        val prefs = NudgePreferences(
            InMemoryPreferencesDataStore(
                preferencesOf(
                    booleanPreferencesKey("nuke_active") to true,
                    stringSetPreferencesKey("nuke_packages") to setOf(instagram),
                    stringPreferencesKey("nuke_key_hash") to hash,
                    longPreferencesKey("nuke_timer_duration_ms") to 365L * 24 * 60 * minute,
                    longPreferencesKey("nuke_timer_segment_start_ms") to 0L
                )
            )
        )
        assertTrue(prefs.state().active)
        assertNull(prefs.state().timer)
    }

    @Test
    fun `timer keys left behind while Nuke is off are not a timer`() = runTest {
        val hash = NukeKey.hashOrNull(token)!!
        val prefs = NudgePreferences(
            InMemoryPreferencesDataStore(
                preferencesOf(
                    booleanPreferencesKey("nuke_active") to false,
                    stringPreferencesKey("nuke_key_hash") to hash,
                    longPreferencesKey("nuke_timer_duration_ms") to 30 * minute,
                    longPreferencesKey("nuke_timer_segment_start_ms") to 0L
                )
            )
        )
        assertNull(prefs.state().timer)
    }

    @Test
    fun `the timer choice is remembered and validated`() = runTest {
        val prefs = FakeNudgePreferences()
        prefs.setNukeTimerChoice(45)
        assertEquals(45, prefs.nukeTimerChoiceMinutes.first())
        prefs.setNukeTimerChoice(17)
        assertEquals(0, prefs.nukeTimerChoiceMinutes.first())
    }

    // --- profiles --------------------------------------------------------------------------------

    @Test
    fun `profile edits touch only the profiles, never the list or a running Nuke`() = runTest {
        val prefs = FakeNudgePreferences { paired() }
        prefs.armNuke(NukeTimer.start(30, clock.now()))
        val before = prefs.state()

        prefs.editNukeProfiles { NukeProfiles.save(it, "Social", setOf(instagram, "com.twitter.android")) }
        prefs.editNukeProfiles { NukeProfiles.rename(it, "Social", "Feeds") }
        prefs.editNukeProfiles { NukeProfiles.save(it, "Feeds", setOf("com.android.chrome")) }
        assertEquals(listOf(NukeProfile("Feeds", setOf("com.android.chrome"))), prefs.nukeProfiles.first())
        prefs.editNukeProfiles { NukeProfiles.delete(it, "Feeds") }

        assertEquals(before, prefs.state())
        assertEquals(emptyList<NukeProfile>(), prefs.nukeProfiles.first())
    }

    @Test
    fun `a refused profile edit writes nothing`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val prefs = NudgePreferences(store)
        val before = store.writes
        val result = prefs.editNukeProfiles { NukeProfiles.save(it, " ", setOf(instagram)) }
        assertEquals(NukeProfiles.Edit.Refused(NukeProfiles.Refusal.BLANK_NAME), result)
        assertEquals(before, store.writes)
    }

    @Test
    fun `a corrupt profiles value reads as none, and a save over it recovers`() = runTest {
        val prefs = NudgePreferences(
            InMemoryPreferencesDataStore(preferencesOf(stringPreferencesKey("nuke_profiles") to "{not json"))
        )
        assertEquals(emptyList<NukeProfile>(), prefs.nukeProfiles.first())
        prefs.editNukeProfiles { NukeProfiles.save(it, "Social", setOf(instagram)) }
        assertEquals(listOf(NukeProfile("Social", setOf(instagram))), prefs.nukeProfiles.first())
    }

    @Test
    fun `switching the list to a profile works only while Nuke is off`() = runTest {
        val prefs = FakeNudgePreferences { paired() }
        assertTrue(prefs.replaceNukePackagesWhileOff(setOf("com.android.chrome")))
        assertEquals(setOf("com.android.chrome"), prefs.state().packages)

        prefs.setNukeActive(true)
        assertFalse(prefs.replaceNukePackagesWhileOff(setOf(instagram)))
        assertEquals("a running Nuke's list is untouched", setOf("com.android.chrome"), prefs.state().packages)
        assertNotNull(prefs.state().keyHash)
    }
}
