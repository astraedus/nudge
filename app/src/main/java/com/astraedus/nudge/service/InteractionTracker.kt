package com.astraedus.nudge.service

import androidx.annotation.VisibleForTesting
import com.astraedus.nudge.domain.interaction.CountMode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks per-app session state in memory: interaction counts (taps and scrolls) and the
 * foreground-time baseline that the time-based auto-kick measures against.
 *
 * Session state resets when the foreground app changes AND the user has been
 * away from the app longer than [SESSION_EXPIRY_MS]. Returning within the
 * expiry window preserves the session so users cannot game auto-kick
 * by closing and reopening an app. **Both auto-kick triggers share this one definition of a
 * session** — the interaction count and the foreground-time baseline are reset together, always.
 *
 * Daily totals persist until [resetDaily] is called (service restart or midnight).
 * All fields are in-memory only -- no DB writes on every interaction.
 */
@Singleton
class InteractionTracker @Inject constructor() {

    companion object {
        /** Session counter persists if the user returns within this window. */
        const val SESSION_EXPIRY_MS = 5L * 60L * 1000L
    }

    data class SessionCount(
        val packageName: String,
        val sessionCount: Int = 0,
        val dailyTotal: Int = 0,
        /** What this session's number is counting. See [recordInteractions]. */
        val mode: CountMode = CountMode.TAPS,
        /**
         * The caption the counter overlay should show, or null for the mode's generic word.
         *
         * Lives HERE, with the count it describes, because it used to live on `InteractionHandler`
         * and be cleared on a different schedule: the label went when the SITTING changed (2 min)
         * while the count and mode reset on this class's 5-minute expiry and on [resetSession]. The
         * two drifted in both directions -- a stale "shorts" caption over a fresh TAPS count, and a
         * generic "scrolls" caption over a session that was still watching reels. A caption and the
         * number under it are one fact and now have one lifetime.
         */
        val label: String? = null
    )

    /** Override in tests to control time. */
    @VisibleForTesting
    internal var clock: () -> Long = System::currentTimeMillis

    private val sessionCounts = mutableMapOf<String, Int>()

    /** What each package's current session is counting. See [recordInteractions]. */
    private val sessionModes = mutableMapOf<String, CountMode>()

    /**
     * The in-app feature caption for each package's current session ("reels" / "shorts" /
     * "videos"), set from node-tree detection. Cleared wherever the session resets, which is the
     * whole point of it living here rather than on the handler.
     */
    private val sessionLabels = mutableMapOf<String, String>()

    /**
     * Daily interaction totals, keyed by package AND [CountMode].
     *
     * Per mode because the overlay renders this as "today: N" directly beneath a session count that
     * means one unit. A single total mixes them: three taps then five reels rendered "today: 8",
     * which is the same lie -- adding reels to taps -- that the session's mode rule exists to
     * prevent, surviving one field over.
     */
    private val dailyTotals = mutableMapOf<Pair<String, CountMode>, Int>()
    private var currentPackage: String? = null

    /** Package -> epoch ms when the user last left the app. */
    private val lastLeftAt = mutableMapOf<String, Long>()

    /** Package -> epoch ms when cooldown expires. Set after auto-kick. */
    private val cooldownUntil = mutableMapOf<String, Long>()

    /**
     * Package -> the foreground-time reading (see `UsageProvider.getDailyForegroundTimeMs`) taken
     * when the current session began. The time-based auto-kick measures the delta against this, so
     * only time actually spent in the app counts — never time on another app or with the screen
     * off. Absent = this session has no baseline yet and the next reading establishes one.
     */
    private val sessionUsageBaseline = mutableMapOf<String, Long>()

    /** Called when the foreground app changes. Resets the session (interaction count AND the
     *  foreground-time baseline) only if the user has been away longer than [SESSION_EXPIRY_MS]
     *  (and not in cooldown). */
    fun onAppChanged(packageName: String) {
        if (packageName != currentPackage) {
            // Record when we left the current app
            currentPackage?.let { lastLeftAt[it] = clock() }

            // Only reset session state if away long enough (and not in cooldown)
            if (!isInCooldown(packageName)) {
                val leftAt = lastLeftAt[packageName]
                val now = clock()
                if (leftAt == null || (now - leftAt) >= SESSION_EXPIRY_MS) {
                    sessionCounts[packageName] = 0
                    sessionModes.remove(packageName)
                    sessionLabels.remove(packageName)
                    sessionUsageBaseline.remove(packageName)
                }
            }
            lastLeftAt.remove(packageName)
            currentPackage = packageName
        }
    }

    /** Records a single tap and returns the updated counts. */
    fun recordInteraction(packageName: String): SessionCount =
        recordInteractions(packageName, 1, CountMode.TAPS)

    /**
     * Records [count] interactions of [mode] and returns the updated counts.
     *
     * A single scroll event can legitimately represent more than one item consumed — a fling moves
     * the adapter several positions — and [com.astraedus.nudge.domain.interaction.InteractionCounter]
     * is what decides how many. Non-positive counts are ignored rather than clamped silently to one:
     * "this event was not an interaction" is the common answer and must cost nothing.
     *
     * ## One session counts ONE unit
     *
     * Reels watched and buttons tapped are different things, and a number that silently adds them is
     * how "opening the comments counted as a tap" comes back wearing a different name — the overlay
     * would read "7 reels" after five swipes and two taps, and auto-kick would fire two items early.
     * So a session has a [CountMode], and only interactions of that mode are recorded:
     *
     *  - [CountMode.ITEMS] wins. The first item consumed switches a tap-counting session over, and
     *    **resets the count**, because the taps recorded so far were a different unit and adding
     *    them to a reel count would be the exact lie this avoids. The alternative — locking the mode
     *    to whatever came first — would let one stray tap on entering an app silence the reel
     *    counter for the whole sitting.
     *  - Once a session counts items, taps are dropped entirely rather than tallied elsewhere. A
     *    second number nothing displays is state that can only go stale.
     */
    fun recordInteractions(packageName: String, count: Int, mode: CountMode): SessionCount {
        val currentMode = sessionModes[packageName] ?: CountMode.TAPS
        if (count <= 0 || (mode == CountMode.TAPS && currentMode == CountMode.ITEMS)) {
            return snapshot(packageName)
        }
        val promoting = mode == CountMode.ITEMS && currentMode != CountMode.ITEMS
        val previousSession = if (promoting) 0 else (sessionCounts[packageName] ?: 0)
        sessionModes[packageName] = mode

        val session = previousSession + count
        sessionCounts[packageName] = session
        val dailyKey = packageName to mode
        val daily = (dailyTotals[dailyKey] ?: 0) + count
        dailyTotals[dailyKey] = daily
        return SessionCount(packageName, session, daily, mode, sessionLabels[packageName])
    }

    /**
     * Everything the overlay needs for [packageName] right now, with no side effects.
     *
     * One accessor rather than four, because the count, the mode, the label and the daily total have
     * to agree -- the daily total is keyed by the session's CURRENT mode, so reading them separately
     * is how a caller ends up rendering an items count above a taps total.
     */
    fun snapshot(packageName: String): SessionCount {
        val mode = sessionModes[packageName] ?: CountMode.TAPS
        return SessionCount(
            packageName = packageName,
            sessionCount = sessionCounts[packageName] ?: 0,
            dailyTotal = dailyTotals[packageName to mode] ?: 0,
            mode = mode,
            label = sessionLabels[packageName]
        )
    }

    /**
     * Record the in-app feature caption for this package's current session.
     *
     * Deliberately does NOT start a session or touch the count: detection can fire before the user
     * has interacted at all, and a label is a description of a session, never evidence one exists.
     */
    fun setSessionLabel(packageName: String, label: String) {
        sessionLabels[packageName] = label
    }

    fun getSessionCount(packageName: String): Int = sessionCounts[packageName] ?: 0

    /** Today's total for [packageName] in the unit its CURRENT session is counting. */
    fun getDailyTotal(packageName: String): Int =
        dailyTotals[packageName to (sessionModes[packageName] ?: CountMode.TAPS)] ?: 0

    fun resetSession(packageName: String) {
        sessionCounts[packageName] = 0
        sessionModes.remove(packageName)
        sessionLabels.remove(packageName)
        sessionUsageBaseline.remove(packageName)
        lastLeftAt.remove(packageName)
        if (currentPackage == packageName) {
            currentPackage = null
        }
    }

    /** Clear daily totals -- called at midnight or service start. */
    fun resetDaily() {
        dailyTotals.clear()
        lastLeftAt.clear()
    }

    // --- Session foreground-time baseline (time-based auto-kick) ---

    /**
     * The foreground-time reading recorded at the start of this package's current session, or null
     * if the session has no baseline yet.
     */
    fun getSessionUsageBaseline(packageName: String): Long? = sessionUsageBaseline[packageName]

    /**
     * Records [usageMs] as the current session's baseline, replacing any previous one. Called when
     * a session first gets a reading, and again after a backwards reading (a day rollover resets
     * the daily foreground total, which would otherwise make the delta negative).
     */
    fun setSessionUsageBaseline(packageName: String, usageMs: Long) {
        sessionUsageBaseline[packageName] = usageMs
    }

    // --- Cooldown ---

    /** Set a cooldown that expires [durationMs] from now. */
    fun setCooldown(packageName: String, durationMs: Long) {
        cooldownUntil[packageName] = clock() + durationMs
    }

    /** Returns true if the package is currently in cooldown. */
    fun isInCooldown(packageName: String): Boolean {
        val until = cooldownUntil[packageName] ?: return false
        if (clock() >= until) {
            cooldownUntil.remove(packageName)
            return false
        }
        return true
    }

    /** Returns remaining cooldown time in ms, or 0 if not in cooldown. */
    fun getCooldownRemainingMs(packageName: String): Long {
        val until = cooldownUntil[packageName] ?: return 0L
        val remaining = until - clock()
        if (remaining <= 0L) {
            cooldownUntil.remove(packageName)
            return 0L
        }
        return remaining
    }

    /** Clear the cooldown for a package. */
    fun clearCooldown(packageName: String) {
        cooldownUntil.remove(packageName)
    }

    /** Clear ALL auto-kick cooldowns. Called when Nudge is globally disabled so a lingering cooldown
     *  can't kick the user (a disabled Nudge must behave as if uninstalled). */
    fun clearAllCooldowns() {
        cooldownUntil.clear()
    }
}
