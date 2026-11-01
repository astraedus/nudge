package com.astraedus.nudge.ui.screens.apps

import com.astraedus.nudge.data.repository.InstalledAppsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Device QA (groups PR 2, n=4): Manage Apps opened with no Groups section. The LazyColumn keeps its
 * first visible row by key, and the groups arrived after the first frame, so they were inserted
 * above that anchor. The fix is structural: everything above the apps is ONE row, always first,
 * always the same key, so late groups grow it in place. These pin that for every state the list
 * can be in.
 */
class ManageAppsRowsTest {

    private fun app(pkg: String) = AppWithBlockStatus(
        appInfo = InstalledAppsRepository.AppInfo(packageName = pkg, appName = pkg, icon = null),
        isBlocked = false
    )

    private val states = listOf(
        emptyList(),
        listOf(app("com.example.one")),
        listOf(app("com.example.one"), app("com.example.two"), app("com.example.three"))
    )

    @Test
    fun `the first row is always the top section, under the same key`() {
        for (apps in states) {
            val first = manageAppsRows(apps).first()
            assertEquals(ManageAppsRow.Top, first)
            assertEquals(ManageAppsRow.TOP_KEY, first.key)
        }
    }

    @Test
    fun `keys are unique, so no row can take another's place`() {
        for (apps in states) {
            val keys = manageAppsRows(apps).map { it.key }
            assertEquals(keys, keys.distinct())
        }
    }

    @Test
    fun `apps follow their header in order, and an empty list has no header`() {
        assertEquals(listOf(ManageAppsRow.Top), manageAppsRows(emptyList()))
        val apps = states.last()
        assertEquals(
            listOf(ManageAppsRow.Top, ManageAppsRow.AppsHeader) + apps.map { ManageAppsRow.App(it) },
            manageAppsRows(apps)
        )
    }
}
