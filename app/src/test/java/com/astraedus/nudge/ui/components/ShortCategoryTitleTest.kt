package com.astraedus.nudge.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tag on a category member row. The titles are Android's own English `getCategoryTitle` strings,
 * as read off the bench Pixel 3 (API 31) in the PR 6 device QA: the full ones ellipsised on every row.
 */
class ShortCategoryTitleTest {

    private val platformTitles = mapOf(
        "Games" to "Games",
        "Music and audio" to "Music",
        "Movies & Video" to "Movies",
        "Photos & Images" to "Photos",
        "Social & Communication" to "Social",
        "News & Magazines" to "News",
        "Maps & Navigation" to "Maps",
        "Productivity" to "Productivity"
    )

    @Test
    fun `every offered platform title shortens to its first part`() {
        platformTitles.forEach { (full, short) -> assertEquals(full, short, shortCategoryTitle(full)) }
    }

    @Test
    fun `a short title is never longer than the full one and never blank`() {
        (platformTitles.keys + listOf("& Odd", " & Odd", "X")).forEach { full ->
            val short = shortCategoryTitle(full)
            assertTrue(full, short.isNotBlank())
            assertTrue(full, short.length <= full.length)
        }
    }

    @Test
    fun `a title that starts with the separator falls back to the whole title`() {
        assertEquals(" & Odd", shortCategoryTitle(" & Odd"))
    }
}
