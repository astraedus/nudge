package com.astraedus.nudge.domain.surfaces

/**
 * The lookup from a foreground package name to the adapter that describes it.
 *
 * Deliberately a flat list rather than a map literal: the list IS the registration point, the map is
 * derived from it, and [supportedPackages] is derived from the map — so a new adapter is one entry
 * and cannot be half-registered. `PlatformSurfacesRegistryTest` asserts its invariants over the
 * discovered set rather than over a hand-written list, for the same reason.
 *
 * **YouTube Shorts tab vanish + Subscriptions steer: same adapter, needs YT selectors.** The seam is
 * shaped for it and nothing about it is implemented here — a YouTube adapter needs its own device
 * spike (the Shorts tab's id and bounds, the Subscriptions entry point, the screen classifier) and
 * that spike has not been run.
 */
object PlatformSurfacesRegistry {

    // The registration point. Add a new PlatformSurfaces implementation here and nowhere else.
    private val all: List<PlatformSurfaces> = listOf(InstagramSurfaces)

    private val byPackage: Map<String, PlatformSurfaces> = all.associateBy { it.packageName }

    /** The adapter for [packageName], or null when Nudge knows nothing about that app's surfaces. */
    fun forPackage(packageName: String): PlatformSurfaces? = byPackage[packageName]

    /** Every app with an adapter. Derived from [all], never hand-listed. */
    val supportedPackages: Set<String> = byPackage.keys

    /** Every registered adapter, for tests that assert a property across all of them. */
    val adapters: List<PlatformSurfaces> = all
}
