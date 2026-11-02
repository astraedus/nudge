package com.astraedus.nudge.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Layer L1 (pure unit): the picker's Groups section (spec 3.6, the All apps editor's "Add apps or
 * groups"). Search filters groups the way it filters apps, a group toggles like an app, and the
 * title counts apps and groups together.
 */
class AppPickerGroupsTest {

    private val groups = listOf(
        PickerGroup(1, "Games", 4),
        PickerGroup(2, "Social", 6),
        PickerGroup(3, "Work", 0)
    )

    @Test
    fun `a blank query keeps every group in order`() {
        assertEquals(groups, filterPickerGroups(groups, ""))
        assertEquals(groups, filterPickerGroups(groups, "  "))
    }

    @Test
    fun `groups match by name, trimmed and case-insensitive`() {
        assertEquals(listOf("Social"), filterPickerGroups(groups, " SOC ").map { it.name })
        assertEquals(listOf("Games", "Social"), filterPickerGroups(groups, "a").map { it.name })
        assertEquals(emptyList<PickerGroup>(), filterPickerGroups(groups, "zzz"))
    }

    @Test
    fun `a group id toggles in and out`() {
        val added = toggled(setOf(1L), 2L)
        assertEquals(setOf(1L, 2L), added)
        assertEquals(setOf(1L), toggled(added, 2L))
    }

    @Test
    fun `the title counts apps and groups, or says what can be added`() {
        assertEquals("Add apps or groups", pickerTitle(0, offersGroups = true))
        assertEquals("Add apps", pickerTitle(0, offersGroups = false))
        assertEquals("3 selected", pickerTitle(2 + 1, offersGroups = true))
    }

    @Test
    fun `no match names what was searched`() {
        assertEquals("No apps or groups match \"zz\"", noMatchText(" zz ", offersGroups = true))
        assertEquals("No apps match \"zz\"", noMatchText("zz", offersGroups = false))
    }
}
