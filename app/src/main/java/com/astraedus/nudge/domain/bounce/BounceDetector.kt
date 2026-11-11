package com.astraedus.nudge.domain.bounce

import com.astraedus.nudge.domain.web.WebSessionKey

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
 *   each one through [onWall]. A blocked WEBSITE is a wall too, and it carries its own identity:
 *   three different blocked sites inside one browser are three walls, not one browser wall (#106).
 * - The first wall **arms** a streak. While armed, every foreground switch to a real app is
 *   reported through [onAppOpened] and recorded as a distinct package. The walled app counts too:
 *   the user opened it, that is what the wall was in front of.
 * - Only an **app** counts, and what is an app is [countability]'s answer, not the window's (#181).
 *   A share sheet, an account picker, the photo picker or a passkey prompt is an activity in its
 *   own package with a focused application window, so the classifier rightly calls it the app in
 *   front; it is still not an app the user opened. In production the answer is "it has a launcher
 *   entry and is not the home screen" ([AppCountability]). A package that is not an app neither
 *   counts nor counts as LEAVING the walled app, exactly like an [excludedPackages] entry; a home
 *   screen that arrives as an app window does not count either, but it is going home. One whose
 *   answer is not known yet is recorded but not counted until it is ([reevaluate]), and whether
 *   it was a way out of the walled app is decided at the next wall, on the answer known by then.
 * - The streak stays armed while walls keep coming: it lapses [Config.windowMs] after the MOST
 *   RECENT wall, so ongoing bouncing keeps being tracked rather than being cut off at a fixed
 *   deadline from the first one. When it lapses with no new wall, every piece of state is dropped.
 * - It **fires** when, inside the streak, the user has hit at least [Config.minWalls] walls AND
 *   opened at least [Config.minApps] distinct apps, OR has been stopped on at least
 *   [Config.minSites] distinct sites. The app rule keeps the numbers it was specified with; the
 *   site rule is the same loop inside one browser, where "distinct apps" can never reach four.
 * - After firing it goes quiet for [Config.cooldownMs], feeding nothing and holding nothing, and the
 *   next wall after that starts a fresh streak. The cooldown is shared by both rules: it is one
 *   check-in, not one per counter.
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
 * A site wall follows the same rule one level down: the SAME site re-blocked while the user has not
 * left the browser is one wall, and a different site is always a new one. The browser is still the
 * package that decides whether the user "left": navigating from one blocked site to another is not
 * leaving Chrome. Identity is the site when there is one and the package otherwise, so an app-level
 * block of the browser straight after a site block in it is a new wall too.
 *
 * ## What it costs
 *
 * Nothing at all until the first wall: [onAppOpened] and [onWentHome] return at the first line
 * while disarmed, and the caller does not feed this at all while the feature is switched off.
 * While armed, each call is a constant-time update of a bounded set. There is no timer, no
 * scheduled work, no wakelock and no I/O; time is whatever the caller passes in, which in
 * production is `SystemClock.elapsedRealtime()` read on the event that is already being handled.
 * State is in memory only, so a process death simply forgets a streak, which is the right
 * direction for a nudge. Blocked domains are held for the length of one streak and never persisted:
 * this is a nudge counter, not browsing history.
 *
 * Pure Kotlin, no Android, and NOT thread-safe: the caller serialises access (see
 * `service/BounceCheckIn`).
 *
 * @param excludedPackages packages that are never "an app the user opened", even if a caller feeds
 *   them. See [neverCountedPackages] for the defaults and why they exist despite the classifier.
 * @param countability whether a package is an app the user opens (#181). Asked on every
 *   [onAppOpened] and, for each recorded package, on every evaluation, so it must be a constant-time
 *   cache read that never blocks (see [AppCountability]). The default counts everything, which is
 *   only for tests about the streak itself; production always passes [AppCountability].
 * @param onNotCounted told, once per package per streak, that a recorded or opened package was
 *   left out and why ([Countability.NOT_AN_APP] or [Countability.HOME]). For the debug trace.
 */
class BounceDetector(
    private val config: Config = Config(),
    private val excludedPackages: Set<String> = emptySet(),
    private val countability: (String) -> Countability = { Countability.COUNTS },
    private val onNotCounted: (String, Countability) -> Unit = { _, _ -> }
) {

    /** The thresholds, all in one place. */
    data class Config(
        val windowMs: Long = WINDOW_MS,
        val minWalls: Int = MIN_WALLS,
        val minApps: Int = MIN_APPS,
        val minSites: Int = MIN_SITES,
        val cooldownMs: Long = COOLDOWN_MS,
        val maxTrackedApps: Int = MAX_TRACKED_APPS,
        val maxTrackedSites: Int = MAX_TRACKED_SITES
    ) {
        init {
            require(windowMs > 0) { "windowMs must be positive" }
            require(minWalls >= 1) { "minWalls must be at least 1" }
            require(minApps >= 1) { "minApps must be at least 1" }
            require(minSites >= 1) { "minSites must be at least 1" }
            require(cooldownMs >= 0) { "cooldownMs must not be negative" }
            require(maxTrackedApps >= minApps) { "maxTrackedApps must reach minApps" }
            require(maxTrackedSites >= minSites) { "maxTrackedSites must reach minSites" }
        }
    }

    /** True while a streak is armed and being tracked. */
    val isArmed: Boolean get() = streakStartedAtMs != null

    private var streakStartedAtMs: Long? = null
    private var lastWallAtMs: Long = 0L
    private var lastWallPackage: String? = null
    private var lastWallKey: String? = null
    /** The user went to the home screen since the last wall. */
    private var wentHomeSinceLastWall: Boolean = false
    /**
     * Packages that came to the front since the last wall and might be apps. Whether the user LEFT
     * the walled app is decided from these when the next wall arrives, with the answers known by
     * then, so an app whose facts were not cached on the way in still counts as leaving (#181).
     */
    private val openedSinceLastWall = LinkedHashSet<String>()
    private var walls: Int = 0
    /**
     * Every package recorded in this streak, apps or not yet known to be. Only the ones
     * [countability] answers [Countability.COUNTS] for are counted, at evaluation time, so a
     * package whose answer lands later counts from then on ([reevaluate]).
     */
    private val apps = LinkedHashSet<String>()
    private val sites = LinkedHashSet<String>()

    /** Packages already reported through [onNotCounted] in this streak. */
    private val reportedNotCounted = HashSet<String>()

    /** Firing is followed by a quiet period; null when not cooling down. */
    private var cooldownUntilMs: Long? = null

    /**
     * Nudge just stopped [packageName] (the app the user was in: for a website block, the browser).
     *
     * @param siteDomain the site the wall was about, for a website block. Null for an app wall.
     *   A bare host as `WebDomainMatcher.extractDomain` / `WebSessionKey.domainOf` produce it; it
     *   is normalised through [WebSessionKey] so `www.instagram.com`, `m.instagram.com` and
     *   `instagram.com` are one site, not three.
     * @return the alert to show, or null.
     */
    fun onWall(packageName: String, nowMs: Long, siteDomain: String? = null): BounceAlert? {
        if (coolingDown(nowMs)) return null
        lapseIfExpired(nowMs)

        val site = siteDomain?.let { WebSessionKey.forDomain(it) }
        // A wall is identified by its SITE when there is one, otherwise by the app. So a site hop
        // inside one browser is a new wall, and so is an app-level block of that same browser
        // straight after a site block in it (the browser is then a different identity, not a
        // re-launch of the site's overlay).
        val key = site ?: packageName
        val started = streakStartedAtMs
        if (started == null) {
            streakStartedAtMs = nowMs
            walls = 1
        } else if (isNewWall(key)) {
            walls++
        }
        // Re-launches of the overlay for an app the user never left still SLIDE the window: the
        // wall is still in their face, and the streak should not lapse from under it.
        lastWallAtMs = nowMs
        lastWallPackage = packageName
        lastWallKey = key
        wentHomeSinceLastWall = false
        openedSinceLastWall.clear()
        record(packageName, site)
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
        when (countabilityOf(packageName)) {
            // Not an app: a sheet over the walled app is not leaving it, and adds nothing (#181).
            Countability.NOT_AN_APP -> return null
            // A home screen that reached us as an app window: not an app, but it IS going home.
            Countability.HOME -> {
                wentHomeSinceLastWall = true
                return null
            }
            // An app, or not known yet: recorded, and remembered as a possible leave. Both are
            // judged on the answer known when it matters (evaluation, the next wall).
            Countability.UNKNOWN, Countability.COUNTS -> Unit
        }
        if (packageName != lastWallPackage && openedSinceLastWall.size < config.maxTrackedApps) {
            openedSinceLastWall.add(packageName)
        }
        record(packageName, site = null)
        return evaluate(nowMs)
    }

    /**
     * Re-check the streak now, because an answer [countability] did not have before has arrived: a
     * package already recorded may count from now on. Changes nothing but the decision, so it obeys
     * the same cooldown and window as every other entry point.
     *
     * @return the alert to show, or null.
     */
    fun reevaluate(nowMs: Long): BounceAlert? {
        if (streakStartedAtMs == null) return null
        if (coolingDown(nowMs)) return null
        if (lapseIfExpired(nowMs)) return null
        return evaluate(nowMs)
    }

    /**
     * The user went to the home screen. Not an app, so it adds nothing to the count, but it IS
     * leaving the walled app, so the next wall for that same app is a new one.
     */
    fun onWentHome(nowMs: Long) {
        if (streakStartedAtMs == null) return
        if (lapseIfExpired(nowMs)) return
        wentHomeSinceLastWall = true
    }

    /** Forget everything, including a cooldown. Used when the feature or Nudge is switched off. */
    fun reset() {
        clearStreak()
        cooldownUntilMs = null
    }

    /**
     * Is this wall a new confrontation? The same identity (site, or app) only counts once the user
     * has been somewhere else in between (see the class docs): home, or an app. A package not known
     * to be an app (or a home screen) by now is not leaving; under-reading a leave can only miss a
     * wall, never invent one.
     */
    private fun isNewWall(key: String): Boolean = key != lastWallKey || wentHomeSinceLastWall ||
        openedSinceLastWall.any {
            val answer = countabilityOf(it)
            answer == Countability.COUNTS || answer == Countability.HOME
        }

    private fun record(packageName: String, site: String?) {
        if (packageName !in excludedPackages && apps.size < config.maxTrackedApps) {
            apps.add(packageName)
        }
        // A site is not an app: it is counted on its own rule, and only while it fits the bound.
        if (site != null && sites.size < config.maxTrackedSites) sites.add(site)
    }

    private fun evaluate(nowMs: Long): BounceAlert? {
        val started = streakStartedAtMs ?: return null
        val minutes = BounceAlert.minutesFor(nowMs - started)
        val counted = apps.filter { countabilityOf(it) == Countability.COUNTS }
        // The app rule first, so a streak that reaches both reads the way it always has.
        val alert = when {
            walls >= config.minWalls && counted.size >= config.minApps -> BounceAlert(
                count = counted.size,
                minutes = minutes,
                unit = BounceAlert.Unit.APPS,
                walls = walls,
                packages = counted
            )
            // A site alert names no package and no domain: domains are never logged.
            sites.size >= config.minSites -> BounceAlert(
                count = sites.size,
                minutes = minutes,
                unit = BounceAlert.Unit.SITES,
                walls = walls
            )
            else -> return null
        }
        clearStreak()
        cooldownUntilMs = nowMs + config.cooldownMs
        return alert
    }

    /** [countability], reporting each left-out package once per streak. */
    private fun countabilityOf(packageName: String): Countability {
        val answer = countability(packageName)
        if ((answer == Countability.NOT_AN_APP || answer == Countability.HOME) &&
            reportedNotCounted.add(packageName)
        ) {
            onNotCounted(packageName, answer)
        }
        return answer
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
        lastWallKey = null
        wentHomeSinceLastWall = false
        openedSinceLastWall.clear()
        walls = 0
        apps.clear()
        sites.clear()
        reportedNotCounted.clear()
    }

    companion object {
        /** How long after the most recent wall a streak stays armed. */
        const val WINDOW_MS: Long = 5 * 60_000L

        /** The first wall plus at least one more: "they came back and hit a wall again". */
        const val MIN_WALLS: Int = 2

        /**
         * Distinct real apps opened inside the streak, the walled ones included. "Real app" is
         * [countability]'s answer: a system sheet is never one (#181).
         */
        const val MIN_APPS: Int = 4

        /**
         * Distinct blocked sites inside the streak. One browser is one app, so the app rule can
         * never fire on a user who stays inside it; three different sites is the same loop.
         */
        const val MIN_SITES: Int = 3

        /** After a check-in, no second one for this long. */
        const val COOLDOWN_MS: Long = 30 * 60_000L

        /** Bounds the set; nobody bounces through more than this in one streak, and N stays honest. */
        const val MAX_TRACKED_APPS: Int = 64

        /** Bounds the site set for the same reason. */
        const val MAX_TRACKED_SITES: Int = 64

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
 * What the check-in says: "You've bounced through [count] apps in [minutes] minutes", or "sites"
 * when the streak fired on the site rule.
 *
 * Both numbers are real: [count] is the distinct apps (or distinct blocked sites) opened in the
 * streak (the walled ones included) and [minutes] is the time from the first wall to the moment it
 * fired, rounded UP so a 40-second bounce reads "1 minute" rather than "0 minutes".
 *
 * [walls] and [packages] are for the debug trace only, never the notification. [packages] is the
 * apps that were counted, for an APPS alert; a SITES alert carries none, and no domain ever.
 */
data class BounceAlert(
    val count: Int,
    val minutes: Int,
    val unit: Unit = Unit.APPS,
    val walls: Int = 0,
    val packages: List<String> = emptyList()
) {

    enum class Unit { APPS, SITES }

    val title: String get() = TITLE

    val body: String
        get() {
            val minutesUnit = if (minutes == 1) "minute" else "minutes"
            val noun = when {
                unit == Unit.SITES -> if (count == 1) "site" else "sites"
                count == 1 -> "app"
                else -> "apps"
            }
            return "You've bounced through $count $noun in $minutes $minutesUnit. Wanna take a break?"
        }

    companion object {
        const val TITLE = "Bro. wtf."

        fun minutesFor(elapsedMs: Long): Int {
            val clamped = elapsedMs.coerceAtLeast(0L)
            return ((clamped + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        }
    }
}
