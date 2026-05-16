package com.astraedus.nudge.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PassthroughTest {

    @Before
    fun setUp() {
        NudgeAccessibilityService.resetPassthroughForTests()
    }

    @Test
    fun `grantPassthrough marks package as passed through`() {
        NudgeAccessibilityService.grantPassthrough("com.example.alpha")

        assertTrue(NudgeAccessibilityService.isPassthroughGranted("com.example.alpha"))
        assertFalse(NudgeAccessibilityService.isPassthroughGranted("com.example.beta"))
    }

    @Test
    fun `passthrough clears on app switch`() {
        NudgeAccessibilityService.grantPassthrough("com.example.alpha")

        val cleared = NudgeAccessibilityService.clearPassthroughIfAppChanged("com.example.beta")

        assertTrue(cleared)
        assertNull(NudgeAccessibilityService.lastPassthroughPackage)
        assertFalse(NudgeAccessibilityService.isPassthroughGranted("com.example.alpha"))
    }

    @Test
    fun `passthrough prevents re-evaluation`() {
        NudgeAccessibilityService.grantPassthrough("com.example.alpha")

        assertTrue(NudgeAccessibilityService.shouldSkipEvaluationForPassthrough("com.example.alpha"))
        assertFalse(NudgeAccessibilityService.shouldSkipEvaluationForPassthrough("com.example.beta"))
    }
}
