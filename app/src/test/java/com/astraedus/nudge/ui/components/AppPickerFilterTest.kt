package com.astraedus.nudge.ui.components

import com.astraedus.nudge.data.repository.InstalledAppsRepository.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AppPickerFilterTest {

    private val apps = listOf(
        AppInfo("com.cal", "Calendar", null),
        AppInfo("com.cam", "Camera", null),
        AppInfo("com.maps", "Maps", null)
    )

    @Test
    fun blankQueryReturnsAllInOrder() {
        assertEquals(apps, filterPickerApps(apps, ""))
        assertEquals(apps, filterPickerApps(apps, "   "))
    }

    @Test
    fun matchIsCaseInsensitive() {
        assertEquals(listOf("Camera"), filterPickerApps(apps, "CAMERA").map { it.appName })
    }

    @Test
    fun queryIsTrimmed() {
        assertEquals(listOf("Maps"), filterPickerApps(apps, "  maps ").map { it.appName })
    }

    @Test
    fun orderIsPreserved() {
        assertEquals(listOf("Calendar", "Camera"), filterPickerApps(apps, "ca").map { it.appName })
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertEquals(emptyList<AppInfo>(), filterPickerApps(apps, "zzz"))
    }

    @Test
    fun toggleAddsAndRemoves() {
        val added = toggled(setOf("a"), "b")
        assertEquals(setOf("a", "b"), added)
        assertEquals(setOf("a"), toggled(added, "b"))
    }

    @Test
    fun titleReflectsCount() {
        assertEquals("Add apps", pickerTitle(0))
        assertEquals("1 selected", pickerTitle(1))
        assertEquals("3 selected", pickerTitle(3))
    }
}
