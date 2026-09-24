package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.domain.surfaces.InstagramSurfaces
import com.astraedus.nudge.domain.surfaces.NodeLocator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HostNodeFinder] is the ONE bridge from a pure [NodeLocator] to a real host-app node, so every
 * mistake available in host-app node lookup is available exactly here and nowhere else.
 *
 * Layer **L1** (`docs/testing-strategy.md`): pure decision logic behind a mocked framework surface.
 * `AccessibilityNodeInfo` needs mockk in a JVM test (`tasks/lessons.md`, 2026-06-16) and that is all
 * it needs — none of these cases wants a device, and the two that could not be written this way
 * (`findPlacement`'s three-line `Rect` read) are documented as residue on the function itself and
 * deliberately have no test.
 *
 * Every Instagram id and label below is READ FROM [InstagramSurfaces] rather than typed out. Two
 * reasons: fixture honesty (`docs/testing-strategy.md` rule (b) — never hand-type a value production
 * derives), and the adapter is the one file in the repo allowed to hold `com.instagram.android:id/…`
 * strings at all.
 */
@Suppress("DEPRECATION") // AccessibilityNodeInfo.recycle: the ownership rule under test is about it.
class HostNodeFinderTest {

    // ------------------------------------------------------------------------------------------
    // Fakes. A node answers only what it was told; nothing here relies on a relaxed default for a
    // value a decision reads, because a silent "" or 0 would make a broken locator look like a
    // working one.
    // ------------------------------------------------------------------------------------------

    private fun node(
        viewId: String? = null,
        text: String? = null,
        desc: String? = null,
        clickable: Boolean = false,
        children: List<AccessibilityNodeInfo> = emptyList(),
        byId: Map<String, List<AccessibilityNodeInfo>> = emptyMap(),
        byText: Map<String, List<AccessibilityNodeInfo>> = emptyMap()
    ): AccessibilityNodeInfo {
        val n = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { n.viewIdResourceName } returns viewId
        every { n.text } returns text
        every { n.contentDescription } returns desc
        every { n.isClickable } returns clickable
        every { n.parent } returns null
        every { n.childCount } returns children.size
        every { n.getChild(any()) } answers { children.getOrNull(firstArg()) }
        every { n.findAccessibilityNodeInfosByViewId(any()) } answers {
            byId[firstArg<String>()] ?: emptyList()
        }
        every { n.findAccessibilityNodeInfosByText(any()) } answers {
            byText[firstArg<String>()] ?: emptyList()
        }
        return n
    }

    /** Link [child] to [parent] after the fact, since each needs the other to exist first. */
    private fun parentOf(child: AccessibilityNodeInfo, parent: AccessibilityNodeInfo?) {
        every { child.parent } returns parent
    }

    private val clipsTab = NodeLocator(viewIds = listOf(InstagramSurfaces.ID_CLIPS_TAB))

    // ------------------------------------------------------------------------------------------
    // Strategy order: ids first, then text, then content-description.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a view id match is found`() {
        val tab = node(viewId = InstagramSurfaces.ID_CLIPS_TAB, clickable = true)
        val root = node(byId = mapOf(InstagramSurfaces.ID_CLIPS_TAB to listOf(tab)))

        assertTrue(HostNodeFinder.exists(root, clipsTab))
        assertSame(tab, HostNodeFinder.findClickable(root, clipsTab))
    }

    /**
     * The fallback fires only when the reliable key found nothing. An UNSCOPED locator carrying both
     * must not consult the localized, copy-fragile handle while the compiled-in one is answering.
     *
     * Deliberately NOT written against `followingSteer.menuItemLabel`, which is the app's one SCOPED
     * locator and obeys a different rule (see the scoped cases below). This test previously used it
     * and asserted the OR behaviour, i.e. it had the old semantics written into it as a contract —
     * re-stated rather than deleted, per `tasks/lessons.md` 2026-09-21 ("a test whose numbers encode
     * the bug must be re-stated"). The chain it is actually about is exercised here on a locator that
     * genuinely wants it.
     */
    @Test
    fun `text is consulted only when the view ids miss`() {
        val locator = NodeLocator(
            viewIds = listOf(InstagramSurfaces.ID_CONTEXT_MENU_ITEM_LABEL),
            texts = listOf(InstagramSurfaces.LABEL_FOLLOWING)
        )
        assertFalse("this case is about the OR chain, so the locator must not be scoped", locator.isScopedLabel)

        val label = node(text = InstagramSurfaces.LABEL_FOLLOWING, clickable = true)
        val idsMiss = node(byText = mapOf(InstagramSurfaces.LABEL_FOLLOWING to listOf(label)))
        assertSame(
            "text must be the fallback when no id resolves",
            label,
            HostNodeFinder.findClickable(idsMiss, locator)
        )

        val byId = node(viewId = InstagramSurfaces.ID_CONTEXT_MENU_ITEM_LABEL, clickable = true)
        val idsHit = node(
            byId = mapOf(InstagramSurfaces.ID_CONTEXT_MENU_ITEM_LABEL to listOf(byId)),
            byText = mapOf(InstagramSurfaces.LABEL_FOLLOWING to listOf(label))
        )
        assertSame(byId, HostNodeFinder.findClickable(idsHit, locator))
        verify(exactly = 0) { idsHit.findAccessibilityNodeInfosByText(any()) }
    }

    /**
     * THE case that decides what Nudge taps inside somebody else's app.
     *
     * Instagram's dropdown rows all carry `context_menu_item_label` and are told apart only by their
     * label, so the real locator is SCOPED: id AND label. Given two rows, the finder must return the
     * one labelled "Following" — not simply the first one the framework hands back, which is how the
     * steer would end up opening Favorites.
     */
    @Test
    fun `a scoped locator picks the row whose label matches, not the first row`() {
        val favorites = node(
            viewId = InstagramSurfaces.ID_CONTEXT_MENU_ITEM_LABEL,
            text = "Favorites",
            clickable = true
        )
        val following = node(
            viewId = InstagramSurfaces.ID_CONTEXT_MENU_ITEM_LABEL,
            text = InstagramSurfaces.LABEL_FOLLOWING,
            clickable = true
        )
        // Favorites FIRST, so an id-only lookup would return the wrong row.
        val root = node(
            byId = mapOf(
                InstagramSurfaces.ID_CONTEXT_MENU_ITEM_LABEL to listOf(favorites, following)
            )
        )

        val locator = InstagramSurfaces.followingSteer.menuItemLabel
        assertTrue("the production menu locator must be scoped", locator.isScopedLabel)
        assertSame(following, HostNodeFinder.findClickable(root, locator))
    }

    /**
     * A scoped locator must NOT fall back to a text search when the id is present but no label
     * matches. `findAccessibilityNodeInfosByText` is a case-insensitive substring search over the
     * whole tree, so on the home feed a caption containing the word "following" matches and the
     * clickable ancestor of a caption is somebody's post. Nothing found must mean nothing found.
     */
    @Test
    fun `a scoped locator does not fall back to a text search`() {
        val unmatchedRow = node(
            viewId = InstagramSurfaces.ID_CONTEXT_MENU_ITEM_LABEL,
            text = "Favorites",
            clickable = true
        )
        val caption = node(text = "following my dreams", clickable = true)
        val root = node(
            byId = mapOf(InstagramSurfaces.ID_CONTEXT_MENU_ITEM_LABEL to listOf(unmatchedRow)),
            byText = mapOf(InstagramSurfaces.LABEL_FOLLOWING to listOf(caption))
        )

        assertNull(HostNodeFinder.findClickable(root, InstagramSurfaces.followingSteer.menuItemLabel))
        verify(exactly = 0) { root.findAccessibilityNodeInfosByText(any()) }
    }

    /**
     * The regression that would have cost us Tab Vanish entirely. The Reels tab locator pairs
     * `clips_tab` with the content-description "Reels" so that EITHER can find the tab when a host
     * build exposes only one — so it must stay unscoped. Deriving "scoped" from "carries both an id
     * and a label" (the first version of this feature did) silently turns that fallback into a
     * requirement and the cover stops appearing in every locale whose description is not English.
     */
    @Test
    fun `the reels tab locator is not scoped, so either handle can find it`() {
        val tabLocator = InstagramSurfaces.vanishableTabs.getValue("REELS")
        assertFalse(
            "the tab locator must remain OR: id or content-description, whichever this build exposes",
            tabLocator.isScopedLabel
        )

        val byDescOnly = node(desc = InstagramSurfaces.DESC_REELS_TAB, clickable = true)
        val root = node(children = listOf(byDescOnly))

        assertTrue(
            "a tab carrying only the content-description must still be found",
            HostNodeFinder.exists(root, tabLocator)
        )
    }

    /**
     * `findAccessibilityNodeInfosByText` matches case-insensitive SUBSTRINGS, so the framework hands
     * back nodes a locator does not actually want. "Followingsuggestions" must not be taken for the
     * "Following" row — the steer would click the wrong thing in somebody else's app.
     */
    @Test
    fun `a substring hit from the framework text finder is rejected`() {
        val nearMiss = node(text = InstagramSurfaces.LABEL_FOLLOWING + " suggestions", clickable = true)
        val root = node(byText = mapOf(InstagramSurfaces.LABEL_FOLLOWING to listOf(nearMiss)))

        assertNull(
            HostNodeFinder.findClickable(root, NodeLocator(texts = listOf(InstagramSurfaces.LABEL_FOLLOWING)))
        )
    }

    @Test
    fun `the exact text hit is picked out of a list of near misses`() {
        val nearMiss = node(text = "Not " + InstagramSurfaces.LABEL_FOLLOWING, clickable = true)
        val exact = node(text = InstagramSurfaces.LABEL_FOLLOWING, clickable = true)
        val root = node(byText = mapOf(InstagramSurfaces.LABEL_FOLLOWING to listOf(nearMiss, exact)))

        assertSame(
            exact,
            HostNodeFinder.findClickable(root, NodeLocator(texts = listOf(InstagramSurfaces.LABEL_FOLLOWING)))
        )
    }

    /** The content-description walk is the only strategy with no framework finder behind it. */
    @Test
    fun `a content description is found by the walk when ids and text both miss`() {
        val tab = node(desc = InstagramSurfaces.DESC_REELS_TAB, clickable = true)
        val root = node(children = listOf(node(), tab))
        val locator = InstagramSurfaces.vanishableTabs.getValue("REELS")

        assertTrue(HostNodeFinder.exists(root, locator))
        assertSame(tab, HostNodeFinder.findClickable(root, locator))
    }

    /**
     * The walk is CAPPED, because it runs on the accessibility hot path — a firehose of
     * content-change events (a measured ~800 in three minutes of Instagram use), each of which would
     * otherwise breadth-first the whole feed. A match past the cap is reported as absent, which is
     * the correct failure for a fragile fallback.
     */
    @Test
    fun `the content description walk respects its node cap`() {
        val needle = node(desc = InstagramSurfaces.DESC_REELS_TAB)
        val filler = node(desc = "not it")
        val locator = NodeLocator(contentDescriptions = listOf(InstagramSurfaces.DESC_REELS_TAB))

        // The root itself is dequeued first, so the last child the walk can reach sits at
        // CONTENT_DESC_NODE_LIMIT - 2. One past that is unreachable by construction.
        val reachable = HostNodeFinder.CONTENT_DESC_NODE_LIMIT - 2
        val within = List(HostNodeFinder.CONTENT_DESC_NODE_LIMIT + 100) {
            if (it == reachable) needle else filler
        }
        assertTrue(
            "a match at the last reachable index must be found",
            HostNodeFinder.exists(node(children = within), locator)
        )

        val beyond = List(HostNodeFinder.CONTENT_DESC_NODE_LIMIT + 100) {
            if (it == reachable + 1) needle else filler
        }
        assertFalse(
            "a match one node past the cap must read as absent, not blow the hot path's budget",
            HostNodeFinder.exists(node(children = beyond), locator)
        )
    }

    // ------------------------------------------------------------------------------------------
    // findClickable: the walk UP. This is the function's whole reason to exist.
    // ------------------------------------------------------------------------------------------

    /**
     * The exact measured Instagram shape: `title_logo` is an ImageView reporting `clickable=false`,
     * and the node that actually responds is its `action_bar_title_view` ViewAnimator parent. A tap
     * dispatched at the logo does nothing at all, silently — so returning it would be a steer that
     * fails for a reason no log line explains.
     */
    @Test
    fun `findClickable walks up from an inert match to its clickable ancestor`() {
        val logo = node(viewId = InstagramSurfaces.ID_TITLE_LOGO, clickable = false)
        val titleView = node(viewId = InstagramSurfaces.ID_ACTION_BAR_TITLE_VIEW, clickable = true)
        parentOf(logo, titleView)
        val root = node(byId = mapOf(InstagramSurfaces.ID_TITLE_LOGO to listOf(logo)))

        assertSame(
            titleView,
            HostNodeFinder.findClickable(root, NodeLocator(viewIds = listOf(InstagramSurfaces.ID_TITLE_LOGO)))
        )
    }

    /** The other measured shape: a row's label TextView under the clickable `context_menu_item`. */
    @Test
    fun `findClickable walks up from a menu label to its clickable row`() {
        val label = node(viewId = InstagramSurfaces.ID_CONTEXT_MENU_ITEM_LABEL, text = InstagramSurfaces.LABEL_FOLLOWING)
        val row = node(viewId = InstagramSurfaces.ID_CONTEXT_MENU_ITEM, clickable = true)
        parentOf(label, row)
        val root = node(byId = mapOf(InstagramSurfaces.ID_CONTEXT_MENU_ITEM_LABEL to listOf(label)))

        assertSame(row, HostNodeFinder.findClickable(root, InstagramSurfaces.followingSteer.menuItemLabel))
    }

    @Test
    fun `findClickable returns null when nothing matches`() {
        assertNull(HostNodeFinder.findClickable(node(), clipsTab))
    }

    /**
     * A match with nothing clickable above it is reported as NO match. Handing back a node that
     * cannot be clicked is precisely the failure this function exists to prevent, and the caller's
     * "not found" path is the one that should run.
     */
    @Test
    fun `findClickable returns null when nothing in the chain is clickable`() {
        val inert = node(viewId = InstagramSurfaces.ID_TITLE_LOGO)
        val chain = (0 until HostNodeFinder.CLICKABLE_ANCESTOR_DEPTH + 2).map { node() }
        parentOf(inert, chain.first())
        chain.zipWithNext { child, parent -> parentOf(child, parent) }
        parentOf(chain.last(), null)
        val root = node(byId = mapOf(InstagramSurfaces.ID_TITLE_LOGO to listOf(inert)))

        assertNull(
            HostNodeFinder.findClickable(root, NodeLocator(viewIds = listOf(InstagramSurfaces.ID_TITLE_LOGO)))
        )
    }

    @Test
    fun `an empty locator can never match`() {
        val root = node(byId = mapOf("anything" to listOf(node(clickable = true))))
        assertFalse(HostNodeFinder.exists(root, NodeLocator()))
        assertNull(HostNodeFinder.findClickable(root, NodeLocator()))
    }

    // ------------------------------------------------------------------------------------------
    // Ownership. The returned node is the caller's; everything else is recycled.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `the node handed back is not recycled, and the ones beside it are`() {
        val wanted = node(viewId = InstagramSurfaces.ID_CLIPS_TAB, clickable = true)
        val spare = node(viewId = InstagramSurfaces.ID_CLIPS_TAB, clickable = true)
        val root = node(byId = mapOf(InstagramSurfaces.ID_CLIPS_TAB to listOf(wanted, spare)))

        assertSame(wanted, HostNodeFinder.findClickable(root, clipsTab))
        verify(exactly = 0) { wanted.recycle() }
        verify(exactly = 1) { spare.recycle() }
    }

    @Test
    fun `exists recycles everything it found`() {
        val tab = node(viewId = InstagramSurfaces.ID_CLIPS_TAB)
        val root = node(byId = mapOf(InstagramSurfaces.ID_CLIPS_TAB to listOf(tab)))

        assertTrue(HostNodeFinder.exists(root, clipsTab))
        verify(exactly = 1) { tab.recycle() }
    }

    // ------------------------------------------------------------------------------------------
    // Never throws. A host app's tree can go away mid-read, and this service dying means blocking
    // stops completely.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a throwing root yields nothing rather than propagating`() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.findAccessibilityNodeInfosByViewId(any()) } throws IllegalStateException("stale")
        every { root.findAccessibilityNodeInfosByText(any()) } throws IllegalStateException("stale")
        every { root.childCount } throws IllegalStateException("stale")

        assertNull(HostNodeFinder.findClickable(root, clipsTab))
        assertFalse(HostNodeFinder.exists(root, clipsTab))
        assertNull(HostNodeFinder.textOf(root, clipsTab))
        assertNull(HostNodeFinder.findPlacement(root, clipsTab))
        assertEquals(emptySet<String>(), HostNodeFinder.presentViewIds(root, listOf(InstagramSurfaces.ID_CLIPS_TAB)))
        assertEquals(emptySet<String>(), HostNodeFinder.harvestViewIds(root, "com.example:id/"))
    }

    // ------------------------------------------------------------------------------------------
    // textOf and the two id readers.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `textOf prefers text and falls back to the content description`() {
        val titled = node(viewId = InstagramSurfaces.ID_ACTION_BAR_TITLE, text = InstagramSurfaces.LABEL_FOLLOWING)
        val described = node(viewId = InstagramSurfaces.ID_ACTION_BAR_TITLE, desc = InstagramSurfaces.LABEL_FOLLOWING)
        val locator = NodeLocator(viewIds = listOf(InstagramSurfaces.ID_ACTION_BAR_TITLE))

        assertEquals(
            InstagramSurfaces.LABEL_FOLLOWING,
            HostNodeFinder.textOf(node(byId = mapOf(InstagramSurfaces.ID_ACTION_BAR_TITLE to listOf(titled))), locator)
        )
        assertEquals(
            "the measured Following header carries the string in contentDescription too",
            InstagramSurfaces.LABEL_FOLLOWING,
            HostNodeFinder.textOf(node(byId = mapOf(InstagramSurfaces.ID_ACTION_BAR_TITLE to listOf(described))), locator)
        )
        assertNull(HostNodeFinder.textOf(node(), locator))
    }

    @Test
    fun `presentViewIds reports only the ids that resolve`() {
        val root = node(byId = mapOf(InstagramSurfaces.ID_TAB_BAR to listOf(node())))

        assertEquals(
            setOf(InstagramSurfaces.ID_TAB_BAR),
            HostNodeFinder.presentViewIds(
                root,
                listOf(InstagramSurfaces.ID_TAB_BAR, InstagramSurfaces.ID_CLIPS_TAB)
            )
        )
    }

    /**
     * The harvest is what makes an observation COMPLETE for a classifier keying on markers the
     * adapter never had to publish. It must stay inside the host app's own id namespace: an
     * accessibility tree carries system decor too, and a classifier reasoning about Instagram should
     * never see an id that is not Instagram's.
     */
    @Test
    fun `harvestViewIds keeps to the host package and honours its limit`() {
        val prefix = InstagramSurfaces.packageName + ":id/"
        val tree = node(
            viewId = InstagramSurfaces.ID_TAB_BAR,
            children = listOf(
                node(viewId = InstagramSurfaces.ID_CLIPS_TAB),
                node(viewId = "android:id/statusBarBackground"),
                node(viewId = InstagramSurfaces.ID_STICKY_HEADER_LIST)
            )
        )

        assertEquals(
            setOf(
                InstagramSurfaces.ID_TAB_BAR,
                InstagramSurfaces.ID_CLIPS_TAB,
                InstagramSurfaces.ID_STICKY_HEADER_LIST
            ),
            HostNodeFinder.harvestViewIds(tree, prefix)
        )

        assertEquals(
            "a limit of 1 reaches the root and nothing under it",
            setOf(InstagramSurfaces.ID_TAB_BAR),
            HostNodeFinder.harvestViewIds(tree, prefix, limit = 1)
        )
    }
}
