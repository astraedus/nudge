package com.astraedus.nudge.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InstalledAppsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    data class AppInfo(
        val packageName: String,
        val appName: String,
        val icon: Drawable?
    )

    /**
     * Query PackageManager for user-visible apps (those with a LAUNCHER intent).
     * Filters out our own package and critical system packages.
     */
    fun getInstalledApps(): List<AppInfo> {
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

    companion object {
        private val EXCLUDED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.android.permissioncontroller",
        )
    }
}
