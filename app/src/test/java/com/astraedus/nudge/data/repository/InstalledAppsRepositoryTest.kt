package com.astraedus.nudge.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class InstalledAppsRepositoryTest {

    // The repo constructs `Intent(Intent.ACTION_MAIN)` internally; in plain JVM unit
    // tests the Android Intent stub throws "not mocked", so mock the constructor.
    @Before
    fun setUp() {
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().addCategory(any()) } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkConstructor(Intent::class)
    }

    private fun resolveInfo(pkg: String, label: String): ResolveInfo {
        val info = mockk<ResolveInfo>(relaxed = true)
        info.activityInfo = ActivityInfo().apply { packageName = pkg }
        every { info.loadLabel(any()) } returns label
        return info
    }

    private fun newRepo(
        pm: PackageManager,
        ownPackage: String = "com.astraedus.nudge"
    ): InstalledAppsRepository {
        val context = mockk<Context>(relaxed = true)
        every { context.packageManager } returns pm
        every { context.packageName } returns ownPackage
        return InstalledAppsRepository(context)
    }

    @Test
    fun `getInstalledApps queries PackageManager once across two calls (cached)`() = runTest {
        val pm = mockk<PackageManager>(relaxed = true)
        every { pm.queryIntentActivities(any<Intent>(), any<Int>()) } returns listOf(
            resolveInfo("com.example.one", "One"),
            resolveInfo("com.example.two", "Two")
        )
        every { pm.getApplicationIcon(any<String>()) } returns mockk(relaxed = true)

        val repo = newRepo(pm)

        val first = repo.getInstalledApps()
        val second = repo.getInstalledApps()

        // Same cached instance returned, no second query.
        assertEquals(first, second)
        assertEquals(2, first.size)
        verify(exactly = 1) { pm.queryIntentActivities(any<Intent>(), any<Int>()) }
    }

    @Test
    fun `refresh forces a re-query`() = runTest {
        val pm = mockk<PackageManager>(relaxed = true)
        every { pm.queryIntentActivities(any<Intent>(), any<Int>()) } returns listOf(
            resolveInfo("com.example.one", "One")
        )
        every { pm.getApplicationIcon(any<String>()) } returns mockk(relaxed = true)

        val repo = newRepo(pm)

        repo.getInstalledApps()
        repo.refresh()
        repo.getInstalledApps()

        verify(exactly = 2) { pm.queryIntentActivities(any<Intent>(), any<Int>()) }
    }

    @Test
    fun `getInstalledApps filters own package and excluded system packages, sorts by name`() = runTest {
        val pm = mockk<PackageManager>(relaxed = true)
        every { pm.queryIntentActivities(any<Intent>(), any<Int>()) } returns listOf(
            resolveInfo("com.example.zebra", "Zebra"),
            resolveInfo("com.example.apple", "Apple"),
            resolveInfo("com.astraedus.nudge", "Nudge"),        // own package -> filtered
            resolveInfo("com.android.settings", "Settings")     // excluded -> filtered
        )
        every { pm.getApplicationIcon(any<String>()) } returns mockk(relaxed = true)

        val repo = newRepo(pm)
        val apps = repo.getInstalledApps()

        assertEquals(listOf("Apple", "Zebra"), apps.map { it.appName })
    }

    @Test
    fun `resolveAppName returns label and caches it per package`() = runTest {
        val pm = mockk<PackageManager>(relaxed = true)
        val appInfo = ApplicationInfo()
        every { pm.getApplicationInfo("com.example.one", 0) } returns appInfo
        every { pm.getApplicationLabel(appInfo) } returns "One"

        val repo = newRepo(pm)

        val first = repo.resolveAppName("com.example.one")
        val second = repo.resolveAppName("com.example.one")

        assertEquals("One", first)
        assertEquals("One", second)
        // Resolved once, then served from cache.
        verify(exactly = 1) { pm.getApplicationInfo("com.example.one", 0) }
    }

    @Test
    fun `resolveAppName falls back to package name when not found`() = runTest {
        val pm = mockk<PackageManager>(relaxed = true)
        every { pm.getApplicationInfo("com.missing.app", 0) } throws
            PackageManager.NameNotFoundException()

        val repo = newRepo(pm)

        assertEquals("com.missing.app", repo.resolveAppName("com.missing.app"))
    }
}
