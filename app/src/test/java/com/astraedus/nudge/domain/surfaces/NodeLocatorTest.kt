package com.astraedus.nudge.domain.surfaces

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NodeLocator]'s two matching rules, and the reason there are two.
 *
 * Most locators want OR: find the node by its id, or by its label if this host-app build happens to
 * expose only that. Exactly one wants AND: the dropdown's menu row, where every row shares one id and
 * only the label tells them apart, and where a bare text search would substring-match a feed caption.
 *
 * The bug this file exists to prevent is not a wrong match — it is the two rules being confused for
 * each other. Applying AND to the tab locator silently breaks Tab Vanish in every non-English locale;
 * applying OR to the menu row taps whichever row came first.
 */
class NodeLocatorTest {

    private val id = "com.instagram.android:id/context_menu_item_label"
    private val otherId = "com.instagram.android:id/clips_tab"

    // -----------------------------------------------------------------------------------------
    // OR semantics: the default
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an unscoped locator matches on the view id alone`() {
        val locator = NodeLocator(viewIds = listOf(otherId), contentDescriptions = listOf("Reels"))
        assertTrue(locator.matches(otherId, null, null))
    }

    @Test
    fun `an unscoped locator matches on the content description alone`() {
        val locator = NodeLocator(viewIds = listOf(otherId), contentDescriptions = listOf("Reels"))
        assertTrue(
            "a build that exposes the description but not the id must still find the tab",
            locator.matches(null, null, "Reels")
        )
    }

    @Test
    fun `an unscoped locator matches on the text alone`() {
        assertTrue(NodeLocator(texts = listOf("Following")).matches(null, "Following", null))
    }

    @Test
    fun `an unscoped locator does not match a node sharing nothing`() {
        val locator = NodeLocator(viewIds = listOf(otherId), contentDescriptions = listOf("Reels"))
        assertFalse(locator.matches("com.instagram.android:id/feed_tab", null, "Home"))
    }

    @Test
    fun `an empty locator matches nothing and knows it`() {
        val empty = NodeLocator()
        assertTrue(empty.isEmpty)
        assertFalse(empty.matches(otherId, "Following", "Reels"))
    }

    /**
     * **The regression that would have cost us the tab cover.**
     *
     * The Reels tab locator carries `clips_tab` AND the description "Reels" precisely so that EITHER
     * can find it. If `isScopedLabel` were derived from "has ids AND has labels" instead of being
     * declared, this locator would flip to AND on its own, and the cover would stop appearing on any
     * build or locale that does not expose the English description. Declared, not inferred.
     */
    @Test
    fun `a locator carrying both ids and labels stays OR unless it declares otherwise`() {
        val tab = NodeLocator(viewIds = listOf(otherId), contentDescriptions = listOf("Reels"))
        assertFalse("scopedLabel defaults to false", tab.isScopedLabel)
        assertTrue("the id alone must still find the tab", tab.matches(otherId, null, null))
        assertTrue(
            "the description alone must still find the tab — this is the locale fallback",
            tab.matches(null, null, "Reels")
        )
    }

    /** The real Reels locator, read from production rather than reconstructed here (rule (b)). */
    @Test
    fun `the shipped reels tab locator is an OR locator`() {
        val reels = InstagramSurfaces.vanishableTabs.getValue("REELS")
        assertFalse(
            "Tab Vanish depends on id-or-description; AND would break it in other locales",
            reels.isScopedLabel
        )
        assertTrue(reels.matches(InstagramSurfaces.ID_CLIPS_TAB, null, null))
        assertTrue(reels.matches(null, null, InstagramSurfaces.DESC_REELS_TAB))
    }

    // -----------------------------------------------------------------------------------------
    // AND semantics: opted into, for the menu row
    // -----------------------------------------------------------------------------------------

    private fun scoped() = NodeLocator(
        viewIds = listOf(id),
        texts = listOf("Following"),
        contentDescriptions = listOf("Following"),
        scopedLabel = true
    )

    @Test
    fun `a scoped locator matches when the id and the text agree`() {
        assertTrue(scoped().matchesScoped(id, "Following", null))
    }

    @Test
    fun `a scoped locator matches when the id and the content description agree`() {
        assertTrue(scoped().matchesScoped(id, null, "Following"))
    }

    /** The coin-flip case: the right id, the wrong row. This is the Favorites row. */
    @Test
    fun `a scoped locator rejects the right id with the wrong label`() {
        assertFalse(
            "every dropdown row carries this id — the label is the only thing that names the row",
            scoped().matchesScoped(id, "Favorites", null)
        )
    }

    /** The caption case: the right label, on a node that is not a menu row at all. */
    @Test
    fun `a scoped locator rejects the right label with the wrong id`() {
        assertFalse(
            "a feed caption containing the word must never be taken for the menu row",
            scoped().matchesScoped("com.instagram.android:id/row_feed_photo_profile_name", "Following", null)
        )
    }

    @Test
    fun `a scoped locator rejects a node with the id and no label at all`() {
        assertFalse(scoped().matchesScoped(id, null, null))
    }

    @Test
    fun `a scoped locator rejects a node with neither`() {
        assertFalse(scoped().matchesScoped(null, null, null))
    }

    @Test
    fun `a declared scoped locator reports itself as scoped`() {
        assertTrue(scoped().isScopedLabel)
    }

    /**
     * A locator that declares AND but supplies only one side would match nothing at all, silently.
     * `isScopedLabel` is false in that case so the caller falls back to OR rather than to nothing.
     */
    @Test
    fun `declaring scoped without both sides is not treated as scoped`() {
        assertFalse(
            "an id with no label cannot AND against anything",
            NodeLocator(viewIds = listOf(id), scopedLabel = true).isScopedLabel
        )
        assertFalse(
            "a label with no id cannot be scoped by anything",
            NodeLocator(texts = listOf("Following"), scopedLabel = true).isScopedLabel
        )
    }

    /** The shipped menu-row locator, read from production. */
    @Test
    fun `the shipped menu label locator is the one scoped locator in the app`() {
        val label = InstagramSurfaces.followingSteer!!.menuItemLabel
        assertTrue("the dropdown row needs id AND label", label.isScopedLabel)

        val others = listOf(
            InstagramSurfaces.followingSteer!!.entryPoint,
            InstagramSurfaces.followingSteer!!.menuItem,
            InstagramSurfaces.titleLocator!!
        ) + InstagramSurfaces.vanishableTabs.values
        others.forEach { locator ->
            assertFalse("only menuItemLabel may be scoped, not $locator", locator.isScopedLabel)
        }
    }

    /**
     * `matches` is untouched by the scoped flag: it is still OR, and it is still what every other
     * locator uses. Pinned because the tempting "simplification" is to make `matches` respect the
     * flag, which would make the two rules one and reintroduce both bugs at once.
     */
    @Test
    fun `matches stays OR even on a scoped locator`() {
        assertTrue(
            "matches() is the OR rule; callers branch on isScopedLabel instead of matches changing",
            scoped().matches(id, "Favorites", null)
        )
    }
}
