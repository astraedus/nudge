package com.astraedus.nudge.service

import com.astraedus.nudge.domain.events.ForegroundSignal
import com.astraedus.nudge.domain.surfaces.TabCoverEffect
import com.astraedus.nudge.domain.surfaces.TabCoverPlacement

/**
 * Thin interface extracted for testability, matching [CounterOverlayManagerApi]'s shape.
 *
 * The tab cover is a solid, TOUCHABLE `TYPE_ACCESSIBILITY_OVERLAY` window painted over one host-app
 * nav-bar tab, so the icon disappears and the tap is eaten. It only ever exists behind a decision
 * that was already a hard block; DELAY / HOLD / BREATHING must leave the tab tappable, because the
 * tab is how the user reaches the interstitial.
 */
interface TabCoverOverlayManagerApi {

    /** True while a cover window is attached. */
    fun isVisible(): Boolean

    /** The package the current cover was drawn over, or null when no cover is up. */
    fun coveredPackage(): String?

    /**
     * Where the cover currently is, or null when none is up.
     *
     * Exists so [com.astraedus.nudge.domain.surfaces.TabCoverDecider] can tell "already exactly
     * there, leave it alone" from "the tab moved, reposition" without the manager holding any
     * policy, and without the caller keeping a second copy of "is a cover up" that could disagree
     * with this one. Non-null exactly when [isVisible] is true.
     */
    fun shownPlacement(): TabCoverPlacement?

    /**
     * Apply an already-DECIDED effect for [packageName]. Safe to call on every content change.
     *
     * This method decides nothing: [TabCoverEffect] comes from the pure layer, which has already
     * weighed the block decision, the surface classification and the node bounds. All that happens
     * here is window work.
     *
     * @param color ARGB fill, from the adapter's `navBarColor(nightMode)`. Passed in rather than
     *   chosen here so the one place host-app colours live stays the adapter.
     * @param label what the covered tab IS, e.g. "Reels" — used to build the cover's
     *   `contentDescription`. Passed in for the same reason as [color]: the feature word belongs to
     *   the caller and the adapter, never to this class.
     */
    fun apply(packageName: String, effect: TabCoverEffect, color: Int, label: String)

    /** Remove the cover window if one is attached. Idempotent. */
    fun hide()

    /**
     * Tear down when the classification says the user is no longer in the covered app.
     *
     * The whole decision belongs to `TabCoverPresence.shouldKeep`; this exists so the service has
     * one call to make from `applyForegroundSignal` — the single place that sees every
     * classification above every early return — instead of six teardown sites that disagree about
     * what "the user left" means.
     */
    fun onForegroundSignal(signal: ForegroundSignal)
}
