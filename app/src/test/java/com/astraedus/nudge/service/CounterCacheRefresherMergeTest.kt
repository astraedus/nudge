package com.astraedus.nudge.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ── showCounter ──

    @Test
    fun `mergeEntries aggregates showCounter as OR`() {
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(showCounter = false),
                "com.example.alpha" to CounterCacheEntry(showCounter = true),
            )
        )
        assertTrue(merged["com.example.alpha"]!!.showCounter)
    }

    @Test
    fun `mergeEntries keeps showCounter false when no rule asked for it`() {
        // A package tracked only for a time-based auto-kick must not end up with a counter overlay
        // the user never enabled.
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(autoKickAfterMinutes = 30),
                "com.example.alpha" to CounterCacheEntry(showTimeRemaining = true),
            )
        )
        assertFalse(merged["com.example.alpha"]!!.showCounter)
    }

    // ── autoKickAfterMinutes ──

    @Test
    fun `mergeEntries uses strictest minutes threshold`() {
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(autoKickAfterMinutes = 60),
                "com.example.alpha" to CounterCacheEntry(autoKickAfterMinutes = 15),
                "com.example.alpha" to CounterCacheEntry(autoKickAfterMinutes = null),
            )
        )
        assertEquals(15, merged["com.example.alpha"]!!.autoKickAfterMinutes)
    }

    @Test
    fun `mergeEntries leaves minutes threshold null when no rule sets one`() {
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(autoKickAfter = 30),
                "com.example.alpha" to CounterCacheEntry(showCounter = true),
            )
        )
        assertNull(merged["com.example.alpha"]!!.autoKickAfterMinutes)
    }

    @Test
    fun `mergeEntries keeps the two auto-kick triggers independent`() {
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(showCounter = true, autoKickAfter = 40),
                "com.example.alpha" to CounterCacheEntry(autoKickAfterMinutes = 30),
            )
        )
        val entry = merged["com.example.alpha"]!!
        assertEquals(40, entry.autoKickAfter)
        assertEquals(30, entry.autoKickAfterMinutes)
    }

    // ── needsForegroundTimeTick ──

    @Test
    fun `a minutes threshold alone needs the foreground time tick`() {
        assertTrue(CounterCacheEntry(autoKickAfterMinutes = 30).needsForegroundTimeTick)
    }

    @Test
    fun `time remaining with a daily limit needs the foreground time tick`() {
        assertTrue(
            CounterCacheEntry(showTimeRemaining = true, dailyLimitMinutes = 60)
                .needsForegroundTimeTick
        )
    }

    @Test
    fun `time remaining without a daily limit has nothing to count down`() {
        assertFalse(CounterCacheEntry(showTimeRemaining = true).needsForegroundTimeTick)
    }

    @Test
    fun `a counter-only package does not need the foreground time tick`() {
        // Nothing clock-driven here: the counter is fed by accessibility events, so spinning a
        // timer for it would burn battery for no behaviour.
        assertFalse(
            CounterCacheEntry(showCounter = true, autoKickAfter = 30).needsForegroundTimeTick
        )
    }

    // ── needsForegroundTimeTick: a DAILY LIMIT is a clock consumer (v1.18.4) ──
    //
    // THIS PREDICATE IS THE WHOLE BEHAVIOUR. Mid-session enforcement of a daily limit is not a new
    // mechanism -- `TimeRemainingHandler` has launched the daily-limit HARD_BLOCK from the tick path
    // since v1.10.0 -- it is this one boolean. While a plain limit answered false here,
    // `updateForegroundTimeTicker` stopped the clock with `no_clock_config` and nothing re-read the
    // budget until the next window-state event: measured on the bench, 150 seconds of continuous
    // foreground time on a 1-minute budget produced exactly ONE evaluation, at t=0. So a user who
    // never switched away simply outlived their limit ("daily limits should definitely be enforced
    // even mid session", product call 2026-09-29).

    @Test
    fun `a plain daily limit needs the foreground time tick`() {
        // The shape that was broken, and the common one: a budget, no readout, no auto-kick. The
        // mode is irrelevant here -- an app-level NONE rule with a limit is exactly "don't gate
        // this app, but stop me after N minutes".
        assertTrue(CounterCacheEntry(dailyLimitMinutes = 30).needsForegroundTimeTick)
    }

    @Test
    fun `a daily limit needs the tick whether or not it draws the readout`() {
        // The readout and the enforcement are two different questions about one budget, and
        // `TimeRemainingHandler.maybeUpdate` now asks them separately. Neither answer may switch
        // the clock off.
        listOf(true, false).forEach { showTimeRemaining ->
            assertTrue(
                "showTimeRemaining=$showTimeRemaining must not decide whether a budget is watched",
                CounterCacheEntry(showTimeRemaining = showTimeRemaining, dailyLimitMinutes = 45)
                    .needsForegroundTimeTick
            )
        }
    }

    /**
     * THE COUNTERFACTUAL, and it is the battery half of the change (issue #63 landed two days before it).
     *
     * A rule with no limit, no readout and no time kick must still spin no timer -- if this row
     * ever goes green, the fix has stopped being "a budget is watched" and become "everything is
     * polled", on a 3GB Pixel 3.
     */
    @Test
    fun `a rule with no limit, no overlay and no time kick still needs no tick`() {
        assertFalse(CounterCacheEntry().needsForegroundTimeTick)
        assertFalse(CounterCacheEntry(showCounter = true).needsForegroundTimeTick)
        assertFalse(
            CounterCacheEntry(showCounter = true, autoKickAfter = 15).needsForegroundTimeTick
        )
        assertFalse(CounterCacheEntry(showTimeRemaining = true).needsForegroundTimeTick)
    }

    @Test
    fun `merging a counter-only rule with a daily-limit rule leaves the package ticking`() {
        // Two rules on one package, only one of which carries the budget. `mergeEntries` takes the
        // strictest reading, so the clock must survive the merge -- otherwise adding a second,
        // limitless rule to an app would silently switch its budget back to re-entry-only.
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(showCounter = true),
                "com.example.alpha" to CounterCacheEntry(dailyLimitMinutes = 20),
            )
        )
        assertTrue(merged["com.example.alpha"]!!.needsForegroundTimeTick)
    }

    // ── configuresAutoKick: cache membership is NOT auto-kick authority ──

    /**
     * The regression the daily-limit arm above would otherwise have caused.
     *
     * An armed auto-kick cooldown may only enforce while some rule still configures an auto-kick
     * (`CooldownGate`). That authority used to be read as bare counter-cache membership, which was
     * a fair proxy while only auto-kicks and awareness overlays lived in the cache. A daily limit
     * now lives there too, so "has an entry" would have said yes for a rule that cannot kick at
     * all -- and switching auto-kick off while keeping the limit would have left the cooldown
     * ejecting the user, which is the exact defect `CooldownGate` exists to prevent.
     */
    @Test
    fun `a daily limit alone configures no auto-kick`() {
        assertFalse(CounterCacheEntry(dailyLimitMinutes = 30).configuresAutoKick)
        assertFalse(
            CounterCacheEntry(showCounter = true, showTimeRemaining = true, dailyLimitMinutes = 30)
                .configuresAutoKick
        )
    }

    @Test
    fun `either auto-kick trigger configures an auto-kick`() {
        assertTrue(CounterCacheEntry(autoKickAfter = 30).configuresAutoKick)
        assertTrue(CounterCacheEntry(autoKickAfterMinutes = 30).configuresAutoKick)
    }

    @Test
    fun `a web entry always configures an auto-kick, so its cooldown keeps its authority`() {
        // Web entries exist only for a time kick (`webEntriesFor`), so the narrower authority must
        // not have narrowed the web cooldown out of existence.
        val entries = CounterCacheRefresher.webEntriesFor(
            webDomains = "instagram.com",
            webEnforces = true,
            autoKickAfterMinutes = 10,
            autoKickCooldownSeconds = 60
        )
        assertTrue(entries.isNotEmpty())
        entries.forEach { (key, entry) ->
            assertTrue("$key must keep auto-kick authority", entry.configuresAutoKick)
        }
    }
}
