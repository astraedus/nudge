package com.astraedus.nudge.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the user-visible installed-app list (labels + icons) from PackageManager.
 *
 * PackageManager work is EXPENSIVE: querying launcher activities then calling
 * [ResolveInfo.loadLabel] + [PackageManager.getApplicationIcon] for every app is
 * hundreds of ms to >1s on a low-end device (Pixel 3). To keep navigation snappy:
 *
 *  - all PackageManager access runs off the main thread (Dispatchers.IO),
 *  - the computed list (and resolved names) are cached in-memory in this @Singleton,
 *    so repeat navigations are instant,
 *  - concurrent first-loads are guarded by a [Mutex] so we query exactly once
 *    (same pattern as [ContentFilterRepository]).
 *
 * Call [refresh] to invalidate the cache (e.g. after an app is installed/removed).
 */
@Singleton
class InstalledAppsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    data class AppInfo(
        val packageName: String,
        val appName: String,
        val icon: Drawable?
    )

    @Volatile
    private var cachedApps: List<AppInfo>? = null
    private val loadMutex = Mutex()

    // Per-package name cache for resolveAppName (covers packages not in the launcher
    // list, e.g. apps with usage history but no launcher activity).
    private val nameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Query PackageManager for user-visible apps (those with a LAUNCHER intent).
     * Filters out our own package and critical system packages.
     *
     * Cached after first call; subsequent calls return the cache without re-querying.
     * Runs off the main thread.
     */
    suspend fun getInstalledApps(): List<AppInfo> {
        cachedApps?.let { return it }
        return loadMutex.withLock {
            cachedApps?.let { return it }
            val loaded = withContext(Dispatchers.IO) { queryInstalledApps() }
            cachedApps = loaded
            loaded
        }
    }

    private fun queryInstalledApps(): List<AppInfo> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos: List<ResolveInfo> = pm.queryIntentActivities(launcherIntent, 0)

        val ownPackage = context.packageName

        return resolveInfos
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName

                // Filter out our own app
                if (packageName == ownPackage) return@mapNotNull null

                // Filter out critical system packages
                if (packageName in EXCLUDED_PACKAGES) return@mapNotNull null

                val appName = resolveInfo.loadLabel(pm).toString()
                val icon = try {
                    pm.getApplicationIcon(packageName)
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }

                AppInfo(
                    packageName = packageName,
                    appName = appName,
                    icon = icon
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }

    /**
     * Resolve a single package's display label. Cached per-package; runs off the
     * main thread on the first lookup for a given package.
     */
    suspend fun resolveAppName(packageName: String): String {
        nameCache[packageName]?.let { return it }
        val resolved = withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                packageName
            }
        }
        nameCache[packageName] = resolved
        return resolved
    }

    /**
     * Invalidate both caches so the next [getInstalledApps] / [resolveAppName] re-queries
     * PackageManager. Call after an app is installed/uninstalled.
     */
    fun refresh() {
        cachedApps = null
        nameCache.clear()
    }

    companion object {
        private val EXCLUDED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.android.permissioncontroller",
        )
    }
}
