package com.astraedus.nudge.domain.bounce

/**
 * The "Bro. wtf." bounce detector: notices a user hitting a Nudge wall, hopping through a handful of
 * other apps, and hitting a wall again, and says so ONCE.
 *
 * The owner's words for the pattern: *"when I hit the walls I just open up other apps like Discord,
 * then go back to Instagram (which I'm blocked from for a few more minutes), and I just bounce
 * around a lot. I want to catch myself midway through that bouncing."*
 *
 * ## The model, in one place
 *
 * - A **wall** is any time Nudge stops an app: a block overlay (every block mode, Nuke included,
 *   the auto-kick cooldown overlay, the daily-limit block) or an auto-kick. The service reports
 *   each one through [onWall].
 * - The first wall **arms** a streak. While armed, every foreground switch to a real app is
 *   reported through [onAppOpened] and recorded as a distinct package. The walled app counts too:
 *   the user opened it, that is what the wall was in front of.
 * - The streak stays armed while walls keep coming: it lapses [Config.windowMs] after the MOST
 *   RECENT wall, so ongoing bouncing keeps being tracked rather than being cut off at a fixed
 *   deadline from the first one. When it lapses with no new wall, every piece of state is dropped.
 * - It **fires** when, inside the streak, the user has hit at least [Config.minWalls] walls AND
 *   opened at least [Config.minApps] distinct apps.
 * - After firing it goes quiet for [Config.cooldownMs], feeding nothing and holding nothing, and the
 *   next wall after that starts a fresh streak.
 *
 * ## What counts as a SECOND wall
 *
 * "They came back and hit a wall again" means a new confrontation, not the same overlay being put
 * up twice. Several legitimate mechanisms re-launch an overlay for an app the user never left
 * (`docs/architecture/block-overlay-lifecycle.md`, "ONE CONFRONTATION PER ARRIVAL", issue #36), and
 * counting each of those as a wall would fire this on a user sitting still. So a wall for the SAME
 * package as the previous one only counts once the user has demonstrably been somewhere else in
 * between: another app ([onAppOpened]) or the home screen ([onWentHome]). A wall for a different
 * package is always a new one.
 *
 * ## What it costs
 *
 * Nothing at all until the first wall: [onAppOpened] and [onWentHome] return at the first line
 * while disarmed, and the caller does not feed this at all while the feature is switched off.
 * While armed, each call is a constant-time update of a bounded set. There is no timer, no
 * scheduled work, no wakelock and no I/O; time is whatever the caller passes in, which in
 * production is `SystemClock.elapsedRealtime()` read on the event that is already being handled.
 * State is in memory only, so a process death simply forgets a streak, which is the right
 * direction for a nudge.
 *
 * Pure Kotlin, no Android, and NOT thread-safe: the caller serialises access (see
 * `service/BounceCheckIn`).
 *
 * @param excludedPackages packages that are never "an app the user opened", even if a caller feeds
 *   them. See [neverCountedPackages] for the defaults and why they exist despite the classifier.
 */
class BounceDetector(
    private val config: Config = Config(),
    private val excludedPackages: Set<String> = emptySet()
) {

    /** The thresholds, all in one place. */
    data class Config(
        val windowMs: Long = WINDOW_MS,
        val minWalls: Int = MIN_WALLS,
        val minApps: Int = MIN_APPS,
        val cooldownMs: Long = COOLDOWN_MS,
        val maxTrackedApps: Int = MAX_TRACKED_APPS
    ) {
        init {
            require(windowMs > 0) { "windowMs must be positive" }
            require(minWalls >= 1) { "minWalls must be at least 1" }
            require(minApps >= 1) { "minApps must be at least 1" }
            require(cooldownMs >= 0) { "cooldownMs must not be negative" }
            require(maxTrackedApps >= minApps) { "maxTrackedApps must reach minApps" }
        }
    }

    /** True while a streak is armed and being tracked. */
    val isArmed: Boolean get() = streakStartedAtMs != null

    private var streakStartedAtMs: Long? = null
    private var lastWallAtMs: Long = 0L
    private var lastWallPackage: String? = null
    private var leftSinceLastWall: Boolean = false
    private var walls: Int = 0
    private val apps = LinkedHashSet<String>()

    /** Firing is followed by a quiet period; null when not cooling down. */
    private var cooldownUntilMs: Long? = null

    /**
     * Nudge just stopped [packageName] (the app the user was in: for a website block, the browser).
     *
     * @return the alert to show, or null.
     */
    fun onWall(packageName: String, nowMs: Long): BounceAlert? {
        if (coolingDown(nowMs)) return null
        lapseIfExpired(nowMs)

        val started = streakStartedAtMs
        if (started == null) {
            streakStartedAtMs = nowMs
            walls = 1
        } else if (packageName != lastWallPackage || leftSinceLastWall) {
            walls++
        }
        // Re-launches of the overlay for an app the user never left still SLIDE the window: the
        // wall is still in their face, and the streak should not lapse from under it.
        lastWallAtMs = nowMs
        lastWallPackage = packageName
        leftSinceLastWall = false
        record(packageName)
        return evaluate(nowMs)
    }

    /**
     * A real app came to the front. The caller has already decided it is a real application window
     * (not the launcher, a keyboard, the shade, a dialog host or Nudge itself).
     *
     * @return the alert to show, or null.
     */
    fun onAppOpened(packageName: String, nowMs: Long): BounceAlert? {
        if (streakStartedAtMs == null) return null
        if (coolingDown(nowMs)) return null
        if (lapseIfExpired(nowMs)) return null
        if (packageName in excludedPackages) return null
        if (packageName != lastWallPackage) leftSinceLastWall = true
        record(packageName)
        return evaluate(nowMs)
    }

    /**
     * The user went to the home screen. Not an app, so it adds nothing to the count, but it IS
     * leaving the walled app, so the next wall for that same app is a new one.
     */
    fun onWentHome(nowMs: Long) {
        if (streakStartedAtMs == null) return
        if (lapseIfExpired(nowMs)) return
        leftSinceLastWall = true
    }

    /** Forget everything, including a cooldown. Used when the feature or Nudge is switched off. */
    fun reset() {
        clearStreak()
        cooldownUntilMs = null
    }

    private fun record(packageName: String) {
        if (packageName in excludedPackages) return
        if (apps.size >= config.maxTrackedApps) return
        apps.add(packageName)
    }

    private fun evaluate(nowMs: Long): BounceAlert? {
        val started = streakStartedAtMs ?: return null
        if (walls < config.minWalls || apps.size < config.minApps) return null
        val alert = BounceAlert(
            appCount = apps.size,
            minutes = BounceAlert.minutesFor(nowMs - started)
        )
        clearStreak()
        cooldownUntilMs = nowMs + config.cooldownMs
        return alert
    }

    /** True while the post-fire quiet period runs; clears it once it has passed. */
    private fun coolingDown(nowMs: Long): Boolean {
        val until = cooldownUntilMs ?: return false
        if (nowMs < until) return true
        cooldownUntilMs = null
        return false
    }

    /** Drops an armed streak whose window has passed. Returns true if it did. */
    private fun lapseIfExpired(nowMs: Long): Boolean {
        if (streakStartedAtMs == null) return false
        // A wall exactly windowMs ago is still inside the window. A clock that went BACKWARDS (it
        // cannot with elapsedRealtime, but a future caller could pass wall time) gives a negative
        // age, which is read as expired: a streak that could never lapse is the worse failure.
        val age = nowMs - lastWallAtMs
        if (age in 0..config.windowMs) return false
        clearStreak()
        return true
    }

    private fun clearStreak() {
        streakStartedAtMs = null
        lastWallAtMs = 0L
        lastWallPackage = null
        leftSinceLastWall = false
        walls = 0
        apps.clear()
    }

    companion object {
        /** How long after the most recent wall a streak stays armed. */
        const val WINDOW_MS: Long = 5 * 60_000L

        /** The first wall plus at least one more: "they came back and hit a wall again". */
        const val MIN_WALLS: Int = 2

        /** Distinct real apps opened inside the streak, the walled ones included. */
        const val MIN_APPS: Int = 4

        /** After a check-in, no second one for this long. */
        const val COOLDOWN_MS: Long = 30 * 60_000L

        /** Bounds the set; nobody bounces through more than this in one streak, and N stays honest. */
        const val MAX_TRACKED_APPS: Int = 64

        /**
         * Packages that are never an app the user OPENED, whatever window they put up.
         *
         * The service only feeds [onAppOpened] from the classifier's `AppWindow` signal, which
         * already excludes the launcher, keyboards, the notification shade, the `android` framework
         * popups and every Nudge window. This list is the narrow remainder that the classifier
         * deliberately does not know about: the Pixel's permission dialog host
         * (`com.google.android.permissioncontroller`) is an ordinary app package to the classifier
         * (see `ForegroundSignal`), and so is System UI on the paths that do not classify it as a
         * system surface.
         *
         * A package LIST is the wrong tool for "has the user left the app" (`tasks/lessons.md`,
         * 2026-09-11), and it is not answering that question here. This one only decides what an
         * over-count costs, and the cost of a miss is that one dialog counts as an app toward a
         * soft notification. That is the failure direction that makes a short list acceptable.
         */
        fun neverCountedPackages(ownPackage: String): Set<String> = setOf(
            ownPackage,
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"
        )
    }
}

/**
 * What the check-in says: "You've bounced through [appCount] apps in [minutes] minutes."
 *
 * Both numbers are real: [appCount] is the distinct apps opened in the streak (the walled ones
 * included) and [minutes] is the time from the first wall to the moment it fired, rounded UP so a
 * 40-second bounce reads "1 minute" rather than "0 minutes".
 */
data class BounceAlert(val appCount: Int, val minutes: Int) {

    val title: String get() = TITLE

    val body: String
        get() {
            val unit = if (minutes == 1) "minute" else "minutes"
            return "You've bounced through $appCount apps in $minutes $unit. Wanna take a break?"
        }

    companion object {
        const val TITLE = "Bro. wtf."

        fun minutesFor(elapsedMs: Long): Int {
            val clamped = elapsedMs.coerceAtLeast(0L)
            return ((clamped + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        }
    }
}
