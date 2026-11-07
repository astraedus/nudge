package com.astraedus.nudge.ui.screens.config

import com.astraedus.nudge.ui.components.UsageReminderInput
import androidx.lifecycle.SavedStateHandle
import com.astraedus.nudge.MainDispatcherRule
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.RuleSnapshot
import com.astraedus.nudge.data.repository.RuleSnapshots
import com.astraedus.nudge.domain.engine.FirstUnlockWindow
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.FeatureMode
import com.astraedus.nudge.domain.usecase.SetDayStartMinuteUseCase
import com.astraedus.nudge.domain.packages.FakeEssentialPackages
import com.astraedus.nudge.domain.packages.FakePackageFacts
import com.astraedus.nudge.testing.FakeInstalledAppsRepository
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeRuleDatabase
import com.astraedus.nudge.testing.aBlockRule
import com.astraedus.nudge.testing.anApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * ViewModel-level tests for [UnifiedAppConfigViewModel], focused on the semantics that changed
 * in the issue #21 fix: [UnifiedAppConfigViewModel.setBlocksWholeApp]'s lastBlockingMode restore,
 * and [UnifiedAppConfigViewModel.buildDefaultRule]'s independent web-block-mode persistence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedAppConfigViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val packageName = "com.instagram.android"

    private var db = FakeRuleDatabase()

    /** The device has Instagram installed (the app label the editor shows) and nothing else. */
    private val installedApps = FakeInstalledAppsRepository(anApp(packageName, "Instagram"))

    /** The preferences the ViewModel is reading, so a test can assert what a setting actually became. */
    private lateinit var prefs: NudgePreferences

    /**
     * The real day-start use case over this test's rules, so the Strict Mode decision is the
     * production one. Only the snapshot port is stood in for, and only the question it is asked.
     */
    private fun dayStartUseCase(): SetDayStartMinuteUseCase = SetDayStartMinuteUseCase(
        prefs,
        object : RuleSnapshots {
            override suspend fun current(): RuleSnapshot = RuleSnapshot.of(db.rules, emptyList(), emptyList())
            override suspend fun enabledRules(): List<BlockRule> = db.rules.filter { it.enabled }
        }
    )

    private fun viewModel(
        rules: List<BlockRule> = emptyList(),
        strictMode: Boolean = false,
        seed: suspend NudgePreferences.() -> Unit = {}
    ): UnifiedAppConfigViewModel {
        db = FakeRuleDatabase() // a fresh database per view model, so two in one test do not share rows
        rules.forEach { db.seedRule(it) }
        prefs = FakeNudgePreferences {
            setStrictModeEnabled(strictMode)
            seed()
        }
        return UnifiedAppConfigViewModel(
            SavedStateHandle(mapOf("packageName" to packageName)),
            db.repository,
            installedApps,
            FakeEssentialPackages(),
            FakePackageFacts(),
            prefs,
            dayStartUseCase()
        )
    }

    /** An All apps rule one morning long, for the tests that need one enabled first-unlock rule. */
    private fun firstUnlockRule(minutes: Int, days: String = "1,2,3,4,5") = aBlockRule(
        packageName = packageName,
        mode = "HARD_BLOCK",
        scheduleDays = days,
        firstUnlockMinutes = minutes
    )

    // ── setBlocksWholeApp / lastBlockingMode restore ──────────────────────

    @Test
    fun `setBlocksWholeApp false sets NONE and blocksWholeApp false`() = runTest {
        val vm = viewModel()

        vm.setBlocksWholeApp(false)

        val state = vm.uiState.value
        assertEquals(BlockMode.NONE, state.defaultMode)
        assertFalse(state.blocksWholeApp)
    }

    @Test
    fun `setBlocksWholeApp true restores lastBlockingMode not a hardcoded DELAY`() = runTest {
        val vm = viewModel()

        vm.setDefaultMode(BlockMode.HARD_BLOCK)
        vm.setBlocksWholeApp(false)
        assertEquals(BlockMode.NONE, vm.uiState.value.defaultMode)

        vm.setBlocksWholeApp(true)

        assertEquals(BlockMode.HARD_BLOCK, vm.uiState.value.defaultMode)
    }

    @Test
    fun `loading a rule with mode NONE yields lastBlockingMode DELAY with no prior choice`() = runTest {
        val vm = viewModel(listOf(aBlockRule(packageName = packageName, mode = "NONE")))

        val state = vm.uiState.value
        assertEquals(BlockMode.NONE, state.defaultMode)
        assertEquals(BlockMode.DELAY, state.lastBlockingMode)
    }

    @Test
    fun `loading a rule with mode BREATHING yields lastBlockingMode BREATHING`() = runTest {
        val vm = viewModel(listOf(aBlockRule(packageName = packageName, mode = "BREATHING")))

        val state = vm.uiState.value
        assertEquals(BlockMode.BREATHING, state.defaultMode)
        assertEquals(BlockMode.BREATHING, state.lastBlockingMode)
    }

    @Test
    fun `setDefaultMode NONE does not clobber lastBlockingMode`() = runTest {
        val vm = viewModel()

        vm.setDefaultMode(BlockMode.HARD_BLOCK)
        vm.setDefaultMode(BlockMode.NONE)

        val state = vm.uiState.value
        assertEquals(BlockMode.NONE, state.defaultMode)
        assertEquals(BlockMode.HARD_BLOCK, state.lastBlockingMode)
    }

    // ── web-block-mode semantics (buildDefaultRule, issue #21 fix) ────────

    @Test
    fun `whole-app blocking on with web enabled builds null webBlockMode`() = runTest {
        val vm = viewModel()
        vm.setDefaultMode(BlockMode.HARD_BLOCK)
        vm.setWebDomainEnabled(true) // auto-populates DEFAULT_WEB_DOMAINS for this package

        val rule = vm.buildDefaultRule(vm.uiState.value)

        assertNull(rule.webBlockMode)
        assertNotNull(rule.webDomains)
    }

    @Test
    fun `whole-app blocking off with web HARD_BLOCK builds NONE mode and independent webBlockMode`() = runTest {
        val vm = viewModel()
        vm.setBlocksWholeApp(false)
        vm.setWebDomainEnabled(true)
        vm.setWebBlockMode(BlockMode.HARD_BLOCK)

        val rule = vm.buildDefaultRule(vm.uiState.value)

        assertEquals("NONE", rule.mode)
        assertEquals("HARD_BLOCK", rule.webBlockMode)
        assertNotNull(rule.webDomains)
    }

    @Test
    fun `web disabled builds null webDomains and null webBlockMode`() = runTest {
        val vm = viewModel()
        vm.setWebDomainEnabled(false)
        vm.setWebDomains("instagram.com") // text present but toggle off -- must not leak through

        val rule = vm.buildDefaultRule(vm.uiState.value)

        assertNull(rule.webDomains)
        assertNull(rule.webBlockMode)
    }

    @Test
    fun `setDefaultMode while blocking on also moves webBlockMode so it does not silently drift`() = runTest {
        val vm = viewModel()

        vm.setDefaultMode(BlockMode.HARD_BLOCK)

        assertEquals(BlockMode.HARD_BLOCK, vm.uiState.value.webBlockMode)
    }

    @Test
    fun `loading rule mode NONE with webBlockMode BREATHING seeds state`() = runTest {
        val vm = viewModel(
            listOf(aBlockRule(packageName = packageName, mode = "NONE", webDomains = "instagram.com", webBlockMode = "BREATHING"))
        )

        val state = vm.uiState.value
        assertEquals(BlockMode.BREATHING, state.webBlockMode)
        assertTrue(state.webDomainEnabled)
    }

    // ── other cheap high-value state transitions ───────────────────────────

    @Test
    fun `setWebDomainEnabled true auto-populates known default domains for the package`() = runTest {
        val vm = viewModel()

        vm.setWebDomainEnabled(true)

        val state = vm.uiState.value
        assertTrue(state.webDomainEnabled)
        assertEquals("instagram.com,www.instagram.com", state.webDomains)
    }

    @Test
    fun `setWebBlockMode NONE is ignored`() = runTest {
        val vm = viewModel()
        vm.setBlocksWholeApp(false)
        val before = vm.uiState.value.webBlockMode

        vm.setWebBlockMode(BlockMode.NONE)

        assertEquals(before, vm.uiState.value.webBlockMode)
    }

    // ── Tab Vanish / Following steer: load-side defaults ───────────────────

    @Test
    fun `loading a rule with tabVanish false loads state tabVanish false`() = runTest {
        val vm = viewModel(listOf(aBlockRule(packageName = packageName, mode = "HARD_BLOCK", tabVanish = false)))

        assertFalse(vm.uiState.value.tabVanish)
    }

    @Test
    fun `no existing rule loads state tabVanish true`() = runTest {
        val vm = viewModel()

        assertTrue(vm.uiState.value.tabVanish)
    }

    @Test
    fun `loading a rule with followingSteer true loads state followingSteer true`() = runTest {
        val vm = viewModel(listOf(aBlockRule(packageName = packageName, mode = "HARD_BLOCK", followingSteer = true)))

        assertTrue(vm.uiState.value.followingSteer)
    }

    @Test
    fun `no existing rule loads state followingSteer false`() = runTest {
        val vm = viewModel()

        assertFalse(vm.uiState.value.followingSteer)
    }

    // ── Tab Vanish / Following steer: save-side propagation ────────────────

    @Test
    fun `save with tabVanish false and a Reels feature override writes tabVanish false on every persisted rule`() = runTest {
        val vm = viewModel()
        vm.setTabVanish(false)
        vm.setFeatureOverride("REELS", FeatureOverride(mode = FeatureMode.BLOCK))

        vm.save()

        // At least the app-level default rule AND the Reels feature-override rule must have been
        // persisted -- this is the case a test that only checks the default rule would miss.
        assertTrue("expected 2+ persisted rules, got ${db.rules.size}", db.rules.size >= 2)
        assertTrue(
            "expected a feature-override rule for REELS",
            db.rules.any { it.inAppFeatures == "REELS" }
        )
        db.rules.forEach { rule ->
            assertFalse("rule ${rule.inAppFeatures ?: "<default>"} carried tabVanish=true", rule.tabVanish)
        }
    }

    @Test
    fun `followingSteer lands on the app-level rule and not on a feature-override rule`() = runTest {
        val vm = viewModel()
        vm.setFollowingSteer(true)
        vm.setFeatureOverride("REELS", FeatureOverride(mode = FeatureMode.BLOCK))

        vm.save()

        val defaultRuleCaptured = db.rules.first { it.inAppFeatures == null }
        val featureRuleCaptured = db.rules.first { it.inAppFeatures == "REELS" }
        assertTrue(defaultRuleCaptured.followingSteer)
        assertFalse(featureRuleCaptured.followingSteer)
    }

    @Test
    fun `save with a scheduled override also writes tabVanish false on the scheduled rules`() = runTest {
        val vm = viewModel()
        vm.setTabVanish(false)
        vm.setScheduledOverrideEnabled(true)
        vm.setScheduledFeatureOverride("REELS", FeatureOverride(mode = FeatureMode.BLOCK))

        vm.save()

        // App-level default + scheduled app-level + scheduled feature override = 3 rules.
        assertTrue("expected 3+ persisted rules, got ${db.rules.size}", db.rules.size >= 3)
        db.rules.forEach { rule ->
            assertFalse(
                "rule (schedule=${rule.scheduleDays}, feature=${rule.inAppFeatures}) carried tabVanish=true",
                rule.tabVanish
            )
        }
    }


    // ── Keep the pass on short trips (#110): per app, only for pass-granting modes ──

    @Test
    fun `loading a rule with keepPassOnShortTrips true loads it, and no rule loads it off`() = runTest {
        assertTrue(viewModel(listOf(aBlockRule(packageName = packageName, mode = "DELAY", keepPassOnShortTrips = true))).uiState.value.keepPassOnShortTrips)
        assertFalse(viewModel().uiState.value.keepPassOnShortTrips)
    }

    @Test
    fun `the short-trips row shows for Delay, Hold and Breathing, never Hard Block or an unblocked app`() = runTest {
        val vm = viewModel()
        for (mode in listOf(BlockMode.DELAY, BlockMode.HOLD, BlockMode.BREATHING)) {
            vm.setDefaultMode(mode)
            assertTrue("$mode grants a pass", vm.uiState.value.showKeepPassOnShortTrips)
        }
        vm.setDefaultMode(BlockMode.HARD_BLOCK)
        assertFalse(vm.uiState.value.showKeepPassOnShortTrips)
        vm.setBlocksWholeApp(false)
        assertFalse(vm.uiState.value.showKeepPassOnShortTrips)
    }

    @Test
    fun `save with short trips on writes it on every persisted rule, like tabVanish`() = runTest {
        val vm = viewModel()
        vm.setDefaultMode(BlockMode.DELAY)
        vm.setKeepPassOnShortTrips(true)
        vm.setFeatureOverride("REELS", FeatureOverride(mode = FeatureMode.BLOCK))
        vm.setScheduledOverrideEnabled(true)
        vm.setScheduledFeatureOverride("REELS", FeatureOverride(mode = FeatureMode.BLOCK))

        vm.save()

        assertTrue("expected 4+ persisted rules, got ${db.rules.size}", db.rules.size >= 4)
        db.rules.forEach { rule ->
            assertTrue(
                "rule (schedule=${rule.scheduleDays}, feature=${rule.inAppFeatures}) dropped keepPassOnShortTrips",
                rule.keepPassOnShortTrips
            )
        }
    }

    @Test
    fun `switching the app to Hard Block saves short trips off, a hidden option never persists`() = runTest {
        val vm = viewModel()
        vm.setDefaultMode(BlockMode.DELAY)
        vm.setKeepPassOnShortTrips(true)
        vm.setDefaultMode(BlockMode.HARD_BLOCK)

        vm.save()

        assertTrue(db.rules.isNotEmpty())
        db.rules.forEach { assertFalse(it.keepPassOnShortTrips) }
    }

    @Test
    fun `short trips is off by default on a fresh save`() = runTest {
        val vm = viewModel()
        vm.setDefaultMode(BlockMode.DELAY)
        vm.save()

        assertTrue(db.rules.isNotEmpty())
        db.rules.forEach { assertFalse(it.keepPassOnShortTrips) }
    }

    // ── Tab Vanish / Following steer: registry-driven visibility ───────────

    @Test
    fun `supportsTabVanish and supportsFollowingSteer are true for Instagram`() {
        val state = UnifiedAppConfigState(packageName = "com.instagram.android")

        assertTrue(state.supportsTabVanish)
        assertTrue(state.supportsFollowingSteer)
    }

    @Test
    fun `supportsTabVanish and supportsFollowingSteer are false for a package with no adapter`() {
        val state = UnifiedAppConfigState(packageName = "com.google.android.keep")

        assertFalse(state.supportsTabVanish)
        assertFalse(state.supportsFollowingSteer)
    }

    // ── usage reminder (#77) ───────────────────────────────────────────────

    @Test
    fun `default rule carries remindAfterMinutes when the reminder is enabled`() = runTest {
        val vm = viewModel()
        vm.setReminderEnabled(true)
        vm.setRemindAfterMinutesText("25")

        assertEquals(25, vm.buildDefaultRule(vm.uiState.value).remindAfterMinutes)
    }

    @Test
    fun `default rule has null remindAfterMinutes when the reminder is disabled`() = runTest {
        val vm = viewModel()
        vm.setReminderEnabled(true)
        vm.setRemindAfterMinutesText("25")
        vm.setReminderEnabled(false)

        assertNull(vm.buildDefaultRule(vm.uiState.value).remindAfterMinutes)
    }

    @Test
    fun `loading a default rule with a reminder seeds enabled text and original`() = runTest {
        val rule = aBlockRule(packageName = packageName, mode = "DELAY").copy(remindAfterMinutes = 25)
        val vm = viewModel(listOf(rule))

        val state = vm.uiState.value
        assertTrue(state.reminderEnabled)
        assertEquals("25", state.remindAfterMinutesText)
        assertEquals(25, state.originalRemindAfterMinutes)
    }

    @Test
    fun `loading a default rule without a reminder leaves it off and blank`() = runTest {
        val vm = viewModel(listOf(aBlockRule(packageName = packageName, mode = "DELAY")))

        val state = vm.uiState.value
        assertFalse(state.reminderEnabled)
        assertEquals("", state.remindAfterMinutesText)
        assertNull(state.originalRemindAfterMinutes)
    }

    @Test
    fun `an untouched loaded reminder re-saves the original on the default rule`() = runTest {
        val vm = viewModel(listOf(aBlockRule(packageName = packageName, mode = "DELAY").copy(remindAfterMinutes = 2000)))

        assertEquals(2000, vm.buildDefaultRule(vm.uiState.value).remindAfterMinutes)
    }

    @Test
    fun `setReminderEnabled true on blank text sets the default minutes`() = runTest {
        val vm = viewModel()
        assertEquals("", vm.uiState.value.remindAfterMinutesText)

        vm.setReminderEnabled(true)

        assertTrue(vm.uiState.value.reminderEnabled)
        assertEquals(UsageReminderInput.DEFAULT_MINUTES_TEXT, vm.uiState.value.remindAfterMinutesText)
    }

    @Test
    fun `the reminder is saved independently of the time-based auto-kick`() = runTest {
        val vm = viewModel()
        vm.setReminderEnabled(true)
        vm.setRemindAfterMinutesText("20")

        val rule = vm.buildDefaultRule(vm.uiState.value)

        assertEquals(20, rule.remindAfterMinutes)
        assertNull(rule.autoKickAfterMinutes)
    }
    // ── the first-unlock schedule (spec 3.5/5.3) ─────────────────────────────

    @Test
    fun `a first-unlock override saves the window, keeps the days and writes no times`() = runTest {
        val vm = viewModel()
        vm.setScheduledOverrideEnabled(true)
        vm.setScheduledOverrideFirstUnlock(true)
        vm.setFirstUnlockMinutes(45)

        vm.save()

        val scheduled = db.rules.single { it.scheduleDays != null }
        assertEquals(45, scheduled.firstUnlockMinutes)
        assertNull("a first-unlock window has no clock times", scheduled.scheduleStartMinute)
        assertNull(scheduled.scheduleEndMinute)
        assertEquals("the days still pick the logical day", "1,2,3,4,5,6,7", scheduled.scheduleDays)
        assertNull("the always-on default rule has no window", db.rules.first().firstUnlockMinutes)
    }

    @Test
    fun `loading a first-unlock override restores its kind and minutes`() = runTest {
        val vm = viewModel(
            listOf(firstUnlockRule(minutes = 60).copy(scheduleStartMinute = null, scheduleEndMinute = null))
        )

        assertTrue(vm.uiState.value.scheduledOverrideEnabled)
        assertTrue(vm.uiState.value.scheduledOverrideFirstUnlock)
        assertEquals(60, vm.uiState.value.firstUnlockMinutes)
    }

    @Test
    fun `an existing first-unlock override whose window is running does not claim it starts at the next one`() = runTest {
        // The reviewer's case (should-fix 2): the rule was armed before today's unlock, so it is
        // blocking now. Saving keeps that window, and the sentence would be a lie.
        val stamp = System.currentTimeMillis() - 60_000L
        val vm = viewModel(
            listOf(firstUnlockRule(minutes = 60).copy(armedAt = stamp)),
            seed = { setFirstUnlockAt(stamp) }
        )

        assertFalse(vm.uiState.value.startsAtNextUnlock)
    }

    @Test
    fun `a rule newly switched to first unlock says it starts at the next one`() = runTest {
        val vm = viewModel(
            listOf(aBlockRule(packageName = packageName, mode = "HARD_BLOCK")),
            seed = { setFirstUnlockAt(System.currentTimeMillis()) }
        )

        vm.setScheduledOverrideEnabled(true)
        vm.setScheduledOverrideFirstUnlock(true)

        assertTrue(
            "switching the kind arms the new window after today's unlock",
            vm.uiState.value.startsAtNextUnlock
        )
    }

    @Test
    fun `a new first-unlock override starts on every day, a saved one keeps its days`() = runTest {
        // Decision 2026-11-05: Morning Lock is daily, so an untouched new override opens with all
        // seven days selected. A saved days-and-times override keeps the days it had.
        val fresh = viewModel()
        fresh.setScheduledOverrideEnabled(true)
        fresh.setScheduledOverrideFirstUnlock(true)
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7), fresh.uiState.value.scheduleDays)

        val saved = viewModel(
            listOf(
                aBlockRule(
                    packageName = packageName,
                    mode = "HARD_BLOCK",
                    scheduleDays = "1,2,3",
                    scheduleStartMinute = 540,
                    scheduleEndMinute = 600
                )
            )
        )
        saved.setScheduledOverrideFirstUnlock(true)
        assertEquals(setOf(1, 2, 3), saved.uiState.value.scheduleDays)
    }

    @Test
    fun `re-enabling a disabled first-unlock rule re-arms it`() = runTest {
        // Should-fix 1: a disabled rule keeps its old stamp across App config's delete and re-add,
        // and RuleArming honours a carried stamp, so turning it back on mid-morning must re-arm.
        val vm = viewModel(
            listOf(firstUnlockRule(minutes = 30).copy(enabled = false, armedAt = 4_000L))
        )

        vm.setEnabled(true)
        vm.save()

        assertTrue(
            "off to on arms now",
            db.rules.single { it.scheduleDays != null }.armedAt > 4_000L
        )
    }

    @Test
    fun `a free save keeps the scheduled feature override's window too`() = runTest {
        // Blocker 2: the override row carries the schedule's window, so it needs the schedule's
        // stamp. It used to be written with none, and every save re-armed it.
        val stamp = System.currentTimeMillis() - 60_000L
        val override = aBlockRule(
            packageName = packageName,
            mode = "HARD_BLOCK",
            inAppFeatures = "REELS",
            scheduleDays = "1,2,3,4,5",
            firstUnlockMinutes = 30,
            armedAt = stamp
        )
        val vm = viewModel(
            listOf(firstUnlockRule(minutes = 30).copy(armedAt = stamp), override)
        )

        vm.save()

        val windows = db.rules.filter { it.firstUnlockMinutes != null }
        assertEquals("the parent rule and its override", 2, windows.size)
        assertTrue(
            "every row with a window keeps its stamp: ${windows.map { it.armedAt }}",
            windows.all { it.armedAt == stamp }
        )
    }

    @Test
    fun `a stronger save during the window keeps the window running`() = runTest {
        // Review blocker: re-arming on every save meant 30 -> 60 minutes, or a save with nothing
        // changed, ended this morning's block. The row must keep its stamp across App config's
        // delete and re-add, and the window must still be active afterwards.
        // A fixed Monday, because the rule runs Mon-Fri: a stamp from today fails at weekends.
        val stamp = LocalDate.of(2026, 11, 2).atTime(8, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val vm = viewModel(listOf(firstUnlockRule(minutes = 30).copy(armedAt = stamp)))

        vm.setFirstUnlockMinutes(60)
        vm.save()

        val stored = db.rules.single { it.scheduleDays != null }
        assertEquals("the save must not re-arm", stamp, stored.armedAt)
        assertTrue(
            "the window is still running ten minutes after the unlock",
            FirstUnlockWindow.isActive(
                nowMs = stamp + 10 * 60_000L,
                stampMs = stamp,
                dayStartMinute = FirstUnlockWindow.DEFAULT_DAY_START_MINUTE,
                minutes = 60,
                days = listOf(1, 2, 3, 4, 5),
                armedAt = stored.armedAt,
                zone = ZoneId.systemDefault()
            )
        )
    }

    @Test
    fun `a days-only rule still counts as the scheduled override, not the default rule`() = runTest {
        // Review: dropping `scheduleDays != null` from the classification made a days-only rule
        // look like the always-on default, so saving rewrote it as always-on.
        val vm = viewModel(
            listOf(aBlockRule(packageName = packageName, mode = "HARD_BLOCK", scheduleDays = "1,2,3"))
        )

        assertTrue(vm.uiState.value.scheduledOverrideEnabled)
        assertFalse(vm.uiState.value.scheduledOverrideFirstUnlock)
        assertEquals(setOf(1, 2, 3), vm.uiState.value.scheduleDays)
    }

    @Test
    fun `moving the day start writes when this phone has no first-unlock rule`() = runTest {
        val vm = viewModel()

        vm.setDayStartMinute(360)

        assertEquals(360, prefs.dayStartMinute.first())
        assertNull(vm.challenge.value)
    }

    @Test
    fun `a weakening day-start move waits for the challenge under Strict Mode`() = runTest {
        val vm = viewModel(rules = listOf(firstUnlockRule(minutes = 30)), strictMode = true)

        vm.setDayStartMinute(360)

        assertNotNull("moving the day start can skip today's window (spec 5.6)", vm.challenge.value)
        assertEquals("nothing is written before the challenge passes", 240, prefs.dayStartMinute.first())
    }

    @Test
    fun `shrinking an existing first-unlock override takes the challenge, and writes nothing until it passes`() = runTest {
        val vm = viewModel(rules = listOf(firstUnlockRule(minutes = 60)), strictMode = true)
        assertTrue(vm.uiState.value.scheduledOverrideFirstUnlock)

        vm.setFirstUnlockMinutes(30)
        vm.save()

        assertNotNull("60 -> 30 opens half an hour: it is weakening (spec 5.6)", vm.challenge.value)
        assertEquals("the old rule is still the only one", 60, db.rules.single { it.scheduleDays != null }.firstUnlockMinutes)
    }

    @Test
    fun `turning an existing override off takes the challenge, and a longer window does not`() = runTest {
        val vm = viewModel(rules = listOf(firstUnlockRule(minutes = 30)), strictMode = true)

        // Longer is stricter: no challenge, and the row on disk carries the new window.
        vm.setFirstUnlockMinutes(60)
        vm.save()
        assertNull(vm.challenge.value)
        assertEquals(60, db.rules.single { it.scheduleDays != null }.firstUnlockMinutes)

        // Turning it off un-covers the rest of the day: that one challenges.
        vm.setScheduledOverrideEnabled(false)
        vm.save()
        assertNotNull("removing a rule is turning it off (spec 5.6)", vm.challenge.value)
        assertEquals("nothing is deleted before the challenge passes", 1, db.rules.count { it.scheduleDays != null })
    }
}
