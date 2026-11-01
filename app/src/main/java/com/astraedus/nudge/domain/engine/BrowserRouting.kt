package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.BlockDecision

/**
 * How the accessibility service evaluates a BROWSER in the foreground (spec 5.5).
 *
 * The service reaches this only for a browser that is not nuked (a nuked browser is blocked as an
 * app before any of this; `NukeWiringContractTest` pins that line). Both browser evaluation sites,
 * the foreground switch and the content-change re-check, route through here, so the two cannot
 * disagree about what a browser in a group gets.
 */
object BrowserRouting {

    enum class Route {
        /** Covered by a whole-app group rule: app evaluation first, websites only if it allows. */
        APP_FIRST,

        /** Website rules only, as every browser was before spec 5.5. */
        WEB
    }

    /**
     * [coveredWhole] is "a whole-app group rule covers this browser now and the user has not earned
     * their way past it" (the service's `isCoveredWholeNow`, minus a live whole-app grant).
     */
    fun route(coveredWhole: Boolean): Route = if (coveredWhole) Route.APP_FIRST else Route.WEB

    /**
     * After an [Route.APP_FIRST] app evaluation: does website evaluation still run? Only when the
     * app evaluation allowed. Anything else blocks the whole browser, and evaluating the open site
     * as well would launch a second overlay for the same arrival.
     */
    fun continuesToWeb(appDecision: BlockDecision): Boolean = appDecision is BlockDecision.Allow
}
