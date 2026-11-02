package com.astraedus.nudge.testing

import android.graphics.drawable.Drawable
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.InstalledAppsSource
import kotlinx.coroutines.Dispatchers

/**
 * The REAL [InstalledAppsRepository] over an in-memory device, for JVM tests.
 *
 * Its caching, its name fallback (an unlabelled package shows its package name), [refresh] and
 * `changes` are all production code; only the PackageManager reads under it ([InstalledAppsSource])
 * are swapped for [device]. Runs on [Dispatchers.Unconfined], so no real IO thread is involved.
 *
 * ```
 * val device = FakeInstalledApps(anApp("com.instagram.android", "Instagram"))
 * val repo = FakeInstalledAppsRepository(device)
 * device.install(anApp("com.new.app")); repo.refresh()   // as the package-change receiver does
 * ```
 */
fun FakeInstalledAppsRepository(
    device: FakeInstalledApps = FakeInstalledApps()
): InstalledAppsRepository = InstalledAppsRepository(device, Dispatchers.Unconfined)

/** Convenience: a repository whose device has exactly [apps] installed. */
fun FakeInstalledAppsRepository(vararg apps: InstalledAppsRepository.AppInfo): InstalledAppsRepository =
    FakeInstalledAppsRepository(FakeInstalledApps(*apps))

/**
 * The phone's installed apps, in memory. [launcherApps] lists them in the order PackageManager's
 * source does (sorted by label, case-insensitive, one row per package). [label]/[icon] answer for
 * installed apps only, plus any package given a label by [labelOnly] (an app with no launcher
 * activity, which PackageManager can still name).
 */
class FakeInstalledApps(vararg apps: InstalledAppsRepository.AppInfo) : InstalledAppsSource {
    private val installed = linkedMapOf<String, InstalledAppsRepository.AppInfo>()
    private val extraLabels = mutableMapOf<String, String>()

    /** How many times the repository actually reached the "PackageManager" for the app list. */
    var launcherQueries: Int = 0
        private set

    init {
        apps.forEach { install(it) }
    }

    fun install(app: InstalledAppsRepository.AppInfo) {
        installed[app.packageName] = app
    }

    fun uninstall(packageName: String) {
        installed.remove(packageName)
        extraLabels.remove(packageName)
    }

    /** A package PackageManager can label but that has no launcher activity (so it is not listed). */
    fun labelOnly(packageName: String, label: String) {
        extraLabels[packageName] = label
    }

    override fun launcherApps(): List<InstalledAppsRepository.AppInfo> {
        launcherQueries++
        return installed.values.sortedBy { it.appName.lowercase() }
    }

    override fun label(packageName: String): String? =
        installed[packageName]?.appName ?: extraLabels[packageName]

    override fun icon(packageName: String): Drawable? = installed[packageName]?.icon
}
