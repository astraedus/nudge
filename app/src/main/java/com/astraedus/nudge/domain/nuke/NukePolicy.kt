package com.astraedus.nudge.domain.nuke

import com.astraedus.nudge.domain.lock.StrictModeEscapeGuard

/**
 * Every decision Nuke Mode makes, as pure functions: what is nuked, what may never be, when it may
 * be armed, and which changes weaken it. No Android; the device-specific half of the safety floor
 * (the user's actual launcher, dialer and keyboard) is resolved outside and handed in as
 * `deviceFloor`.
 */
object NukePolicy {

    /**
     * Packages Nuke must NEVER block, whatever is stored: the things a phone cannot be without, and
     * the ways out of Nudge itself.
     *
     * A blocker that could nuke the dialer could stop someone calling for help; one that could nuke
     * the launcher, SystemUI, Settings or the keyboard could leave the device unusable in a way no
     * emergency code can fix (the code is TYPED, so the keyboard must work). This is the static half;
     * the dynamic half (whatever launcher/dialer/keyboard THIS phone uses) arrives as `deviceFloor`.
     *
     * Deliberately generous: a package listed here that the user wanted nuked costs them one app on
     * their list; a package missing from here costs a phone call.
     */
    val STATIC_FLOOR: Set<String> = setOf(
        // The framework and system UI.
        "android",
        "com.android.systemui",
        // Home screens (the device's actual default launcher is added dynamically).
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "com.samsung.android.launcher",
        // Phone, dialer, in-call UI, emergency.
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.android.incallui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        // Installer and permission dialogs.
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        // Keyboards (the active one is added dynamically).
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin",
        "com.sec.android.inputmethod",
        "com.samsung.android.honeyboard"
    ) + StrictModeEscapeGuard.SETTINGS_PACKAGES

    /** Whether [packageName] is on the safety floor (static or this device's). */
    fun isProtected(packageName: String, deviceFloor: Set<String>): Boolean =
        packageName.isBlank() || packageName in STATIC_FLOOR || packageName in deviceFloor

    /** The part of [packages] Nuke is actually allowed to enforce. */
    fun enforceable(packages: Set<String>, deviceFloor: Set<String>): Set<String> =
        packages.filterTo(mutableSetOf()) { !isProtected(it, deviceFloor) }

    /**
     * THE count every screen shows as "Nuke is on · N apps": the apps Nuke actually blocks, so an
     * essential app that is still stored on the list is not counted. The stored list keeps it (it
     * counts again if this phone stops treating it as essential). The dashboard card and the Nuke
     * screen both read this with the same `EssentialApps` set, so the two can never disagree.
     */
    fun nukedCount(state: NukeState, deviceFloor: Set<String>): Int =
        enforceable(state.packages, deviceFloor).size

    /**
     * THE enforcement question: is [packageName] blocked by Nuke right now?
     *
     * True only while Nuke is on, the package is on the list, and the package is not on the safety
     * floor. The floor is re-applied HERE, not only in the picker: a stored list is data, and data
     * can arrive by routes the picker never saw (a future import, a hand-edited prefs file, a device
     * whose launcher changed after the app was picked).
     */
    fun isNuked(packageName: String, state: NukeState, deviceFloor: Set<String>): Boolean =
        state.active && packageName in state.packages && !isProtected(packageName, deviceFloor)

    /** Why Nuke cannot be armed right now, or null when it can. */
    fun armBlocker(state: NukeState, deviceFloor: Set<String>): ArmBlocker? = when {
        !state.hasKey -> ArmBlocker.NO_KEY
        enforceable(state.packages, deviceFloor).isEmpty() -> ArmBlocker.EMPTY_LIST
        else -> null
    }

    fun canArm(state: NukeState, deviceFloor: Set<String>): Boolean =
        armBlocker(state, deviceFloor) == null

    enum class ArmBlocker { NO_KEY, EMPTY_LIST }

    /**
     * Whether going from [old] to [new] WEAKENS Nuke, and therefore needs the key or the emergency
     * code. The Nuke sibling of `RuleWeakening.isWeakening` and `SettingsWeakening.requiresUnlock`.
     *
     * Nothing weakens Nuke while it is OFF: an idle list and key are just preferences. While it is
     * ON, three things do — turning it off, taking an app off the list, and changing or removing
     * the key (a new key is a new way out; a removed key ends Nuke outright). Adding apps and arming
     * are strengthening, and strengthening is always free in this app.
     */
    fun isWeakening(old: NukeState, new: NukeState): Boolean {
        if (!old.active) return false
        if (!new.active) return true
        if (!new.packages.containsAll(old.packages)) return true
        return old.keyHash != new.keyHash
    }

    /**
     * Whether turning the app's master toggle OFF must first get past Nuke.
     *
     * With Nuke on, the master switch is the obvious one-tap way around it: every enforcement path
     * sits behind `globalEnabled`. So it costs exactly what ending Nuke costs. Turning it ON is never
     * gated.
     */
    fun masterToggleOffRequiresUnlock(state: NukeState): Boolean = state.active
}
