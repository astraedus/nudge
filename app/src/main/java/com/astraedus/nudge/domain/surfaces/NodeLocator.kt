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
    val contentDescriptions: List<String> = emptyList(),
    /**
     * Opt in to AND semantics: the id SCOPES and the label SELECTS, and a node must satisfy both.
     * See [isScopedLabel] for when that is the only correct rule.
     *
     * DECLARED, never inferred. It would be tempting to derive this from "has ids AND has labels",
     * and that is wrong: the Reels tab locator carries `clips_tab` AND the description "Reels"
     * precisely so that EITHER can find it when a host-app build exposes only one of them, so
     * inferring intent from which lists are populated would silently convert that fallback into a
     * requirement — and the cover would stop appearing in any locale whose description is not the
     * English string. Two locators, two rules, stated rather than guessed.
     */
    val scopedLabel: Boolean = false
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

    /**
     * True when this locator both SCOPES by view id and SELECTS by label, so a node must satisfy
     * BOTH to match ([matchesScoped]) rather than either ([matches]).
     *
     * ## Why one locator needs AND when the rest want OR
     *
     * The tab locator legitimately wants OR: `clips_tab` OR the content-description "Reels",
     * whichever this Instagram build happens to expose. The dropdown's menu row cannot work that
     * way, and it fails in both directions:
     *
     * - **Id alone is ambiguous.** Every row in the menu carries `context_menu_item_label`, so an
     *   id-first lookup returns whichever row is first in the tree. `logo-menu.xml` holds both
     *   "Following" and "Favorites", so that coin decides which one Nudge taps.
     * - **Text alone is dangerous.** `findAccessibilityNodeInfosByText` is a case-insensitive
     *   SUBSTRING search over the whole tree, so a feed caption containing the word "following"
     *   matches, and the clickable ancestor of a caption is the post.
     *
     * One is a coin flip and the other taps a stranger's post. Both are decided by requiring the id
     * and the label to agree, which is the only thing that actually identifies the row.
     *
     * True only when [scopedLabel] was declared AND there is actually an id and a label to combine —
     * a locator that asks for AND but supplies only one side would otherwise match nothing at all,
     * silently, which is the hardest kind of selector bug to see.
     */
    val isScopedLabel: Boolean
        get() = scopedLabel && viewIds.isNotEmpty() &&
            (texts.isNotEmpty() || contentDescriptions.isNotEmpty())

    /**
     * Whether one node satisfies this locator under AND semantics: its id must be one of [viewIds]
     * AND its [text] or [contentDescription] must be one of the labels.
     *
     * Only meaningful when [isScopedLabel]; for any other locator it is equivalent to requiring the
     * id, which is why callers branch on [isScopedLabel] rather than calling this unconditionally.
     */
    fun matchesScoped(viewId: String?, text: String?, contentDescription: String?): Boolean {
        val idMatches = viewId != null && viewId in viewIds
        if (!idMatches) return false
        val labelMatches = (text != null && text in texts) ||
            (contentDescription != null && contentDescription in contentDescriptions)
        return labelMatches
    }

    /**
     * THE match question: does this node satisfy this locator, under whichever rule the locator
     * declares? [matchesScoped] when [isScopedLabel], else [matches].
     *
     * This exists so the branch lives in ONE place. It was briefly written out twice — once in
     * `HostNodeFinder` for the real node tree and once in the fixture reader for recorded dumps — and
     * two copies of a matching rule is how a fixture ends up agreeing with the test author instead of
     * with the device (`docs/testing-strategy.md` rule (b)): the dump reader would select "Following"
     * under OR while production selected whichever row came first, and the test would be green about
     * the wrong thing. One function, both callers, nothing to keep in step.
     *
     * [matches] and [matchesScoped] stay public and stay unaware of the flag, because the tests that
     * prove the two rules DIFFER have to be able to ask each one directly.
     */
    fun matchesNode(viewId: String?, text: String?, contentDescription: String?): Boolean =
        if (isScopedLabel) {
            matchesScoped(viewId, text, contentDescription)
        } else {
            matches(viewId, text, contentDescription)
        }
}
