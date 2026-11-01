package com.astraedus.nudge.service

import com.astraedus.nudge.domain.events.ForegroundSignal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1: the whole-app axis of the passthrough grant (spec 5.5). A browser a group covers may be let
 * through only by a grant earned against the WHOLE browser; a finished website delay is not one.
 */
class PassthroughWholeAxisTest {

    private val chrome = "com.android.chrome"
    private val manager = PassthroughManager()

    @Test
    fun `an app-level completion is a whole grant`() {
        manager.grant(chrome)
        assertTrue(manager.isGrantedWhole(chrome))
    }

    @Test
    fun `a website completion alone is not`() {
        manager.grant(chrome, webDomain = "youtube.com")
        assertTrue("the plain grant is still there", manager.isGranted(chrome))
        assertFalse(manager.isGrantedWhole(chrome))
    }

    @Test
    fun `a feature completion alone is not`() {
        manager.grant("com.instagram.android", featureKey = "REELS")
        assertFalse(manager.isGrantedWhole("com.instagram.android"))
    }

    @Test
    fun `a website completion inside an earned browser keeps it earned`() {
        manager.grant(chrome)
        manager.grant(chrome, webDomain = "youtube.com")
        assertTrue(manager.isGrantedWhole(chrome))
    }

    @Test
    fun `a website completion does not inherit another package's whole grant`() {
        manager.grant("org.mozilla.firefox")
        manager.grant(chrome, webDomain = "youtube.com")
        assertFalse(manager.isGrantedWhole(chrome))
        assertFalse(manager.isGrantedWhole("org.mozilla.firefox"))
    }

    @Test
    fun `whole is per package`() {
        manager.grant(chrome)
        assertFalse(manager.isGrantedWhole("org.mozilla.firefox"))
    }

    @Test
    fun `clear drops it, and a later website grant does not resurrect it`() {
        manager.grant(chrome)
        manager.clear()
        assertFalse(manager.isGrantedWhole(chrome))
        manager.grant(chrome, webDomain = "youtube.com")
        assertFalse(manager.isGrantedWhole(chrome))
    }

    @Test
    fun `it ends with the sitting, like the grant it belongs to`() {
        manager.grant(chrome)
        manager.onForegroundSignal(ForegroundSignal.Home("com.google.android.apps.nexuslauncher"), nowMs = 1_000L)
        assertFalse(manager.isGrantedWhole(chrome))
    }

    @Test
    fun `whole implies granted`() {
        val grants = listOf<PassthroughManager.() -> Unit>(
            { grant(chrome) },
            { grant(chrome, webDomain = "a.com") },
            { grant(chrome, featureKey = "REELS") },
            { grant("other") },
            { clear() }
        )
        // Every ordered pair of grant steps: the axis never claims a grant the package lacks.
        for (first in grants) for (second in grants) {
            val m = PassthroughManager()
            m.first()
            m.second()
            if (m.isGrantedWhole(chrome)) assertTrue(m.isGranted(chrome))
        }
    }
}
