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
}
