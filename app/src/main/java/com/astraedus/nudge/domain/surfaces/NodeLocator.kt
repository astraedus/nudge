package com.astraedus.nudge.domain.surfaces

/**
 * A pure description of how to find ONE node in a HOST app's view tree — Instagram's Reels tab,
 * YouTube's Shorts tab, the title dropdown that opens Instagram's feed switcher.
 *
 * Nothing here touches Android. A locator is data: the service layer is what walks a real
 * `AccessibilityNodeInfo` tree looking for a match, and a JVM test can drive the exact same locator
 * against a recorded hierarchy dump. That is the whole point of the seam — the selectors that
 * change when a host app ships an update are separated from the code that acts on them, so a
 * version bump is a data edit in [InstagramSurfaces] and not a hunt through `service/`.
 *
 * ## Why the fields are ordered ids-first
 *
 * [viewIds] is the reliable key. A resource id is compiled into the host app's APK and is stable
 * across locales, across accessibility settings, and across most releases — `clips_tab` has been
 * `clips_tab` for years. Match on ids whenever an id exists.
 *
 * [texts] and [contentDescriptions] are the **version-fragile fallback**. They are the only handle
 * on a node that carries no id at all (Instagram's title-dropdown menu items all share the single
 * id `context_menu_item`, so the label is the only thing separating "Following" from "Favorites"),
 * but they are localized, they are rewritten by copy changes, and a host app A/B test can change
 * them for some users and not others. A feature that depends on text must fail silently when the
 * text is not found, never retry, and never assume the absence means anything.
 *
 * An empty locator matches nothing. All three lists are OR-ed: a node matches if it satisfies any
 * entry in any populated list.
 */
data class NodeLocator(
    /** Fully-qualified resource ids, e.g. `com.instagram.android:id/clips_tab`. The reliable key. */
    val viewIds: List<String> = emptyList(),
    /** Exact `text` values. Localized and copy-fragile — fallback only. */
    val texts: List<String> = emptyList(),
    /** Exact `contentDescription` values. Localized and copy-fragile — fallback only. */
    val contentDescriptions: List<String> = emptyList()
) {
    /** True when this locator names no way at all to find a node, and so can never match. */
    val isEmpty: Boolean
        get() = viewIds.isEmpty() && texts.isEmpty() && contentDescriptions.isEmpty()

    /**
     * Whether one node's attributes satisfy this locator.
     *
     * Kept here rather than in the service so that the matching RULE is JVM-testable against a
     * recorded hierarchy: the service supplies the three strings off a real node, a test supplies
     * them off a parsed fixture, and both go through this one function.
     */
    fun matches(viewId: String?, text: String?, contentDescription: String?): Boolean =
        (viewId != null && viewId in viewIds) ||
            (text != null && text in texts) ||
            (contentDescription != null && contentDescription in contentDescriptions)
}
