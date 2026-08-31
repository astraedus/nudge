package com.astraedus.nudge.service

import com.astraedus.nudge.domain.autokick.TimeKickEvaluator
import com.astraedus.nudge.domain.web.WebSessionKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A website has no `UsageStatsManager` stream of its own, so its clock is the BROWSER's. These pin
 * that redirection, and the one property it buys: the time-based auto-kick machinery
 * ([TimeKickEvaluator], [AutoKickTimeHandler], [AutoKickExecutor]) needs no web-specific branch at
 * all -- only a provider that knows where a `web:` key's minutes come from.
 */
class WebSessionUsageProviderTest {

    private class FakeUsage(private val byPackage: Map<String, Long>) : UsageProvider {
        var reads = mutableListOf<String>()
        override fun getDailyForegroundTimeMs(packageName: String): Long {
            reads += packageName
            return byPackage[packageName] ?: 0L
        }
    }

    private val chromeMs = 42L * 60_000L

    @Test
    fun `a web key reads the browser's foreground time`() {
        val delegate = FakeUsage(mapOf("com.android.chrome" to chromeMs))
        val provider = WebSessionUsageProvider(delegate)
        provider.browserPackage = "com.android.chrome"

        val key = WebSessionKey.forDomain("instagram.com")!!

        assertEquals(chromeMs, provider.getDailyForegroundTimeMs(key))
        assertEquals(listOf("com.android.chrome"), delegate.reads)
    }

    /**
     * The synthetic key must never reach `UsageStatsManager` -- it is not a package, so the platform
     * would answer 0 and a time-based kick would silently never fire, which is the defect this whole
     * change is about.
     */
    @Test
    fun `the synthetic key is never passed to the platform`() {
        val delegate = FakeUsage(emptyMap())
        val provider = WebSessionUsageProvider(delegate)
        provider.browserPackage = "org.mozilla.firefox"

        provider.getDailyForegroundTimeMs(WebSessionKey.forDomain("youtube.com")!!)

        assertEquals(listOf("org.mozilla.firefox"), delegate.reads)
    }

    @Test
    fun `a real package passes straight through`() {
        val delegate = FakeUsage(mapOf("com.instagram.android" to 5_000L))
        val provider = WebSessionUsageProvider(delegate)
        provider.browserPackage = "com.android.chrome"

        assertEquals(5_000L, provider.getDailyForegroundTimeMs("com.instagram.android"))
        assertEquals(listOf("com.instagram.android"), delegate.reads)
    }

    /**
     * Between sessions there is no browser to read. A 0 reading is safe: `TimeKickEvaluator` treats
     * it as "start a session", never as a kick -- an unreadable clock must never eject a user.
     */
    @Test
    fun `no active browser reads zero and never kicks`() {
        val delegate = FakeUsage(mapOf("com.android.chrome" to chromeMs))
        val provider = WebSessionUsageProvider(delegate)
        provider.browserPackage = null

        val reading = provider.getDailyForegroundTimeMs(WebSessionKey.forDomain("instagram.com")!!)

        assertEquals(0L, reading)
        assertEquals(emptyList<String>(), delegate.reads)
        assertEquals(
            TimeKickEvaluator.Decision.START_SESSION,
            TimeKickEvaluator.evaluate(
                thresholdMinutes = 30,
                baselineUsageMs = null,
                currentUsageMs = reading
            )
        )
    }

    /**
     * The number the kick is measured against: elapsed = current − baseline, where the baseline was
     * the browser's reading when the user landed on the domain. Time in other apps and with the
     * screen off is absent from both readings, so it is absent from the delta -- inherited from the
     * app path for free.
     */
    @Test
    fun `session elapsed is the browser's delta since the domain was entered`() {
        val delegate = FakeUsage(mapOf("com.android.chrome" to 31L * 60_000L))
        val provider = WebSessionUsageProvider(delegate)
        provider.browserPackage = "com.android.chrome"
        val key = WebSessionKey.forDomain("instagram.com")!!

        val baseline = 1L * 60_000L // reading when the user arrived on the site

        assertEquals(
            TimeKickEvaluator.Decision.KICK,
            TimeKickEvaluator.evaluate(
                thresholdMinutes = 30,
                baselineUsageMs = baseline,
                currentUsageMs = provider.getDailyForegroundTimeMs(key)
            )
        )
    }
}
