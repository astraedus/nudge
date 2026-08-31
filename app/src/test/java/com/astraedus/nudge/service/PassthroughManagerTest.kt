package com.astraedus.nudge.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PassthroughManagerTest {

    private lateinit var manager: PassthroughManager

    @Before
    fun setUp() {
        manager = PassthroughManager()
    }

    @Test
    fun `grant sets package and feature`() {
        manager.grant("com.example.app", "REELS")

        assertEquals("com.example.app", manager.lastPackage)
        assertEquals("REELS", manager.lastFeature)
        assertTrue(manager.lastTime > 0L)
    }

    @Test
    fun `grant without feature sets null feature`() {
        manager.grant("com.example.app")

        assertEquals("com.example.app", manager.lastPackage)
        assertNull(manager.lastFeature)
    }

    @Test
    fun `isGranted returns true for matching package`() {
        manager.grant("com.example.alpha")

        assertTrue(manager.isGranted("com.example.alpha"))
    }

    @Test
    fun `isGranted returns false for different package`() {
        manager.grant("com.example.alpha")

        assertFalse(manager.isGranted("com.example.beta"))
    }

    @Test
    fun `isGranted returns false when no grant active`() {
        assertFalse(manager.isGranted("com.example.alpha"))
    }

    @Test
    fun `shouldSkipForegroundEvaluation delegates to isGranted`() {
        manager.grant("com.example.alpha")

        assertTrue(manager.shouldSkipForegroundEvaluation("com.example.alpha"))
        assertFalse(manager.shouldSkipForegroundEvaluation("com.example.beta"))
    }

    @Test
    fun `shouldSkipFeatureEvaluation requires both package and feature match`() {
        manager.grant("com.example.alpha", "REELS")

        assertTrue(manager.shouldSkipFeatureEvaluation("com.example.alpha", "REELS"))
        assertFalse(manager.shouldSkipFeatureEvaluation("com.example.alpha", "EXPLORE"))
        assertFalse(manager.shouldSkipFeatureEvaluation("com.example.beta", "REELS"))
    }

    @Test
    fun `shouldSkipFeatureEvaluation returns false when granted without feature`() {
        manager.grant("com.example.alpha")

        assertFalse(manager.shouldSkipFeatureEvaluation("com.example.alpha", "REELS"))
    }

    @Test
    fun `clearIfAppChanged clears when different package and returns true`() {
        manager.grant("com.example.alpha", "REELS")

        val result = manager.clearIfAppChanged("com.example.beta")

        assertTrue(result)
        assertNull(manager.lastPackage)
        assertNull(manager.lastFeature)
        assertEquals(0L, manager.lastTime)
    }

    @Test
    fun `clearIfAppChanged does not clear when same package and returns false`() {
        manager.grant("com.example.alpha", "REELS")

        val result = manager.clearIfAppChanged("com.example.alpha")

        assertFalse(result)
        assertEquals("com.example.alpha", manager.lastPackage)
        assertEquals("REELS", manager.lastFeature)
    }

    @Test
    fun `clearIfAppChanged does nothing when no passthrough active and returns false`() {
        val result = manager.clearIfAppChanged("com.example.beta")

        assertFalse(result)
        assertNull(manager.lastPackage)
    }

    @Test
    fun `clear resets all state`() {
        manager.grant("com.example.alpha", "SHORTS")

        manager.clear()

        assertNull(manager.lastPackage)
        assertNull(manager.lastFeature)
        assertEquals(0L, manager.lastTime)
        assertFalse(manager.isGranted("com.example.alpha"))
    }

    // --- The web axis ---
    //
    // A completed website block grants TWO things at once: the browser is the app the user is in,
    // and the domain is the site they earned entry to. They are granted and cleared together, which
    // is why they live in one manager rather than in a stray field on the accessibility service.

    @Test
    fun `a web grant records the browser and the domain together`() {
        manager.grant("com.android.chrome", webDomain = "instagram.com")

        assertEquals("com.android.chrome", manager.lastPackage)
        assertEquals("instagram.com", manager.lastDomain)
    }

    /**
     * An app block must never leave a domain behind. Before the two axes shared a manager, the
     * domain lived on the service and nothing about an app-level grant touched it.
     */
    @Test
    fun `an app grant does not inherit a stale domain`() {
        manager.grant("com.android.chrome", webDomain = "instagram.com")

        manager.grant("com.example.alpha")

        assertNull(manager.lastDomain)
    }

    @Test
    fun `clearWebGrant drops the domain and leaves the app grant alone`() {
        manager.grant("com.android.chrome", webDomain = "instagram.com")

        manager.clearWebGrant()

        assertNull(manager.lastDomain)
        assertEquals("com.android.chrome", manager.lastPackage)
        assertTrue(manager.isGranted("com.android.chrome"))
    }

    @Test
    fun `leaving the browser clears the domain with everything else`() {
        manager.grant("com.android.chrome", webDomain = "instagram.com")

        assertTrue(manager.clearIfAppChanged("com.whatsapp"))
        assertNull(manager.lastDomain)
        assertNull(manager.lastPackage)
    }

    @Test
    fun `clear resets the domain too`() {
        manager.grant("com.android.chrome", webDomain = "instagram.com")

        manager.clear()

        assertNull(manager.lastDomain)
    }
}
