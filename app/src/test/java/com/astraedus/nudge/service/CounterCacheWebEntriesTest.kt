package com.astraedus.nudge.service

import com.astraedus.nudge.domain.web.WebSessionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a rule's WEBSITES contribute to the foreground-awareness cache.
 *
 * The defect these pin: the cache was keyed by `rule.packageName` only, so a browser was never in it
 * — which meant `updateForegroundTimeTicker` stopped immediately for every browser event and NOTHING
 * clock-driven could run while the user sat on a blocked website. A rule saying "kick me off after
 * 30 minutes" was inert on the web while being enforced perfectly in the app.
 */
class CounterCacheWebEntriesTest {

    private val instagramKey = WebSessionKey.forDomain("instagram.com")!!

    @Test
    fun `a web rule with a minutes threshold gets an entry per domain`() {
        val entries = CounterCacheRefresher.webEntriesFor(
            webDomains = "instagram.com, www.facebook.com",
            webEnforces = true,
            autoKickAfterMinutes = 30,
            autoKickCooldownSeconds = 900
        )

        assertEquals(
            listOf(instagramKey, WebSessionKey.forDomain("facebook.com")),
            entries.map { it.first }
        )
        assertEquals(30, entries.first().second.autoKickAfterMinutes)
        assertEquals(900, entries.first().second.autoKickCooldownSeconds)
    }

    /**
     * The entry exists to drive a clock, so it must ask for one — otherwise the ticker would start
     * and have nothing to do on every visit to the site.
     */
    @Test
    fun `a web entry needs the foreground time tick`() {
        val entry = CounterCacheRefresher.webEntriesFor(
            webDomains = "instagram.com",
            webEnforces = true,
            autoKickAfterMinutes = 30,
            autoKickCooldownSeconds = 60
        ).single().second

        assertTrue(entry.needsForegroundTimeTick)
    }

    /**
     * Deliberate omissions, documented on `webEntriesFor`. The counter is fed by events carrying the
     * BROWSER's package, and the time-remaining overlay needs a daily web total that does not exist
     * yet (`docs/BACKLOG.md`). Carrying either across would put a floating overlay on screen that
     * measures nothing.
     */
    @Test
    fun `a web entry carries no counter and no time-remaining overlay`() {
        val entry = CounterCacheRefresher.webEntriesFor(
            webDomains = "instagram.com",
            webEnforces = true,
            autoKickAfterMinutes = 30,
            autoKickCooldownSeconds = 60
        ).single().second

        assertFalse(entry.showCounter)
        assertFalse(entry.showTimeRemaining)
        assertNull(entry.autoKickAfter)
        assertNull(entry.dailyLimitMinutes)
    }

    /**
     * Issue #21's axis: a rule whose resolved WEB mode is NONE blocks nothing on the web. Ejecting a
     * user from a site the app is not blocking would be enforcement they never asked for.
     */
    @Test
    fun `a rule that does not enforce on the web contributes nothing`() {
        assertEquals(
            emptyList<Pair<String, CounterCacheEntry>>(),
            CounterCacheRefresher.webEntriesFor(
                webDomains = "instagram.com",
                webEnforces = false,
                autoKickAfterMinutes = 30,
                autoKickCooldownSeconds = 60
            )
        )
    }

    @Test
    fun `no minutes threshold means no clock and therefore no entry`() {
        assertEquals(
            emptyList<Pair<String, CounterCacheEntry>>(),
            CounterCacheRefresher.webEntriesFor(
                webDomains = "instagram.com",
                webEnforces = true,
                autoKickAfterMinutes = null,
                autoKickCooldownSeconds = 60
            )
        )
    }

    @Test
    fun `no domains means no entry`() {
        assertTrue(
            CounterCacheRefresher.webEntriesFor(null, true, 30, 60).isEmpty()
        )
        assertTrue(
            CounterCacheRefresher.webEntriesFor("  ", true, 30, 60).isEmpty()
        )
    }

    /**
     * The same site written two ways in one rule is one session, so it must be one entry — a
     * duplicate would be harmless here but would mean the domain normalisation had been skipped
     * somewhere, and the session key is what the cooldown is armed on.
     */
    @Test
    fun `two spellings of one site collapse to one entry`() {
        val entries = CounterCacheRefresher.webEntriesFor(
            webDomains = "instagram.com, www.instagram.com, m.instagram.com",
            webEnforces = true,
            autoKickAfterMinutes = 30,
            autoKickCooldownSeconds = 60
        )

        assertEquals(listOf(instagramKey), entries.map { it.first })
    }

    /**
     * A website's key and its app's package are separate cache entries with separate sessions. If
     * they collided, time on instagram.com would spend the Instagram app's budget (and a web kick
     * would arm a cooldown on the app).
     */
    @Test
    fun `a site and its app are separate entries after the merge`() {
        val appEntry = "com.instagram.android" to CounterCacheEntry(
            showCounter = true,
            autoKickAfterMinutes = 45,
            autoKickCooldownSeconds = 60
        )
        val webEntries = CounterCacheRefresher.webEntriesFor(
            webDomains = "instagram.com",
            webEnforces = true,
            autoKickAfterMinutes = 30,
            autoKickCooldownSeconds = 900
        )

        val merged = CounterCacheRefresher.mergeEntries(listOf(appEntry) + webEntries)

        assertEquals(2, merged.size)
        assertEquals(45, merged["com.instagram.android"]?.autoKickAfterMinutes)
        assertEquals(30, merged[instagramKey]?.autoKickAfterMinutes)
        assertTrue(merged["com.instagram.android"]?.showCounter == true)
        assertFalse(merged[instagramKey]?.showCounter == true)
    }

    /**
     * Two rules covering the same site merge to the strictest reading, exactly as two rules covering
     * one package do.
     */
    @Test
    fun `two rules on one site merge to the strictest thresholds`() {
        val merged = CounterCacheRefresher.mergeEntries(
            CounterCacheRefresher.webEntriesFor("instagram.com", true, 45, 60) +
                CounterCacheRefresher.webEntriesFor("www.instagram.com", true, 20, 900)
        )

        val entry = merged[instagramKey]
        assertNotNull(entry)
        assertEquals(20, entry?.autoKickAfterMinutes)
        assertEquals(900, entry?.autoKickCooldownSeconds)
    }
}
