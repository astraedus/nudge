package com.astraedus.nudge.service

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CounterCacheRefresherTest {

    @Test
    fun `refreshIfNeeded only queries after 10 second interval`() = runTest {
        val refresher = CounterCacheRefresher()
        var queryCount = 0

        val firstRefresh = refresher.refreshIfNeeded(now = 10_000L) {
            queryCount += 1
            setOf("com.example.alpha")
        }
        val skippedRefresh = refresher.refreshIfNeeded(now = 19_999L) {
            queryCount += 1
            setOf("com.example.beta")
        }
        val secondRefresh = refresher.refreshIfNeeded(now = 20_000L) {
            queryCount += 1
            setOf("com.example.beta")
        }

        assertTrue(firstRefresh)
        assertFalse(skippedRefresh)
        assertTrue(secondRefresh)
        assertEquals(2, queryCount)
        assertFalse(refresher.isEnabled("com.example.alpha"))
        assertTrue(refresher.isEnabled("com.example.beta"))
    }
}
