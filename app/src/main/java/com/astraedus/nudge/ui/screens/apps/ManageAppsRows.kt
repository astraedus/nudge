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
 * So everything above the app list (the Groups heading, the group rows, "Block several apps") is a
 * single [Top] row that is ALWAYS first and always [TOP_KEY]. Late groups grow that row in place
 * instead of being inserted before the anchor. Groups are few, so laying them out non-lazily inside
 * one row costs nothing. [ManageAppsRowsTest] pins the invariant for every state the list can be in.
 */
sealed interface ManageAppsRow {
    val key: String

    /** The Groups heading, the group rows and "Block several apps" (each shown only when relevant). */
    data object Top : ManageAppsRow {
        override val key = TOP_KEY
    }

    data object AppsHeader : ManageAppsRow {
        override val key = "apps_header"
    }

    data class App(val app: AppWithBlockStatus) : ManageAppsRow {
        // A package name always contains a dot, so it can never equal the two fixed keys above.
        override val key = app.appInfo.packageName
    }

    companion object {
        const val TOP_KEY = "top"
    }
}

/** The list's rows for [apps]: [ManageAppsRow.Top] first whatever has loaded, then the apps. */
fun manageAppsRows(apps: List<AppWithBlockStatus>): List<ManageAppsRow> = buildList {
    add(ManageAppsRow.Top)
    if (apps.isNotEmpty()) add(ManageAppsRow.AppsHeader)
    apps.mapTo(this) { ManageAppsRow.App(it) }
}
