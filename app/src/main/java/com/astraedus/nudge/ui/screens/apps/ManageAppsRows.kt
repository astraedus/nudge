package com.astraedus.nudge.ui.screens.apps

/**
 * The rows of Manage Apps' list, in order, each with the key the LazyColumn keeps it by.
 *
 * ## Why the top of the list is ONE row with ONE key
 *
 * A LazyColumn keeps its first visible row in place by key. The screen's first frame is the
 * ViewModel's initial state, which has no groups yet, so if every group were its own row, the first
 * row on screen would be "Block several apps". When the groups arrived a moment later they were
 * inserted ABOVE that anchor, i.e. above the viewport, and the user opened Manage Apps to a list
 * with no Groups section at all (device QA, groups PR 2, seen 4 times from Home and after a save).
 *
 * So everything above the app lists ("All apps", the Groups heading, the group rows, "Block several
 * apps") is a single [Top] row that is ALWAYS first and always [TOP_KEY].
 * Late groups grow that row in place instead of being inserted before the anchor. Groups are few,
 * so laying them out non-lazily inside one row costs nothing. [ManageAppsRowsTest] pins the
 * invariant for every state the list can be in.
 *
 * The app sections below it are lazy rows: there can be hundreds of apps.
 */
sealed interface ManageAppsRow {
    val key: String

    /** "All apps", the Groups heading and rows, "Block several apps": each when relevant. */
    data object Top : ManageAppsRow {
        override val key = TOP_KEY
    }

    /** A section heading. Its key has no dot, so it can never equal a package name. */
    data class Header(override val key: String, val title: String) : ManageAppsRow

    data class App(val app: AppRow) : ManageAppsRow {
        // A package name always contains a dot, so it can never equal a fixed key above.
        override val key = app.packageName
    }

    companion object {
        const val TOP_KEY = "top"
        const val WITH_RULES_KEY = "header_with_rules"
        const val OTHER_APPS_KEY = "header_other_apps"
    }
}

/**
 * The list's rows for [sections]: [ManageAppsRow.Top] first whatever has loaded, then "Apps with
 * rules" and the other apps, each under its heading and only when it has rows.
 */
fun manageAppsRows(sections: ManageAppsSections): List<ManageAppsRow> = buildList {
    add(ManageAppsRow.Top)
    if (sections.appsWithRules.isNotEmpty()) {
        add(ManageAppsRow.Header(ManageAppsRow.WITH_RULES_KEY, ManageAppsSections.APPS_WITH_RULES_TITLE))
        sections.appsWithRules.mapTo(this) { ManageAppsRow.App(it) }
    }
    if (sections.otherApps.isNotEmpty()) {
        add(ManageAppsRow.Header(ManageAppsRow.OTHER_APPS_KEY, sections.otherAppsTitle))
        sections.otherApps.mapTo(this) { ManageAppsRow.App(it) }
    }
}
