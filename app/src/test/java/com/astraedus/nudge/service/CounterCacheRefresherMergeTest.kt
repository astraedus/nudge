package com.astraedus.nudge.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CounterCacheRefresherMergeTest {

    @Test
    fun `mergeEntries aggregates showTimeRemaining as OR`() {
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(showTimeRemaining = false),
                "com.example.alpha" to CounterCacheEntry(showTimeRemaining = true),
            )
        )
        assertTrue(merged["com.example.alpha"]!!.showTimeRemaining)
    }

    @Test
    fun `mergeEntries uses strictest daily limit`() {
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(dailyLimitMinutes = 60),
                "com.example.alpha" to CounterCacheEntry(dailyLimitMinutes = 30),
                "com.example.alpha" to CounterCacheEntry(dailyLimitMinutes = null),
            )
        )
        assertEquals(30, merged["com.example.alpha"]!!.dailyLimitMinutes)
    }

    @Test
    fun `mergeEntries uses longest cooldown`() {
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(autoKickCooldownSeconds = 30),
                "com.example.alpha" to CounterCacheEntry(autoKickCooldownSeconds = 120),
                "com.example.alpha" to CounterCacheEntry(autoKickCooldownSeconds = 60),
            )
        )
        assertEquals(120, merged["com.example.alpha"]!!.autoKickCooldownSeconds)
    }

    @Test
    fun `mergeEntries handles single entry`() {
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(
                    autoKickAfter = 20,
                    showTimeRemaining = true,
                    dailyLimitMinutes = 45,
                    autoKickCooldownSeconds = 90
                ),
            )
        )
        val entry = merged["com.example.alpha"]!!
        assertEquals(20, entry.autoKickAfter)
        assertTrue(entry.showTimeRemaining)
        assertEquals(45, entry.dailyLimitMinutes)
        assertEquals(90, entry.autoKickCooldownSeconds)
    }

    @Test
    fun `mergeEntries with all false showTimeRemaining stays false`() {
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(showTimeRemaining = false),
                "com.example.alpha" to CounterCacheEntry(showTimeRemaining = false),
            )
        )
        assertFalse(merged["com.example.alpha"]!!.showTimeRemaining)
    }
}
