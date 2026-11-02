package com.astraedus.nudge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Logs `(package, category)` for every launchable app on the device, on demand:
 * `adb shell am broadcast -a dev.astraedus.nudge.debug.LOG_CATEGORIES -n dev.astraedus.nudge/com.astraedus.nudge.service.CategoryCoverageDebugReceiver`.
 * **Debug builds only**: this file is in the `debug` source set and its manifest entry is in
 * `src/debug/AndroidManifest.xml`, so neither exists in a release APK.
 *
 * Spec PR 6 (`docs/exec-plans/active/groups-categories-allowlist.md` 5.2) made measuring category
 * coverage the FIRST task, before deciding whether a curated `KnownAppCategories` fallback is
 * needed: developers set `android:appCategory` themselves and many apps don't. It reads the same
 * `ApplicationInfo.category` and the same [categoryOf] mapping the engine reads through
 * [AndroidPackageFacts], so what it reports is exactly what a category group would match.
 *
 * Each app is one `CategoryCoverage` log line (`pkg=<package> raw=<int> category=<AppCategory>`);
 * the per-category totals come back in the broadcast's result data and as the last line.
 */
class CategoryCoverageDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val pm = context.applicationContext.packageManager
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val summary = try {
                summarize(pm)
            } catch (t: Throwable) {
                "ERROR ${t.javaClass.simpleName}: ${t.message}"
            }
            Log.i(TAG, summary)
            try {
                pending.setResultData(summary)
            } catch (_: RuntimeException) {
                // Not an ordered broadcast; the log line above is the record.
            }
            pending.finish()
        }
    }

    private fun summarize(pm: PackageManager): String {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = pm.queryIntentActivities(launcher, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .sorted()
        val counts = sortedMapOf<String, Int>()
        for (pkg in packages) {
            val raw = try {
                pm.getApplicationInfo(pkg, 0).category
            } catch (_: PackageManager.NameNotFoundException) {
                continue
            }
            val mapped = categoryOf(raw)
            Log.i(TAG, "pkg=$pkg raw=$raw category=$mapped")
            counts.merge(mapped.name, 1, Int::plus)
        }
        return "total=${packages.size} " + counts.entries.joinToString(" ") { "${it.key}=${it.value}" }
    }

    private companion object {
        const val TAG = "CategoryCoverage"
    }
}
