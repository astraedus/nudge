package com.astraedus.nudge.domain.nudge

/**
 * Whether a pause may interrupt right now (#136 review).
 *
 * The pause is only worth showing when the user can actually act on it. A call, a navigation
 * session, a camera recording, our own screens and a locked phone all have something better to do
 * with the screen. The caller builds [Facts] from the platform and defers (retries ~30 s later)
 * rather than skipping, so a deferred pause does not count as shown.
 *
 * Pure, so the decision is unit-tested without Android.
 */
object PhoneNudgePolicy {

    /** What the service knows at the moment the timer fires. */
    data class Facts(
        val inCall: Boolean = false,
        val navigationActive: Boolean = false,
        val carMode: Boolean = false,
        val ownUiInFront: Boolean = false,
        val blockOverlayActive: Boolean = false,
        val deviceLocked: Boolean = false,
        val screenInteractive: Boolean = true,
        val cameraInUse: Boolean = false
    )

    /** False means "defer": retry later, do not count the pause as shown. */
    fun canInterrupt(facts: Facts): Boolean =
        !facts.inCall &&
            !facts.navigationActive &&
            !facts.carMode &&
            !facts.ownUiInFront &&
            !facts.blockOverlayActive &&
            !facts.deviceLocked &&
            facts.screenInteractive &&
            !facts.cameraInUse

    /**
     * Navigation apps whose Android category is missing or wrong. Android's
     * `CATEGORY_MAPS` is the primary signal; this list only fills the gaps for the common
     * ones (the maintainer named Maps, Waze, Android Auto, OsmAnd and Organic Maps).
     */
    val KNOWN_NAVIGATION_PACKAGES: Set<String> = setOf(
        "com.google.android.apps.maps",
        "com.waze",
        "com.google.android.projection.gearhead",
        "net.osmand",
        "net.osmand.plus",
        "app.organicmaps",
        "com.sygic.aura",
        "com.tomtom.gplay.navapp",
        "com.here.app.maps"
    )

    fun isNavigationPackage(packageName: String?, categoryIsNavigation: Boolean): Boolean =
        packageName != null &&
            (categoryIsNavigation || packageName in KNOWN_NAVIGATION_PACKAGES)
}
