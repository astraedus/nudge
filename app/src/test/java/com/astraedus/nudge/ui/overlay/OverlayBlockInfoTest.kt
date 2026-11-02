package com.astraedus.nudge.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** L1 (pure unit): the block screen's All apps decisions (spec 3.9). */
class OverlayBlockInfoTest {

    private val always: (String) -> Boolean = { true }

    @Test
    fun `allowed icons keep list order`() {
        assertEquals(
            listOf("c", "a", "b"),
            OverlayBlockInfo.allowedIconPackages(listOf("c", "a", "b"), emptySet(), always)
        )
    }

    @Test
    fun `allowed icons are capped at four`() {
        val result = OverlayBlockInfo.allowedIconPackages(
            listOf("a", "b", "c", "d", "e", "f"), emptySet(), always
        )
        assertEquals(listOf("a", "b", "c", "d"), result)
    }

    @Test
    fun `the blocked package is never offered`() {
        val result = OverlayBlockInfo.allowedIconPackages(
            listOf("a", "blocked", "b"), setOf("blocked"), always
        )
        assertEquals(listOf("a", "b"), result)
    }

    @Test
    fun `apps that are not launchable are dropped before the cap applies`() {
        val result = OverlayBlockInfo.allowedIconPackages(
            listOf("gone1", "gone2", "a", "b", "c", "d", "e"), emptySet()
        ) { !it.startsWith("gone") }
        assertEquals(listOf("a", "b", "c", "d"), result)
    }

    @Test
    fun `blank and duplicate entries are dropped`() {
        assertEquals(
            listOf("a", "b"),
            OverlayBlockInfo.allowedIconPackages(listOf("a", "", "a", "b"), emptySet(), always)
        )
    }

    @Test
    fun `nothing surviving gives an empty list so the row is hidden`() {
        assertTrue(OverlayBlockInfo.allowedIconPackages(listOf("a"), setOf("a"), always).isEmpty())
        assertTrue(OverlayBlockInfo.allowedIconPackages(emptyList(), emptySet(), always).isEmpty())
    }

    @Test
    fun `an All apps block shows the all apps title built from the except names`() {
        assertEquals(
            "All apps except Spotify, Maps",
            OverlayBlockInfo.visibleRuleName("All apps", true, listOf("Spotify", "Maps"))
        )
        assertEquals("All apps", OverlayBlockInfo.visibleRuleName("All apps", true, emptyList()))
    }

    @Test
    fun `a normal block keeps the name the service sent`() {
        assertEquals("Social", OverlayBlockInfo.visibleRuleName("Social", false, listOf("ignored")))
        assertNull(OverlayBlockInfo.visibleRuleName(null, false, emptyList()))
    }

    @Test
    fun `until line is present only when the block carries an end time`() {
        assertEquals("Until 8:42 AM", OverlayBlockInfo.untilLine(123L) { "8:42 AM" })
        assertNull(OverlayBlockInfo.untilLine(null) { error("must not format without an end time") })
    }

    @Test
    fun `the redirect bubble is hidden for All apps and shown otherwise`() {
        assertFalse(OverlayBlockInfo.showRedirectBubble(isAllApps = true))
        assertTrue(OverlayBlockInfo.showRedirectBubble(isAllApps = false))
    }
}
