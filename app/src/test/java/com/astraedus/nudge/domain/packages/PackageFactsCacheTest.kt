package com.astraedus.nudge.domain.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class PackageFactsCacheTest {
    private val calls = mutableListOf<String>()
    private val fact = PackageFact(AppCategory.GAME, launchable = true)
    private val cache = PackageFactsCache { calls += it; fact }

    @Test
    fun hitDoesNotReResolve() {
        cache.get("a")
        assertSame(fact, cache.get("a"))
        assertEquals(listOf("a"), calls)
    }

    @Test
    fun missResolvesOncePerPackage() {
        cache.get("a"); cache.get("b"); cache.get("a"); cache.get("b")
        assertEquals(listOf("a", "b"), calls)
    }

    @Test
    fun peekNeverResolves() {
        assertNull(cache.peek("a"))
        cache.get("a")
        assertSame(fact, cache.peek("a"))
        assertEquals(listOf("a"), calls)
    }

    @Test
    fun invalidateReResolvesOnlyThatPackage() {
        cache.get("a"); cache.get("b")
        cache.invalidate("a")
        cache.get("a"); cache.get("b")
        assertEquals(listOf("a", "b", "a"), calls)
    }

    @Test
    fun invalidateAllReResolvesEverything() {
        cache.get("a"); cache.get("b")
        cache.invalidateAll()
        cache.get("a"); cache.get("b")
        assertEquals(listOf("a", "b", "a", "b"), calls)
    }

    @Test
    fun throwingResolveIsNotCachedAndPropagates() {
        var attempts = 0
        val flaky = PackageFactsCache {
            attempts++
            if (attempts == 1) throw IllegalStateException("boom")
            fact
        }
        try {
            flaky.get("a")
            fail("expected exception")
        } catch (_: IllegalStateException) {
        }
        assertNull(flaky.peek("a"))
        assertSame(fact, flaky.get("a"))
        assertEquals(2, attempts)
    }
}
