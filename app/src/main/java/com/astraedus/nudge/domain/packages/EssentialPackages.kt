package com.astraedus.nudge.domain.packages

/**
 * The one essentials list (spec 5.4), as the engine reads it: the apps no group or All apps rule
 * may ever cover. Production binds `service.EssentialApps`, which resolves it on the device; the
 * "Always allowed" sheet renders the same set.
 */
interface EssentialPackages {

    /**
     * Every essential package. Suspends; reads the device off the main thread. Never smaller than
     * the static floor (Nudge itself, Settings, the system surfaces), whatever a source does.
     */
    suspend fun current(): Set<String>

    /**
     * The last resolved set without resolving anything, for a caller that cannot suspend (the
     * accessibility service's synchronous browser check). Null before the first resolution.
     */
    fun cachedOrNull(): Set<String>?

    /**
     * The one browser list: the browsers Nudge reads website addresses from, plus every app on this
     * phone that opens an ordinary web link. Never essential (spec 5.4), and never a member of a
     * group by category, only by hand ([com.astraedus.nudge.domain.groups.GroupMembershipResolver]).
     * Suspends, like [current]; resolved and cached with it.
     */
    suspend fun browsers(): Set<String>
}
