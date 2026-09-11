package com.astraedus.nudge.util

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * "Has the user granted Usage Access?" — the ONE answer, for every caller.
 *
 * It used to be two byte-identical copies, one in `ScreenTimeProvider` (the dashboard's
 * screen-time read) and one private to `SettingsScreen` (the permission row). Both called
 * `AppOpsManager.unsafeCheckOpNoThrow`, **which was added in API 29 while this app declares
 * `minSdk = 26`** — so on Android 8.0, 8.1 and 9 both sites threw `NoSuchMethodError` rather
 * than returning false. Not a wrong answer: a crash, on the home screen and in Settings.
 *
 * Two lessons are baked into where this function lives:
 *
 * 1. **The duplication is what made it two bugs instead of one.** A single helper means the
 *    next platform-level question about usage access is asked in one place and guarded once.
 *    It sits in `util` rather than `ui/PermissionUtils.kt` because `data` may not import `ui`
 *    (the module's dependency rule is `ui -> domain <- data`), and it cannot sit in `domain`
 *    because `domain` carries no `android.*` imports at all.
 * 2. **No JVM test can see this defect and no device we own can reproduce it** — the bench
 *    Pixel 3 is API 31. The check that catches it is Android Lint's `NewApi`, which is why
 *    `lintDebug` is now part of the CI gate rather than something anyone remembers to run.
 *    See `docs/TESTING.md`.
 *
 * Below API 29 this uses `checkOpNoThrow`, which is deprecated in favour of the "unsafe"
 * spelling but has existed since API 19 and has identical semantics for this op. The
 * deprecation is the whole reason the newer name exists; it is not a behaviour difference.
 */
fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}
