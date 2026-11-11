package com.astraedus.nudge.service

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.astraedus.nudge.BuildConfig
import com.astraedus.nudge.domain.bounce.AppCountability
import com.astraedus.nudge.domain.bounce.Countability
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.domain.packages.FakePackageFacts
import com.astraedus.nudge.testing.FakeInstalledAppsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `homeScreen` fact goes through the launcher set's own rule (#181 review).
 *
 * AOSP Settings declares `Settings$FallbackHome` with CATEGORY_HOME, so a raw scoped HOME query
 * resolves for it on every stock phone. Read raw, Settings would never count as an app and every
 * trip to it would read as going home. Every package the launcher-set rule excludes is taken from
 * the production constants, never retyped.
 */
class BounceHomeScreenFactTest {

    private val own = BuildConfig.APPLICATION_ID

    /** Every package `sanitizeLauncherPackages` refuses, from the production constants. */
    private val neverHome: Set<String> = NudgeAccessibilityService.NEVER_LAUNCHER_PACKAGES +
        NudgeAccessibilityService.IME_PACKAGES +
        NudgeAccessibilityService.FRAMEWORK_PACKAGE +
        own

    private val realLauncher = BounceHarness.PIXEL_LAUNCHER
    private val thirdPartyLauncher = "com.teslacoilsw.launcher"

    @Test
    fun `nothing the launcher set refuses is a home screen, and none of them costs the query`() {
        val asked = mutableListOf<String>()
        val wrong = neverHome.filter { pkg ->
            isHomeScreenPackage(pkg, own) { asked += it; true }
        }
        assertEquals("read as a home screen though the launcher set refuses them", emptyList<String>(), wrong)
        assertEquals("the binder query ran for an excluded package", emptyList<String>(), asked)
    }

    @Test
    fun `a launcher is a home screen only when its HOME query resolves`() {
        for (launcher in listOf(realLauncher, thirdPartyLauncher)) {
            assertTrue(launcher, isHomeScreenPackage(launcher, own) { true })
            assertFalse(launcher, isHomeScreenPackage(launcher, own) { false })
        }
    }

    @Test
    fun `the fact and the launcher set agree on every package`() {
        for (pkg in neverHome + realLauncher + thirdPartyLauncher + "com.example.app") {
            assertEquals(
                pkg,
                pkg in NudgeAccessibilityService.sanitizeLauncherPackages(listOf(pkg), own),
                isHomeScreenPackage(pkg, own) { true }
            )
        }
    }

    @Test
    fun `Settings with a raw HOME resolution and a launcher entry COUNTS, and is not going home`() {
        for (pkg in NudgeAccessibilityService.NEVER_LAUNCHER_PACKAGES) {
            val facts = FakePackageFacts(launchable = setOf(pkg), home = setOf(pkg), caches = true)
            facts.homeScreenRule = { p, resolves -> isHomeScreenPackage(p, own) { resolves } }
            val countability = AppCountability(facts, homePackages = { emptySet() }, requestLookup = {})
            runBlocking { facts.factsFor(pkg) }
            assertEquals(pkg, Countability.COUNTS, countability(pkg))
        }
    }

    @Test
    fun `counterfactual - the raw query alone reads Settings as HOME`() {
        val settings = NudgeAccessibilityService.NEVER_LAUNCHER_PACKAGES.first()
        val facts = FakePackageFacts(launchable = setOf(settings), home = setOf(settings), caches = true)
        val countability = AppCountability(facts, homePackages = { emptySet() }, requestLookup = {})
        runBlocking { facts.factsFor(settings) }
        assertEquals(Countability.HOME, countability(settings))
    }

    @Test
    fun `AndroidPackageFacts applies the rule - an excluded package is never queried and never home`() {
        val pm = mockk<PackageManager>(relaxed = true)
        every { pm.getApplicationInfo(any<String>(), 0) } returns mockk<ApplicationInfo>(relaxed = true)
        every { pm.getLaunchIntentForPackage(any()) } returns mockk<Intent>()
        val context = mockk<Context>(relaxed = true)
        every { context.packageManager } returns pm
        every { context.packageName } returns own
        val facts = AndroidPackageFacts(
            context = context,
            io = Dispatchers.Unconfined,
            logger = mockk<NudgeLog>(relaxed = true),
            installedApps = dagger.Lazy { FakeInstalledAppsRepository() }
        )
        for (pkg in neverHome) {
            val fact = runBlocking { facts.factsFor(pkg) }
            assertTrue("$pkg should read as launchable (the mock says so)", fact.launchable)
            assertFalse("$pkg read as a home screen", fact.homeScreen)
        }
        verify(exactly = 0) { pm.queryIntentActivities(any(), any<Int>()) }
    }
}
