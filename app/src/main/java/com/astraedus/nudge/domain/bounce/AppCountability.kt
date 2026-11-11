package com.astraedus.nudge.domain.bounce

import com.astraedus.nudge.domain.packages.PackageFacts
import java.util.concurrent.ConcurrentHashMap

/** Whether a package that came to the front is an app the user opened, for the bounce count. */
enum class Countability {
    /** An app with a launcher entry that is not a home screen: it counts. */
    COUNTS,

    /** Installed but with no launcher entry: a share sheet, picker or prompt host. Never counts. */
    NOT_AN_APP,

    /** A home screen (any launcher, default or not). Never counts. */
    HOME,

    /** Not known yet. Not counted until it is; a lookup has been asked for. */
    UNKNOWN
}

/**
 * The production answer to "is this an app the user opened?" for [BounceDetector] (#181).
 *
 * An app is a package with a launcher entry (`PackageFact.launchable`) that is not a home screen.
 * A system sheet (the share sheet, the photo picker, an account or passkey prompt) is an activity
 * in a package of its own with no launcher entry, which is exactly what tells it apart; no package
 * or class list is involved (`tasks/lessons.md`, 2026-09-11).
 *
 * A home screen is told apart two ways, so neither has to be right on its own: the service's
 * resolved launcher set ([homePackages], which is empty until it first resolves and can be minutes
 * stale), and the package's own `homeScreen` fact (it handles `CATEGORY_HOME`), which is read per
 * package like launchability.
 *
 * Every answer is a cache read and never blocks: this runs on the accessibility event thread, under
 * the check-in's lock. On a miss it answers [Countability.UNKNOWN] and asks [requestLookup] to read
 * the facts off that thread, once per package until [lookupFinished] says the read is done. A
 * package whose read failed stays UNKNOWN (never counted) rather than being read again on every
 * event: under-counting is the safe direction for a nudge.
 */
class AppCountability(
    private val facts: PackageFacts,
    private val homePackages: () -> Set<String>,
    private val requestLookup: (String) -> Unit
) : (String) -> Countability {

    private val requested: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun invoke(packageName: String): Countability {
        if (packageName in homePackages()) return Countability.HOME
        val fact = facts.cachedOrNull(packageName)
        if (fact == null) {
            if (requested.add(packageName)) requestLookup(packageName)
            return Countability.UNKNOWN
        }
        return when {
            fact.homeScreen -> Countability.HOME
            fact.launchable -> Countability.COUNTS
            else -> Countability.NOT_AN_APP
        }
    }

    /**
     * The lookup [requestLookup] was asked for has finished. Returns true if it left an answer, so
     * the caller re-checks the streak; false if it failed, in which case the package stays UNKNOWN
     * and is not asked for again (no retry loop).
     *
     * A package whose facts are later invalidated (installed, updated, removed) is asked for again
     * on its next sighting, because a successful lookup forgets that it was asked.
     */
    fun lookupFinished(packageName: String): Boolean {
        if (facts.cachedOrNull(packageName) == null) return false
        requested.remove(packageName)
        return true
    }
}
