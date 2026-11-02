package com.astraedus.nudge.ui.screens.allapps

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
import com.astraedus.nudge.ui.components.PickerGroup
import com.astraedus.nudge.ui.components.rule.RuleSummary

@Immutable
data class AllAppsEditorState(
    val isLoading: Boolean = true,
    /** Null for a rule that has not been saved yet. */
    val ruleId: Long? = null,
    val form: AllAppsForm = AllAppsForm(),
    /**
     * The allowed apps, in the order they were added, including any that are no longer installed:
     * those are hidden from the list but kept, so a reinstalled app is allowed again.
     */
    val allowedPackages: Set<String> = emptySet(),
    /** The allowed groups, in the order they were added. May hold a stale id until the next save. */
    val allowedGroupIds: Set<Long> = emptySet(),
    val device: DeviceApps = DeviceApps(),
    val tables: RuleTables = RuleTables(),
    val showPicker: Boolean = false,
    /** The read-only "Always allowed" sheet. */
    val showAlwaysAllowed: Boolean = false,
    /** "Block N apps all the time?" before an All the time + Hard block save. */
    val showSaveConfirmation: Boolean = false,
    val showDeleteConfirmation: Boolean = false,
    /** Leaving with unsaved edits: "Discard changes?". */
    val showDiscardConfirmation: Boolean = false,
    /**
     * What the editor opened with (the saved rule, or the new-rule defaults), which is what "unsaved
     * edits" are measured against. Null while loading.
     */
    val baseline: AllAppsDraft? = null,
    /** Saved, deleted or discarded: the screen pops. */
    val isFinished: Boolean = false
) {
    /** Everything a save would write; an allowed group that no longer exists is dropped. */
    val draft: AllAppsDraft
        get() = AllAppsDraft(form, allowedPackages, allowedGroupIds).withLiveGroups(tables.liveGroupIds)

    /**
     * Leaving now would lose something. Measured against what the editor opened with, so changing a
     * value and changing it back is not an edit, and a deleted group dropping off the list is not one
     * either.
     */
    val isDirty: Boolean
        get() = baseline != null && !isFinished && draft != baseline.withLiveGroups(tables.liveGroupIds)

    /** "Blocks N apps", live as apps and groups are allowed. */
    val blockedCount: Int
        get() = draft.let { blockedCount(it.form.toRule(null, it.allowedPackages, it.allowedGroupIds), tables, device) }

    /**
     * A rule that blocks no app does nothing, so it cannot be saved: the line under Enabled says
     * "Blocks 0 apps", which is the reason.
     */
    val canSave: Boolean get() = !isLoading && form.isComplete && blockedCount > 0

    /** The groups the picker offers: every group, with its live member count. */
    val pickerGroups: List<PickerGroup>
        get() = tables.groups
            .sortedBy { it.name.lowercase() }
            .map { PickerGroup(id = it.id, name = it.name, memberCount = tables.memberCount(it.id, device)) }
}

/** One allowed group in "Except these": "Work · 6 apps", and "2 still blocked by other rules" when so. */
@Immutable
data class AllowedGroupRow(val groupId: Long, val title: String, val stillBlocked: String?)

/** One allowed app in "Except these", and "Also blocked by Social" when another rule still blocks it. */
@Immutable
data class AllowedAppRow(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val alsoBlocked: String?
)

/** "Except these", top to bottom: allowed groups first, then allowed apps (spec 3.4). */
@Immutable
data class ExceptRows(val groups: List<AllowedGroupRow>, val apps: List<AllowedAppRow>)

/**
 * The rows of "Except these" for [state]. Groups that no longer exist and apps that are no longer
 * installed are not shown; everything else keeps the order it was added in.
 */
fun exceptRows(state: AllAppsEditorState): ExceptRows {
    val tables = state.tables
    val device = state.device
    val otherRules = tables.otherRules(state.ruleId)
    val groups = state.allowedGroupIds
        .filter { it in tables.liveGroupIds }
        .map { id ->
            AllowedGroupRow(
                groupId = id,
                title = RuleSummary.groupTitle(tables.groupNames.getValue(id), tables.memberCount(id, device)),
                stillBlocked = stillBlockedText(stillBlockedCount(id, otherRules, tables, device))
            )
        }
    val apps = state.allowedPackages.mapNotNull { pkg ->
        val app = device.byPackage[pkg] ?: return@mapNotNull null
        AllowedAppRow(
            packageName = pkg,
            appName = app.appName,
            icon = app.icon,
            alsoBlocked = alsoBlockedByText(alsoBlockedBy(pkg, otherRules, tables, device))
        )
    }
    return ExceptRows(groups, apps)
}
