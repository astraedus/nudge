package com.astraedus.nudge.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.domain.lock.StrictModeEscapeGuard
import com.astraedus.nudge.domain.nuke.NukeEnforcement
import com.astraedus.nudge.domain.nuke.NukePolicy
import com.astraedus.nudge.util.InputMethods
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This DEVICE's half of Nuke's safety floor: the launcher, dialer and keyboards this phone actually
 * uses, plus the package sets the accessibility service already treats as system surfaces. The
 * static half is [NukePolicy.STATIC_FLOOR].
 *
 * Resolved from PackageManager / TelecomManager / InputMethodManager, which are binder calls, so
 * [deviceFloor] must be called OFF the main thread. The answer is cached for [REFRESH_MS]: the user
 * can change their default launcher or keyboard, and a stale set here only errs toward the old
 * device's apps staying protected, never toward nuking a new launcher for longer than a few minutes
 * (and the static floor covers the common launchers regardless).
 *
 * Fails SOFT per source: a query that throws contributes nothing, and the rest still apply. There is
 * no failure mode in which this makes MORE apps nukable than the static floor allows.
 */
@Singleton
class NukeSafetyFloor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: NudgeLog
) {
    @Volatile private var cached: Set<String>? = null
    @Volatile private var cachedAt: Long = 0L

    fun deviceFloor(now: Long = System.currentTimeMillis()): Set<String> {
        val current = cached
        if (current != null && now - cachedAt < REFRESH_MS) return current
        val resolved = resolve()
        cached = resolved
        cachedAt = now
        return resolved
    }

    /** Drop the cache (e.g. after the user changes a default app). */
    fun invalidate() {
        cached = null
    }

    private fun resolve(): Set<String> {
        val floor = mutableSetOf(context.packageName)
        floor += NudgeAccessibilityService.SYSTEM_PACKAGES
        floor += NudgeAccessibilityService.IME_PACKAGES
        floor += StrictModeEscapeGuard.SETTINGS_PACKAGES
        floor += safely("launchers") { homePackages() }
        floor += safely("dialer") { setOfNotNull(defaultDialer()) }
        floor += safely("keyboards") { keyboardPackages() }
        return floor.filterTo(mutableSetOf()) { it.isNotBlank() }
    }

    private fun homePackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager
            .queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNullTo(mutableSetOf()) { it.activityInfo?.packageName }
    }

    /** `getDefaultDialerPackage` is API 23; minSdk is 26, so no version check is owed. */
    private fun defaultDialer(): String? =
        (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)
            ?.defaultDialerPackage

    /** Shared with the event classifier's transient set, so the two cannot disagree. */
    private fun keyboardPackages(): Set<String> = InputMethods.enabledPackages(context)

    private inline fun safely(label: String, block: () -> Set<String>): Set<String> = try {
        block()
    } catch (e: Exception) {
        logger.w("nuke safety floor: could not resolve $label", e)
        emptySet()
    }

    companion object {
        private const val REFRESH_MS = 5 * 60_000L
    }
}

/**
 * The production [NukeEnforcement]: the persisted state, through [NukePolicy.isNuked], with this
 * device's floor. Reads the floor only when Nuke is actually on, so an idle Nuke costs the
 * evaluation hot path one cached DataStore read.
 */
@Singleton
class NukeEnforcementSource @Inject constructor(
    private val preferences: NudgePreferences,
    private val floor: NukeSafetyFloor
) : NukeEnforcement {
    override suspend fun isNuked(packageName: String): Boolean {
        val state = preferences.nukeState.first()
        if (!state.active) return false
        return NukePolicy.isNuked(packageName, state, floor.deviceFloor())
    }
}
