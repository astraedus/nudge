package com.astraedus.nudge.service

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The post-block passthrough: what the user has earned the right to enter without being re-blocked.
 *
 * Two axes, ONE lifetime. [lastPackage] is the app whose delay/breathing exercise was completed;
 * [lastDomain] is the website's equivalent. They are granted together (a web block is completed in a
 * browser, so both are true at once) and cleared together, which is the point of them living here.
 *
 * The web axis used to be a `lastBlockedDomain` field on the accessibility service, set at BLOCK
 * time rather than at completion time -- so walking away from a website's delay, or tabbing out of
 * it, left the pass granted anyway. Every other grant in this app is earned by finishing the
 * exercise ([BlockOverlayActivity.onTimerComplete][com.astraedus.nudge.ui.overlay.BlockOverlayActivity]);
 * this one now is too.
 *
 * There is deliberately **no time-based expiry**: a naive `now - lastTime > N` would re-block a user
 * mid-use, still inside the app, which is issue #5. A grant lives until the foreground app changes,
 * the user goes home, or the process dies.
 */
@Singleton
class PassthroughManager @Inject constructor() {
    @Volatile var lastPackage: String? = null
        private set
    @Volatile var lastFeature: String? = null
        private set
    @Volatile var lastTime: Long = 0L
        private set

    /**
     * The web domain whose block was completed, normalised (see
     * [com.astraedus.nudge.domain.web.WebSessionKey]). Null when the completed block was not a web
     * one -- a HARD_BLOCK never reaches a completion path at all, so it can never grant.
     */
    @Volatile var lastDomain: String? = null
        private set

    fun grant(packageName: String, featureKey: String? = null, webDomain: String? = null) {
        lastPackage = packageName
        lastFeature = featureKey
        lastDomain = webDomain
        lastTime = System.currentTimeMillis()
    }

    fun isGranted(packageName: String): Boolean = packageName == lastPackage

    fun shouldSkipForegroundEvaluation(packageName: String): Boolean = isGranted(packageName)

    fun shouldSkipFeatureEvaluation(packageName: String, featureKey: String): Boolean =
        isGranted(packageName) && lastFeature == featureKey

    /**
     * Drop only the web axis, leaving an app-level grant alone.
     *
     * Used when the user navigates to a different domain or leaves the browser: they have stopped
     * being on the site they earned entry to, but nothing about the app they are in has changed.
     */
    fun clearWebGrant() {
        lastDomain = null
    }

    fun clearIfAppChanged(packageName: String): Boolean {
        if (lastPackage == null || packageName == lastPackage) return false
        clear()
        return true
    }

    fun clear() {
        lastPackage = null
        lastFeature = null
        lastDomain = null
        lastTime = 0L
    }

    fun resetForTests() = clear()
}
