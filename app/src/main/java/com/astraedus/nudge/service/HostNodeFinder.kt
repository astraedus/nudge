package com.astraedus.nudge.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.domain.surfaces.NodeLocator
import com.astraedus.nudge.domain.surfaces.TabCoverPlacement

/**
 * The ONE place a pure [NodeLocator] becomes a real `AccessibilityNodeInfo` lookup.
 *
 * `domain/surfaces/` describes WHICH node to find, as data, with no `android.*` import. This object
 * is the other half of that seam: it is the only code in the tab-vanish / following-steer features
 * allowed to walk a host app's tree. Nothing else may do node lookups for them — not the overlay
 * manager, not the steer executor, not the service. One implementation means one place where the
 * ids-first ordering, the bounded walk, the recycling discipline and the never-throw rule are
 * stated, instead of four subtly different copies of each.
 *
 * ## Strategy order, and why
 *
 * [NodeLocator] documents its own field order and this object obeys it: [NodeLocator.viewIds] first,
 * then [NodeLocator.texts], then [NodeLocator.contentDescriptions]. A resource id is compiled into
 * the host APK and survives locale changes and copy edits; text and content-descriptions are
 * localized, A/B-tested and rewritten without notice. The first strategy that finds anything wins,
 * so the fragile handles are only ever consulted when the reliable one found nothing at all.
 *
 * The framework supplies a finder for ids ([AccessibilityNodeInfo.findAccessibilityNodeInfosByViewId])
 * and one for text ([AccessibilityNodeInfo.findAccessibilityNodeInfosByText]). It supplies **none**
 * for content-descriptions, so that strategy is a hand-rolled breadth-first walk capped at
 * [CONTENT_DESC_NODE_LIMIT] nodes — the same cap and the same reason as
 * `InAppDetector.dumpViewIdsForDiagnosis`: this runs on the accessibility hot path, which sees a
 * firehose of content-change events (a measured ~800 in three minutes of Instagram use), and an
 * uncapped walk of a feed's tree on every one of them is a budget nobody granted.
 *
 * ## Never throws
 *
 * Every public function wraps its tree reads and fails to `null` / `false` / an empty set, exactly
 * as `InAppDetector.detectFeature` does. A host app's tree can go away mid-read, and a
 * `StaleObjectException` propagating out of here would take the whole accessibility service down —
 * which means blocking stops completely (see CLAUDE.md, "Key conventions"). A feature that silently
 * does nothing is the correct failure for both tab vanish and the steer.
 *
 * ## Recycling
 *
 * Every node LIST obtained from the framework is recycled before returning, per
 * `InAppDetector.recycleNodes`. The one exception is documented on [findClickable]: the node it
 * returns belongs to the caller.
 *
 * The breadth-first walk deliberately does NOT recycle the nodes it visits, matching
 * `InAppDetector.dumpViewIdsForDiagnosis`: the queue holds descendants of nodes already dequeued,
 * `recycle()` has been a deprecated no-op since API 33, and "recycle something another live
 * reference still points at" is a worse bug than the allocation it would save.
 */
object HostNodeFinder {

    /**
     * Cap for the content-description walk. Mirrors `InAppDetector.DIAGNOSTIC_NODE_LIMIT` — same hot
     * path, same reason, same number, so the two bounded walks in `service/` cost the same.
     */
    const val CONTENT_DESC_NODE_LIMIT = 800

    /**
     * How far up the parent chain [findClickable] will look. Matches `InAppDetector.isInSelectedTab`,
     * which walks the same trees with the same bound. Instagram's measured gaps are one level
     * (`title_logo` -> `action_bar_title_view`, `context_menu_item_label` -> `context_menu_item`);
     * five is slack for a host app that adds a wrapper, not an invitation to click a whole screen.
     */
    const val CLICKABLE_ANCESTOR_DEPTH = 5

    /**
     * Where a cover over [locator]'s node goes, or null if the node is absent or its bounds cannot
     * be trusted.
     *
     * Returns the PURE [TabCoverPlacement], never an `android.graphics.Rect`. That is deliberate and
     * it is the reason this function exists in this shape: `android.graphics.Rect` is a throwing stub
     * in this repo's JVM unit tests (`testOptions { unitTests.isReturnDefaultValues = true }` is
     * deliberately NOT set — turning every unmocked android call into a silent 0/null would change
     * behaviour under ~1550 existing tests), so a `Rect` in a signature is a `Rect` in every caller
     * and in every caller's test. Handing back validated pure data keeps the untestable surface at
     * three lines instead of spreading it.
     *
     * Those three lines — allocate, `getBoundsInScreen`, read four fields — are the genuinely
     * untestable RESIDUE, isolated here exactly as `docs/TESTING.md` prescribes: one field-copying
     * function, no logic. Do not write a test for it and do not reach for the default-values flag to
     * make one possible. Everything with a decision in it — which strategy matched, the bounded
     * walk, the validation — is either tested here or tested in [TabCoverPlacement.of].
     */
    fun findPlacement(root: AccessibilityNodeInfo, locator: NodeLocator): TabCoverPlacement? = try {
        val nodes = matchesFor(root, locator)
        if (nodes.isEmpty()) {
            null
        } else {
            // The residue. Four field reads off one node; TabCoverPlacement.of owns the validation.
            val bounds = Rect()
            nodes[0].getBoundsInScreen(bounds)
            val placement = TabCoverPlacement.of(bounds.left, bounds.top, bounds.right, bounds.bottom)
            recycleNodes(nodes)
            placement
        }
    } catch (_: Exception) {
        null
    }

    /** True when [locator] resolves to at least one node in [root]. Recycles everything it finds. */
    fun exists(root: AccessibilityNodeInfo, locator: NodeLocator): Boolean = try {
        val nodes = matchesFor(root, locator)
        val found = nodes.isNotEmpty()
        recycleNodes(nodes)
        found
    } catch (_: Exception) {
        false
    }

    /**
     * The nearest node that can actually be clicked for [locator]: the match itself if it is
     * clickable, otherwise the closest ancestor within [CLICKABLE_ANCESTOR_DEPTH] that is. Null when
     * nothing matched, or when nothing in that chain is clickable.
     *
     * **Returning a non-clickable node is the failure mode this function exists to prevent.** Both
     * of Instagram's measured steer targets are inert: `title_logo` (the Home-feed logo) reports
     * `clickable=false` and its clickable parent is the `action_bar_title_view` ViewAnimator, and a
     * dropdown row's LABEL is a TextView whose clickable parent is the `context_menu_item` Button.
     * `performAction(ACTION_CLICK)` on either child returns false and nothing happens — a steer that
     * fails for a reason no log line explains. So a match that cannot be clicked is reported as no
     * match, and the caller's "not found" path is the one that runs.
     *
     * **The returned node is the CALLER'S to use and is NOT recycled here.** Every other node this
     * call obtains is. Callers perform their action on it and let it go; nothing in this codebase
     * recycles it either, because `recycle()` has been a deprecated no-op since API 33 and the node
     * is unreachable the moment the caller's frame returns.
     */
    fun findClickable(root: AccessibilityNodeInfo, locator: NodeLocator): AccessibilityNodeInfo? = try {
        val nodes = matchesFor(root, locator)
        if (nodes.isEmpty()) {
            null
        } else {
            val match = nodes[0]
            val clickable = clickableSelfOrAncestor(match)
            // Recycle every node we obtained except the one we hand back.
            recycleNodes(nodes.filter { it !== clickable })
            clickable
        }
    } catch (_: Exception) {
        null
    }

    /**
     * The text [locator]'s node carries: its `text`, or its `contentDescription` when it has no text.
     *
     * Both are read because host-app chrome splits the string across the two arbitrarily — the
     * measured Instagram `action_bar_title` carries "Following" in each, while a label TextView
     * carries it only in `text`. Null when the node is absent or carries neither.
     */
    fun textOf(root: AccessibilityNodeInfo, locator: NodeLocator): String? = try {
        val nodes = matchesFor(root, locator)
        if (nodes.isEmpty()) {
            null
        } else {
            val node = nodes[0]
            val text = node.text?.toString()?.takeIf { it.isNotEmpty() }
                ?: node.contentDescription?.toString()?.takeIf { it.isNotEmpty() }
            recycleNodes(nodes)
            text
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Which of [ids] are present in [root] — the id half of a
     * [com.astraedus.nudge.domain.surfaces.SurfaceObservation].
     *
     * Asks for a known set rather than harvesting every id in the tree: the adapter declares which
     * ids its classifier cares about, so this is a handful of indexed framework lookups instead of a
     * full walk on the hot path. It also means no id the caller did not ask for can end up in a
     * committed fixture.
     */
    fun presentViewIds(root: AccessibilityNodeInfo, ids: Collection<String>): Set<String> = try {
        val present = LinkedHashSet<String>()
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: continue
            if (nodes.isNotEmpty()) present += id
            recycleNodes(nodes)
        }
        present
    } catch (_: Exception) {
        emptySet()
    }

    /**
     * Every resource id in [root]'s tree that starts with [prefix], from a breadth-first walk capped
     * at [limit] dequeued nodes.
     *
     * This is the id half of a [com.astraedus.nudge.domain.surfaces.SurfaceObservation] as that class
     * defines it — "every fully-qualified resource id seen in this tree read" — and it is what makes
     * a surface classifier work without the adapter having to publish its classifier's id list
     * through the interface. A targeted [presentViewIds] can only confirm ids somebody already named;
     * a classifier that keys on the ABSENCE of an id (Instagram's Home feed is identified partly by
     * `tab_bar` being present and partly by other tabs' markers not being) needs the whole set.
     *
     * Reads ONLY `viewIdResourceName` — never `text`, never `contentDescription`. On these screens
     * those are the user's captions and private messages, this repo commits its fixtures publicly,
     * and Nudge ships with no internet permission precisely so that stays true. The same constraint,
     * for the same reason, as `InAppDetector.dumpViewIdsForDiagnosis`, whose cap this shares.
     *
     * [prefix] keeps other packages' ids out of the set: an accessibility tree can contain system
     * decor, and a classifier reasoning about a host app should never see ids that are not its.
     */
    fun harvestViewIds(
        root: AccessibilityNodeInfo,
        prefix: String,
        limit: Int = CONTENT_DESC_NODE_LIMIT
    ): Set<String> = try {
        val ids = LinkedHashSet<String>()
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        while (queue.isNotEmpty() && visited < limit) {
            val node = queue.removeFirst()
            visited++
            node.viewIdResourceName?.let { if (it.startsWith(prefix)) ids += it }
            for (i in 0 until node.childCount) {
                queue += node.getChild(i) ?: continue
            }
        }
        ids
    } catch (_: Exception) {
        emptySet()
    }

    /**
     * The first strategy that finds anything, in [NodeLocator]'s own reliability order. The returned
     * list is framework-owned: every caller either recycles it or documents why it did not.
     */
    private fun matchesFor(
        root: AccessibilityNodeInfo,
        locator: NodeLocator
    ): List<AccessibilityNodeInfo> {
        if (locator.isEmpty) return emptyList()

        for (id in locator.viewIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: continue
            if (nodes.isNotEmpty()) return nodes
        }
        for (text in locator.texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
            // findAccessibilityNodeInfosByText matches case-insensitively on a SUBSTRING, and it
            // searches contentDescription as well as text, so it over-matches in two directions.
            // The locator's own exact rule decides which of the framework's hits actually counts —
            // on either field, because which of the two carries a host app's label is arbitrary
            // (Instagram's measured dropdown row has it in contentDescription, its label child in
            // text).
            val exact = nodes.firstOrNull {
                locator.matches(null, it.text?.toString(), it.contentDescription?.toString())
            }
            if (exact != null) return listOf(exact) + nodes.filter { it !== exact }
            recycleNodes(nodes)
        }
        if (locator.contentDescriptions.isNotEmpty()) {
            val found = firstByContentDescription(root, locator)
            if (found != null) return listOf(found)
        }
        return emptyList()
    }

    /**
     * Bounded breadth-first search for a node whose `contentDescription` satisfies [locator].
     *
     * The framework has no content-description finder, so this is the one hand-rolled walk. Capped
     * at [CONTENT_DESC_NODE_LIMIT] dequeued nodes; a deeper match is reported as absent, which is
     * the correct failure for a fallback strategy. Matching goes through [NodeLocator.matches] so
     * the RULE lives in the pure layer and a fixture test exercises the same rule production does.
     */
    private fun firstByContentDescription(
        root: AccessibilityNodeInfo,
        locator: NodeLocator
    ): AccessibilityNodeInfo? {
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        while (queue.isNotEmpty() && visited < CONTENT_DESC_NODE_LIMIT) {
            val node = queue.removeFirst()
            visited++
            if (locator.matches(null, null, node.contentDescription?.toString())) return node
            for (i in 0 until node.childCount) {
                queue += node.getChild(i) ?: continue
            }
        }
        return null
    }

    /** [node] if it is clickable, else the nearest clickable ancestor within the depth bound. */
    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var current = node.parent
        var depth = 0
        while (current != null && depth < CLICKABLE_ANCESTOR_DEPTH) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    /** Mirrors `InAppDetector.recycleNodes`: best effort, and an already-recycled node is fine. */
    private fun recycleNodes(nodes: List<AccessibilityNodeInfo>) {
        for (node in nodes) {
            try {
                @Suppress("DEPRECATION")
                node.recycle()
            } catch (_: Exception) {
                // Already recycled -- ignore.
            }
        }
    }
}
