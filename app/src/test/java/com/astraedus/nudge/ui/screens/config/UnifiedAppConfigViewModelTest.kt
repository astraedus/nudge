package com.astraedus.nudge.ui.screens.config

import androidx.lifecycle.SavedStateHandle
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.FeatureMode
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ViewModel-level tests for [UnifiedAppConfigViewModel], focused on the semantics that changed
 * in the issue #21 fix: [UnifiedAppConfigViewModel.setBlocksWholeApp]'s lastBlockingMode restore,
 * and [UnifiedAppConfigViewModel.buildDefaultRule]'s independent web-block-mode persistence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedAppConfigViewModelTest {

    private val packageName = "com.instagram.android"

    private lateinit var blockRuleRepository: BlockRuleRepository
    private lateinit var installedAppsRepository: InstalledAppsRepository
    private lateinit var nudgePreferences: NudgePreferences

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        blockRuleRepository = mockk()
        installedAppsRepository = mockk()
        nudgePreferences = mockk()

        coEvery { installedAppsRepository.resolveAppName(any()) } returns "Instagram"
        every { nudgePreferences.isStrictModeEnabled } returns flowOf(false)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(rules: List<BlockRule> = emptyList()): UnifiedAppConfigViewModel {
        every { blockRuleRepository.getRulesForPackage(packageName) } returns flowOf(rules)
        return UnifiedAppConfigViewModel(
            SavedStateHandle(mapOf("packageName" to packageName)),
            blockRuleRepository,
            installedAppsRepository,
            nudgePreferences
        )
    }

    private fun defaultRule(
        mode: String,
        webDomains: String? = null,
        webBlockMode: String? = null,
        tabVanish: Boolean = true,
        followingSteer: Boolean = false
    ) = BlockRule(
        packageName = packageName,
        mode = mode,
        webDomains = webDomains,
        webBlockMode = webBlockMode,
        tabVanish = tabVanish,
        followingSteer = followingSteer
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
        val vm = viewModel(listOf(defaultRule(mode = "NONE")))

        val state = vm.uiState.value
        assertEquals(BlockMode.NONE, state.defaultMode)
        assertEquals(BlockMode.DELAY, state.lastBlockingMode)
    }

    @Test
    fun `loading a rule with mode BREATHING yields lastBlockingMode BREATHING`() = runTest {
        val vm = viewModel(listOf(defaultRule(mode = "BREATHING")))

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
            listOf(defaultRule(mode = "NONE", webDomains = "instagram.com", webBlockMode = "BREATHING"))
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
        val vm = viewModel(listOf(defaultRule(mode = "HARD_BLOCK", tabVanish = false)))

        assertFalse(vm.uiState.value.tabVanish)
    }

    @Test
    fun `no existing rule loads state tabVanish true`() = runTest {
        val vm = viewModel()

        assertTrue(vm.uiState.value.tabVanish)
    }

    @Test
    fun `loading a rule with followingSteer true loads state followingSteer true`() = runTest {
        val vm = viewModel(listOf(defaultRule(mode = "HARD_BLOCK", followingSteer = true)))

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
        val capturedRules = mutableListOf<BlockRule>()
        coEvery { blockRuleRepository.deleteDirectRulesForPackage(any()) } just Runs
        coEvery { blockRuleRepository.addRule(capture(capturedRules)) } returns 1L

        val vm = viewModel()
        vm.setTabVanish(false)
        vm.setFeatureOverride("REELS", FeatureOverride(mode = FeatureMode.BLOCK))

        vm.save()

        // At least the app-level default rule AND the Reels feature-override rule must have been
        // persisted -- this is the case a test that only checks the default rule would miss.
        assertTrue("expected 2+ persisted rules, got ${capturedRules.size}", capturedRules.size >= 2)
        assertTrue(
            "expected a feature-override rule for REELS",
            capturedRules.any { it.inAppFeatures == "REELS" }
        )
        capturedRules.forEach { rule ->
            assertFalse("rule ${rule.inAppFeatures ?: "<default>"} carried tabVanish=true", rule.tabVanish)
        }
    }

    @Test
    fun `followingSteer lands on the app-level rule and not on a feature-override rule`() = runTest {
        val capturedRules = mutableListOf<BlockRule>()
        coEvery { blockRuleRepository.deleteDirectRulesForPackage(any()) } just Runs
        coEvery { blockRuleRepository.addRule(capture(capturedRules)) } returns 1L

        val vm = viewModel()
        vm.setFollowingSteer(true)
        vm.setFeatureOverride("REELS", FeatureOverride(mode = FeatureMode.BLOCK))

        vm.save()

        val defaultRuleCaptured = capturedRules.first { it.inAppFeatures == null }
        val featureRuleCaptured = capturedRules.first { it.inAppFeatures == "REELS" }
        assertTrue(defaultRuleCaptured.followingSteer)
        assertFalse(featureRuleCaptured.followingSteer)
    }

    @Test
    fun `save with a scheduled override also writes tabVanish false on the scheduled rules`() = runTest {
        val capturedRules = mutableListOf<BlockRule>()
        coEvery { blockRuleRepository.deleteDirectRulesForPackage(any()) } just Runs
        coEvery { blockRuleRepository.addRule(capture(capturedRules)) } returns 1L

        val vm = viewModel()
        vm.setTabVanish(false)
        vm.setScheduledOverrideEnabled(true)
        vm.setScheduledFeatureOverride("REELS", FeatureOverride(mode = FeatureMode.BLOCK))

        vm.save()

        // App-level default + scheduled app-level + scheduled feature override = 3 rules.
        assertTrue("expected 3+ persisted rules, got ${capturedRules.size}", capturedRules.size >= 3)
        capturedRules.forEach { rule ->
            assertFalse(
                "rule (schedule=${rule.scheduleDays}, feature=${rule.inAppFeatures}) carried tabVanish=true",
                rule.tabVanish
            )
        }
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
}
