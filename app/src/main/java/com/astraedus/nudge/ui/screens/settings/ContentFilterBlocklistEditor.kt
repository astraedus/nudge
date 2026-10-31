package com.astraedus.nudge.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.runtime.Composable
import com.astraedus.nudge.domain.ContentFilterUserList
import com.astraedus.nudge.domain.lock.ContentFilterBlocklistWeakening

internal const val BLOCKLIST_TITLE = "Blocked words & sites"
internal const val BLOCKLIST_SUBTITLE =
    "Words and phrases block pages and searches containing them. A site blocks pages on that " +
        "site and its subdomains."
internal const val BLOCKLIST_STRICT_SUFFIX = " Removing one requires the unlock challenge."
internal const val BLOCKLIST_UNLOCK_PROMPT = "Remove a blocked word or site"
internal const val BLOCKLIST_INVALID =
    "Enter a word or a site, up to ${ContentFilterUserList.MAX_ENTRY_LENGTH} characters."
internal const val BLOCKLIST_DUPLICATE = "Already on the list."
internal const val BLOCKLIST_FULL =
    "The list is full (${ContentFilterUserList.MAX_ENTRIES}). Remove one first."
internal const val BLOCKLIST_EMPTY = "Nothing blocked yet."

private val BLOCKLIST_COPY = UserListCopy(
    title = BLOCKLIST_TITLE,
    icon = Icons.Outlined.Block,
    subtitle = BLOCKLIST_SUBTITLE,
    empty = BLOCKLIST_EMPTY
)

/**
 * The block list editor's one decision, the mirror of [allowlistSubmit]: adding a block is
 * strengthening, so it never needs the challenge. The gated direction is removal, which the host
 * screen handles before it calls the preference writer
 * ([com.astraedus.nudge.domain.lock.ContentFilterBlocklistWeakening]).
 */
internal fun blocklistSubmit(input: String, current: Set<String>): UserListSubmit {
    val entry = ContentFilterUserList.normalize(input)
        ?: return UserListSubmit.Rejected(BLOCKLIST_INVALID)
    if (entry in current) return UserListSubmit.Rejected(BLOCKLIST_DUPLICATE)
    if (current.size >= ContentFilterUserList.MAX_ENTRIES) {
        return UserListSubmit.Rejected(BLOCKLIST_FULL)
    }
    return UserListSubmit.Add(entry = entry, needsUnlock = false)
}

/**
 * Removing one entry from the block list: the host screen's whole decision, pure so it is testable
 * without Compose. A removal that loses a block weakens the filter, so Strict Mode gates it; one
 * that changes nothing (entry absent, or already canonicalised away) does not.
 */
internal fun blocklistRemoveNeedsUnlock(
    entry: String,
    current: Set<String>,
    strictModeEnabled: Boolean
): Boolean = ContentFilterBlocklistWeakening.requiresUnlock(
    current = current,
    next = current - entry,
    strictModeEnabled = strictModeEnabled
)

/**
 * "Blocked words & sites": the user's own additions to the content filter. Shown whenever the
 * filter is on, deliberately independent of "Strict keyword matching", which ships off.
 */
@Composable
internal fun ContentFilterBlocklistEditor(
    entries: Set<String>,
    strictModeEnabled: Boolean,
    onAdd: (entry: String, needsUnlock: Boolean) -> Unit,
    onRemove: (String) -> Unit
) {
    UserListEditor(
        copy = BLOCKLIST_COPY.copy(
            subtitle = BLOCKLIST_SUBTITLE +
                if (strictModeEnabled) BLOCKLIST_STRICT_SUFFIX else ""
        ),
        entries = entries,
        submit = { blocklistSubmit(it, entries) },
        onAdd = onAdd,
        onRemove = onRemove
    )
}
