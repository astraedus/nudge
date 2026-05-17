package com.astraedus.nudge.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDomainDetectorTest {

    private val detector = WebDomainDetector()

    @Test
    fun `isBrowser returns true for Chrome`() {
        assertTrue(detector.isBrowser("com.android.chrome"))
    }

    @Test
    fun `isBrowser returns false for Instagram`() {
        assertFalse(detector.isBrowser("com.instagram.android"))
    }

    @Test
    fun `isBrowser returns false for YouTube`() {
        assertFalse(detector.isBrowser("com.google.android.youtube"))
    }

    @Test
    fun `isBrowser returns false for system UI`() {
        assertFalse(detector.isBrowser("com.android.systemui"))
    }

    @Test
    fun `isBrowser returns false for empty string`() {
        assertFalse(detector.isBrowser(""))
    }

    @Test
    fun `detectUrl returns null for null root node`() {
        val result = detector.detectUrl(null)
        assertTrue(result == null)
    }
}
