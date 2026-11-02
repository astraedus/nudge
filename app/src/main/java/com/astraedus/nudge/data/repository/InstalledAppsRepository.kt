package com.astraedus.nudge.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.service.categoryOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
        val icon: Drawable?,
        /**
         * `ApplicationInfo.category`, mapped (spec 5.2): what a category group matches. UNDEFINED
         * for a row that is not an installed app (a placeholder for an uninstalled member), which
         * matches no category.
         */
        val category: AppCategory = AppCategory.UNDEFINED
    )

    private val _changes = MutableStateFlow(0L)

    /**
     * A version counter that bumps on every [refresh] (a package was added, removed or changed).
     * Collectors that show the installed list re-read [getInstalledApps] when it moves.
     */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    @Volatile
    private var cachedApps: List<AppInfo>? = null
    private val loadMutex = Mutex()

    // Per-package name cache for resolveAppName (covers packages not in the launcher
    // list, e.g. apps with usage history but no launcher activity).
    private val nameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    // Per-package icon cache for resolveIcon. Misses are tracked separately because a
    // ConcurrentHashMap cannot hold a null value, and an uninstalled package that still
    // has usage history would otherwise re-hit PackageManager on every screen rebuild.
    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, Drawable>()
    private val iconMisses: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

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
                    icon = icon,
                    // Already on the ResolveInfo: no extra PackageManager call per app.
                    category = categoryOf(activityInfo.applicationInfo?.category ?: -1)
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
     * Resolve a single package's launcher icon, or null when the app is not installed
     * (uninstalled apps still appear in usage history, so callers must render without one).
     *
     * Same contract as [resolveAppName]: PackageManager work off the main thread, cached
     * per package.
     */
    suspend fun resolveIcon(packageName: String): Drawable? {
        iconCache[packageName]?.let { return it }
        if (packageName in iconMisses) return null
        val resolved = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
        if (resolved != null) iconCache[packageName] = resolved else iconMisses.add(packageName)
        return resolved
    }

    /**
     * Invalidate every cache so the next [getInstalledApps] / [resolveAppName] /
     * [resolveIcon] re-queries PackageManager, and bump [changes]. Called by the package-change
     * receiver in `AndroidPackageFacts` after an app is installed/uninstalled/changed.
     */
    fun refresh() {
        cachedApps = null
        nameCache.clear()
        iconCache.clear()
        iconMisses.clear()
        _changes.update { it + 1 }
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
