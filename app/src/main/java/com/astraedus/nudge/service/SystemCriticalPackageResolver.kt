package com.astraedus.nudge.service

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import com.astraedus.nudge.domain.lock.StrictModeEscapeGuard
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE SAFETY FLOOR: the set of packages that must stay reachable no matter what Nudge is enforcing.
 *
 * Per-app blocking makes a missed package annoying. **Lights Off makes it catastrophic** — it blocks
 * *every* app for a scheduled window, so a launcher or dialer we failed to recognise means a user who
 * cannot reach their home screen or phone their mother at 2am. `SYSTEM_PACKAGES` in
 * [NudgeAccessibilityService] is a hardcoded OEM *guess* list; on a device whose launcher or dialer
 * isn't in it (any non-Pixel/Samsung OEM, any third-party launcher) that guess is simply wrong.
 *
 * So this resolver asks the SYSTEM what the current defaults actually are:
 *  - **launcher** — every activity answering `ACTION_MAIN` + `CATEGORY_HOME`, plus the resolved
 *    default. Every installed launcher is included, not just the default: switching launcher
 *    mid-window must not be able to strand the user.
 *  - **dialer** — [TelecomManager.getDefaultDialerPackage], plus *system* `ACTION_DIAL` handlers
 *    (system-only so a third-party caller-ID app doesn't silently become unblockable).
 *  - **keyboards** — every installed IME, so the user can always type (including the unlock
 *    challenge).
 *  - **settings** — the resolved `ACTION_SETTINGS` package, plus
 *    [StrictModeEscapeGuard.SETTINGS_PACKAGES] (the same OEM list the escape guard already trusts).
 *
 * …and unions that with a static floor of emergency-dialer / telephony / in-call / system-UI /
 * installer / permission-controller packages and Nudge itself. The static floor is what we fall back
 * to if PackageManager fails, so **the resolved set is never empty**.
 *
 * ## Invariants (locked by `SystemCriticalPackageResolverTest`)
 *  1. Never empty, and always contains Nudge's own package.
 *  2. Derived ONLY from the platform + this file — never from a user's Lights Off whitelist, so no
 *     amount of mis-editing settings can remove a package from the floor.
 *  3. Checked as a service-level short-circuit BEFORE `BlockEngine`, so a floor package can never
 *     produce a block decision at all (see [NudgeAccessibilityService.isAlwaysAllowedPackage]).
 *  4. A resolution failure widens the floor or leaves it unchanged; it never narrows it.
 *
 * It is a deliberately reviewable file in a public repo: "the apps that stay reachable during Lights
 * Off are listed here — go read it."
 */
@Singleton
class SystemCriticalPackageResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Hot-path readable snapshot. Initialised to the static floor so [isSystemCritical] is correct
     * (if conservative) from the very first accessibility event, before any resolution has run.
     */
    @Volatile
    private var cached: Set<String> = staticFloor(context.packageName)

    @Volatile
    private var lastResolvedAtMs: Long = 0L

    private val refreshMutex = Mutex()

    /**
     * Non-blocking check for the accessibility hot path: never touches PackageManager, never
     * suspends, reads one volatile reference.
     */
    fun isSystemCritical(packageName: String): Boolean = packageName in cached

    /** Current floor — for diagnostics and for showing the user what stays reachable. */
    fun packages(): Set<String> = cached

    /**
     * Re-resolve the dynamic parts (launcher / dialer / IME / settings defaults can change while the
     * service lives). Runs PackageManager work off the main thread; single-flighted by [refreshMutex].
     *
     * Never throws and never narrows the floor: each group is resolved independently so one OEM
     * misbehaving cannot cost us the others, and everything is unioned onto the static floor.
     */
    suspend fun refresh(nowMs: Long = System.currentTimeMillis()) {
        refreshMutex.withLock {
            val resolved = withContext(Dispatchers.IO) { resolveAll() }
            cached = resolved
            lastResolvedAtMs = nowMs
        }
    }

    /** [refresh] only if the cache has never been built or is older than [REFRESH_INTERVAL_MS]. */
    suspend fun refreshIfNeeded(nowMs: Long = System.currentTimeMillis()) {
        if (lastResolvedAtMs != 0L && (nowMs - lastResolvedAtMs) < REFRESH_INTERVAL_MS) return
        refresh(nowMs)
    }

    private fun resolveAll(): Set<String> = buildCriticalSet(
        ownPackage = context.packageName,
        launcherPackages = resolveLaunchers(),
        dialerPackages = resolveDialers(),
        imePackages = resolveInputMethods(),
        settingsPackages = resolveSettings()
    )

    /**
     * Every launcher on the device. `queryIntentActivities` is preferred over the single
     * `resolveActivity` answer because when no default launcher is set, `resolveActivity` returns the
     * system *resolver* activity rather than a real launcher — which would leave the user's actual
     * home screen blockable.
     */
    private fun resolveLaunchers(): Set<String> {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val packages = mutableSetOf<String>()
        try {
            pm.queryIntentActivities(home, 0).forEach { info ->
                info.activityInfo?.packageName?.let { packages.add(it) }
            }
        } catch (_: Exception) {
            // fall through to the single-default attempt below
        }
        try {
            pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
                ?.let { packages.add(it) }
        } catch (_: Exception) {
            // static floor still covers the common launchers
        }
        return packages
    }

    /**
     * The default dialer plus system dial handlers. Third-party `ACTION_DIAL` handlers (caller-ID
     * apps, messengers) are deliberately excluded — they are not the escape route we owe the user,
     * and silently making a social app unblockable would be a hole in the lockdown.
     */
    private fun resolveDialers(): Set<String> {
        val packages = mutableSetOf<String>()
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecom?.defaultDialerPackage?.let { packages.add(it) }
        } catch (_: Exception) {
            // static floor still covers AOSP/OEM dialers
        }
        try {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_DIAL), 0).forEach { info ->
                val activity = info.activityInfo ?: return@forEach
                if (isSystemApp(activity.applicationInfo)) packages.add(activity.packageName)
            }
        } catch (_: Exception) {
            // ignore
        }
        return packages
    }

    /** Every installed IME — the user must always be able to type, including into the unlock challenge. */
    private fun resolveInputMethods(): Set<String> {
        val packages = mutableSetOf<String>()
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                ?: return packages
            imm.inputMethodList.forEach { info -> packages.add(info.packageName) }
            imm.enabledInputMethodList.forEach { info -> packages.add(info.packageName) }
        } catch (_: Exception) {
            // static floor covers the stock keyboards
        }
        return packages
    }

    private fun resolveSettings(): Set<String> {
        val packages = mutableSetOf<String>()
        try {
            context.packageManager
                .resolveActivity(Intent(Settings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
                ?.let { packages.add(it) }
        } catch (_: Exception) {
            // StrictModeEscapeGuard.SETTINGS_PACKAGES still applies
        }
        return packages
    }

    private fun isSystemApp(info: ApplicationInfo?): Boolean {
        if (info == null) return false
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return (info.flags and systemFlags) != 0
    }

    companion object {
        /** How long a resolved floor is trusted before we re-ask the platform. */
        const val REFRESH_INTERVAL_MS = 15L * 60L * 1000L

        /**
         * Packages that are always allowed regardless of what the platform tells us — the fallback if
         * dynamic resolution fails, and the cover for surfaces that are not "apps" a user launches
         * (in-call UI, permission prompts, the emergency dialer).
         *
         * Not a substitute for dynamic resolution: an OEM whose launcher/dialer is not listed here is
         * exactly the case the resolver exists to catch.
         */
        val STATIC_ALWAYS_ALLOWED: Set<String> = setOf(
            // System UI / platform surfaces
            "android",                                  // system + intent resolver
            "com.android.systemui",
            "com.android.intentresolver",               // Android 14+ share/resolver sheet
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",

            // Telephony / emergency — reaching help must never depend on our resolution working
            "com.android.phone",                        // emergency dialer + telephony UI
            "com.android.server.telecom",
            "com.android.incallui",
            "com.samsung.android.incallui",
            "com.android.emergency",                    // Pixel "Emergency information / SOS"
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",

            // Stock keyboards (dynamic IME resolution is the real answer; this is the net)
            "com.android.inputmethod.latin",
            "com.google.android.inputmethod.latin",
            "com.sec.android.inputmethod",

            // Launchers (dynamic CATEGORY_HOME resolution is the real answer; this is the net)
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.samsung.android.launcher",

            // Settings search entry point on Pixel
            "com.android.settings.intelligence"
        )

        /**
         * The floor available with zero platform help: static allow-list + the settings packages the
         * Strict Mode escape guard already trusts + Nudge itself.
         */
        fun staticFloor(ownPackage: String): Set<String> =
            STATIC_ALWAYS_ALLOWED + StrictModeEscapeGuard.SETTINGS_PACKAGES + ownPackage

        /**
         * Pure assembly of the floor from the static list plus whatever the platform reported. Kept
         * separate from the Android calls so the invariants (never empty, always contains our own
         * package, blanks dropped, purely additive) are unit-testable without a device.
         */
        fun buildCriticalSet(
            ownPackage: String,
            launcherPackages: Set<String>,
            dialerPackages: Set<String>,
            imePackages: Set<String>,
            settingsPackages: Set<String>
        ): Set<String> = buildSet {
            addAll(staticFloor(ownPackage))
            addAll(launcherPackages)
            addAll(dialerPackages)
            addAll(imePackages)
            addAll(settingsPackages)
            removeAll { it.isBlank() }
        }
    }
}
