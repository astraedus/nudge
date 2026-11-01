package com.astraedus.nudge.ui.screens.apps

import com.astraedus.nudge.data.repository.InstalledAppsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Device QA (groups PR 2, n=4): Manage Apps opened with no Groups section. The LazyColumn keeps its
 * first visible row by key, and the groups arrived after the first frame, so they were inserted
 * above that anchor. The fix is structural: everything above the apps is ONE row, always first,
 * always the same key, so late groups grow it in place. These pin that for every state the list
 * can be in, now that the apps come in two sections (groups PR 3).
 */
class ManageAppsRowsTest {

    private fun app(pkg: String, summary: String? = null) = AppRow(
        appInfo = InstalledAppsRepository.AppInfo(packageName = pkg, appName = pkg, icon = null),
        summary = summary,
        isOn = summary != null
    )

    private val withRules = listOf(app("com.example.ruled", "Delay 15s"), app("com.example.other", "Hard Block"))
    private val others = listOf(app("com.example.one"), app("com.example.two"), app("com.example.three"))

    private val states = listOf(
        ManageAppsSections(),
        ManageAppsSections(otherApps = others.take(1)),
        ManageAppsSections(otherApps = others, otherAppsTitle = ManageAppsSections.OTHER_APPS_TITLE),
        ManageAppsSections(appsWithRules = withRules, otherAppsTitle = ManageAppsSections.OTHER_APPS_TITLE),
        ManageAppsSections(
            appsWithRules = withRules,
            otherApps = others,
            otherAppsTitle = ManageAppsSections.OTHER_APPS_TITLE
        )
    )

    @Test
    fun `the first row is always the top section, under the same key`() {
        for (sections in states) {
            val first = manageAppsRows(sections).first()
            assertEquals(ManageAppsRow.Top, first)
            assertEquals(ManageAppsRow.TOP_KEY, first.key)
        }
    }

    @Test
    fun `keys are unique, so no row can take another's place`() {
        for (sections in states) {
            val keys = manageAppsRows(sections).map { it.key }
            assertEquals(keys, keys.distinct())
        }
    }

    @Test
    fun `header keys can never equal a package name`() {
        listOf(ManageAppsRow.TOP_KEY, ManageAppsRow.WITH_RULES_KEY, ManageAppsRow.OTHER_APPS_KEY).forEach {
            assertFalse("$it must not contain a dot", it.contains('.'))
        }
    }

    @Test
    fun `an empty list is the top row alone`() {
        assertEquals(listOf(ManageAppsRow.Top), manageAppsRows(ManageAppsSections()))
    }

    @Test
    fun `apps with rules come before the other apps, each under its heading`() {
        assertEquals(
            listOf(ManageAppsRow.Top) +
                ManageAppsRow.Header(ManageAppsRow.WITH_RULES_KEY, "Apps with rules") +
                withRules.map { ManageAppsRow.App(it) } +
                ManageAppsRow.Header(ManageAppsRow.OTHER_APPS_KEY, "Other apps") +
                others.map { ManageAppsRow.App(it) },
            manageAppsRows(states.last())
        )
    }

    @Test
    fun `a section with no rows has no heading`() {
        assertEquals(
            listOf(ManageAppsRow.Top, ManageAppsRow.Header(ManageAppsRow.OTHER_APPS_KEY, "Apps")) +
                ManageAppsRow.App(others.first()),
            manageAppsRows(states[1])
        )
        assertEquals(
            listOf(ManageAppsRow.Top, ManageAppsRow.Header(ManageAppsRow.WITH_RULES_KEY, "Apps with rules")) +
                withRules.map { ManageAppsRow.App(it) },
            manageAppsRows(states[3])
        )
    }
}
