package com.astraedus.nudge.domain.surfaces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The L2 layer for `domain/surfaces/`: production's own locators and classifier, driven against
 * eight real Instagram view hierarchies recorded on a device.
 *
 * `docs/testing-strategy.md` says replayed device data is the layer this repo underfunds (1.9% of the
 * budget, the largest single bug bucket) and that both of these features are node-tree features, so
 * this is the layer they belong at. The point is that a fixture can **disagree** with the code: every
 * assertion below goes through the same `InstagramSurfaces.classify` and the same `NodeLocator`s that
 * the accessibility service uses, over a tree nobody hand-wrote.
 *
 * Provenance, the app version, the device and the scrubbing rule: `surface-fixtures/README.md`.
 */
class InstagramSurfacesFixtureTest {

    private fun classify(fixture: String): HostSurface =
        InstagramSurfaces.classify(SurfaceFixture.observe(fixture))

    // -----------------------------------------------------------------------------------------
    // Home feed
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the home feed classifies as HOME_FEED`() {
        assertEquals(HostSurface.HOME_FEED, classify("home"))
    }

    /**
     * The scrolled Home feed is still the Home feed — and this is the fixture that forced the
     * `sticky_header_list` rung into [InstagramSurfaces.classify].
     *
     * Instagram's action bar scrolls away with the content, so `title_logo` is simply **absent** from
     * this dump. Classified on `title_logo` alone it would read as OTHER_TAB, which resets
     * `FollowingSteer`'s memory — and the user would get the title dropdown opened under them again
     * every single time they scrolled back to the top of the feed.
     */
    @Test
    fun `the scrolled home feed classifies as HOME_FEED even with the action bar gone`() {
        val ids = SurfaceFixture.observe("home-scrolled").presentViewIds
        assertFalse(
            "the premise of this test is that the action bar has scrolled away",
            InstagramSurfaces.ID_TITLE_LOGO in ids
        )
        assertTrue(InstagramSurfaces.ID_TAB_BAR in ids)
        assertEquals(HostSurface.HOME_FEED, classify("home-scrolled"))
    }

    /**
     * The counterfactual for the rung above: with the scrolled-home marker removed from the
     * observation, the same tree falls through to OTHER_TAB.
     *
     * Rule (d) in spirit — without this, a future Instagram release that renames
     * `sticky_header_list` would leave the test above green for the wrong reason (passing on
     * `title_logo`, which it does not have) and nobody would learn the rung had stopped working.
     */
    @Test
    fun `without the scrolled-home marker the same tree reads as another tab`() {
        val observed = SurfaceFixture.observe("home-scrolled")
        val withoutMarker = observed.copy(
            presentViewIds = observed.presentViewIds - InstagramSurfaces.ID_STICKY_HEADER_LIST
        )
        assertEquals(
            "the sticky_header_list rung is load-bearing; if this is HOME_FEED the rung is dead",
            HostSurface.OTHER_TAB,
            InstagramSurfaces.classify(withoutMarker)
        )
    }

    // -----------------------------------------------------------------------------------------
    // The Reels tab and its cover placement
    // -----------------------------------------------------------------------------------------

    /**
     * The measured `clips_tab` bounds, through production's own validator.
     *
     * `[216,1896][432,2028]` becomes `x=216, y=1896, width=216, height=132`. Bounds are parsed to
     * four plain `Int`s and handed to [TabCoverPlacement.of]; no `android.graphics.Rect` is
     * constructed anywhere in test sources, because `unitTests.isReturnDefaultValues` is deliberately
     * not set and every `android.*` call in a JVM test therefore throws.
     */
    @Test
    fun `the reels tab in the home fixture yields the measured cover placement`() {
        val locator = InstagramSurfaces.vanishableTabs.getValue("REELS")
        val tab = SurfaceFixture.find("home", locator)
        assertNotNull("clips_tab must be present on the home feed", tab)
        assertEquals(TabCoverPlacement(x = 216, y = 1896, width = 216, height = 132), tab!!.placement())
    }

    /** The same placement on the scrolled feed: the nav bar does not move when the content does. */
    @Test
    fun `the reels tab does not move when the feed scrolls`() {
        val locator = InstagramSurfaces.vanishableTabs.getValue("REELS")
        assertEquals(
            SurfaceFixture.find("home", locator)?.placement(),
            SurfaceFixture.find("home-scrolled", locator)?.placement()
        )
    }

    /**
     * The measured fact that forbids keying anything on tab selection: **every** tab reports
     * `selected=false`, including the active one. Pinned here so a future contributor who reaches for
     * `isSelected` finds a failing test instead of a plausible-looking heuristic.
     */
    @Test
    fun `no bottom-nav tab reports itself as selected`() {
        val tabIds = setOf(
            InstagramSurfaces.ID_CLIPS_TAB,
            "com.instagram.android:id/feed_tab",
            "com.instagram.android:id/search_tab",
            "com.instagram.android:id/direct_tab",
            "com.instagram.android:id/profile_tab"
        )
        val tabs = SurfaceFixture.load("home").filter { it.viewId in tabIds }
        assertEquals("all five tabs should be present on the home feed", 5, tabs.size)
        tabs.forEach { tab ->
            assertFalse(
                "${tab.viewId} reports selected=true — the dump says every tab is false, and the " +
                    "cover keys on bounds for exactly that reason",
                tab.selected
            )
        }
        assertTrue("every tab must be clickable for the cover to have a tap to eat", tabs.all { it.clickable })
    }

    // -----------------------------------------------------------------------------------------
    // Following feed
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the following feed classifies as FOLLOWING_FEED`() {
        assertEquals(HostSurface.FOLLOWING_FEED, classify("following-feed"))
    }

    /** A warm relaunch of Instagram resumes on Following, so it classifies the same way. */
    @Test
    fun `a warm relaunch resumes on the following feed`() {
        assertEquals(HostSurface.FOLLOWING_FEED, classify("relaunch"))
    }

    /**
     * **There is no `clips_tab` node at all on the Following screen** — no `tab_bar` either. This is
     * why the cover must be torn down rather than left floating: there is no nav bar under it to
     * cover, so a cover that survived here would be an opaque rectangle over the feed's content.
     *
     * Asserted explicitly, on both Following fixtures, because the absence is the load-bearing fact.
     */
    @Test
    fun `the following screen has no reels tab and no nav bar for a cover to sit on`() {
        val locator = InstagramSurfaces.vanishableTabs.getValue("REELS")
        listOf("following-feed", "relaunch").forEach { fixture ->
            assertTrue(
                "$fixture must contain no clips_tab node",
                SurfaceFixture.findAll(fixture, locator).isEmpty()
            )
            assertNull(
                "$fixture must contain no clips_tab node",
                SurfaceFixture.find(fixture, locator)
            )
            assertFalse(
                "$fixture must contain no tab_bar",
                InstagramSurfaces.ID_TAB_BAR in SurfaceFixture.observe(fixture).presentViewIds
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // The title dropdown
    // -----------------------------------------------------------------------------------------

    /**
     * The "Following" row is findable through [SteerRecipe.menuItemLabel].
     *
     * The rows all share the single id `context_menu_item`, so the row id cannot pick a row; only the
     * label can. This is the one place the feature depends on host-app TEXT, which is why the steer
     * fails silently rather than retrying.
     */
    @Test
    fun `the following menu item is findable in the open dropdown`() {
        val recipe = InstagramSurfaces.followingSteer
        assertNotNull("Instagram must declare a steer recipe", recipe)

        val label = SurfaceFixture.find("logo-menu", recipe!!.menuItemLabel)
        assertNotNull("the Following label must be findable in the open dropdown", label)
        assertEquals(
            InstagramSurfaces.LABEL_FOLLOWING,
            label!!.text ?: label.contentDescription
        )

        val rows = SurfaceFixture.findAll("logo-menu", recipe.menuItem)
        assertEquals("the dropdown records two rows, Following and Favorites", 2, rows.size)
        assertTrue("a menu row must be clickable", rows.all { it.clickable })
    }

    /**
     * **The menu-row locator selects "Following" and NOT "Favorites".**
     *
     * The rows all carry the same `context_menu_item_label` id, so under OR semantics this locator
     * matches every row and the finder taps whichever came first in the tree — which for this fixture
     * could be Favorites. `menuItemLabel` therefore declares `scopedLabel = true`, and the fixture
     * reader branches on `isScopedLabel` exactly as `HostNodeFinder` does.
     *
     * The test asserts the Favorites row IS present in the same dump first. Without that, the whole
     * thing would pass on a one-row fixture while proving nothing at all.
     */
    @Test
    fun `the menu label locator selects the following row and never favorites`() {
        val labelLocator = InstagramSurfaces.followingSteer!!.menuItemLabel
        assertTrue("this locator must use id-AND-label semantics", labelLocator.isScopedLabel)

        // The premise: both rows exist in this dump, so choosing between them is a real choice.
        val allLabels = SurfaceFixture.load("logo-menu")
            .filter { it.viewId == InstagramSurfaces.ID_CONTEXT_MENU_ITEM_LABEL }
            .mapNotNull { it.text ?: it.contentDescription }
        assertEquals(
            "the fixture must contain BOTH rows, or this test proves nothing",
            listOf("Favorites", "Following"),
            allLabels.sorted()
        )

        // The assertion: exactly one node is selected, and it is the Following row.
        val selected = SurfaceFixture.findAll("logo-menu", labelLocator)
        assertEquals("exactly one row may be selected", 1, selected.size)
        assertEquals(
            InstagramSurfaces.LABEL_FOLLOWING,
            selected.single().text ?: selected.single().contentDescription
        )
        assertEquals(InstagramSurfaces.ID_CONTEXT_MENU_ITEM_LABEL, selected.single().viewId)
    }

    /**
     * The counterfactual for the rule above: under OR semantics the SAME locator over the SAME dump
     * matches more than one node, so the finder's choice would be arbitrary.
     *
     * This is what makes the scoped flag provably load-bearing rather than decorative — delete
     * `scopedLabel = true` and the test above starts depending on tree order.
     */
    @Test
    fun `under OR semantics the same locator would match more than one row`() {
        val labelLocator = InstagramSurfaces.followingSteer!!.menuItemLabel
        val orMatches = SurfaceFixture.load("logo-menu").filter {
            labelLocator.matches(it.viewId, it.text, it.contentDescription)
        }
        assertTrue(
            "if OR matched only one node the scoped flag would be pointless; it matches " +
                "${orMatches.size}, which is why AND is required",
            orMatches.size > 1
        )
        assertTrue(
            "and one of those OR matches is the Favorites row — the row we must never tap",
            orMatches.any { (it.text ?: it.contentDescription) == "Favorites" }
        )
    }

    /**
     * The entry point is the clickable ancestor, not the logo.
     *
     * `title_logo` reports `clickable=false` in the dump, so a tap dispatched at it does nothing at
     * all; `action_bar_title_view` is the ViewAnimator that actually opens the dropdown. Both halves
     * are asserted because the wrong one is the obvious choice — the logo is what a human looks at.
     */
    @Test
    fun `the steer entry point is clickable and the home logo is not`() {
        val recipe = InstagramSurfaces.followingSteer!!
        val entry = SurfaceFixture.find("home", recipe.entryPoint)
        assertNotNull("action_bar_title_view must be present on the home feed", entry)
        assertTrue("the steer entry point must be clickable", entry!!.clickable)

        val logo = SurfaceFixture.load("home").first { it.viewId == InstagramSurfaces.ID_TITLE_LOGO }
        assertFalse(
            "title_logo reports clickable=false — clicking it is the mistake this recipe avoids",
            logo.clickable
        )
    }

    // -----------------------------------------------------------------------------------------
    // Deep links: the recorded proof they do not work
    // -----------------------------------------------------------------------------------------

    /**
     * **This is the evidence that makes the click-based steer the right choice, not a preference.**
     *
     * `variant-link` is the tree after opening `https://www.instagram.com/?variant=following` — a URL
     * the app claims and then ignores. `scheme-link` is the tree after
     * `instagram://feed?variant=following`, which does not resolve at all. Both land on the ordinary
     * Home feed, and `coldstart` records that a cold start does the same.
     *
     * If any of these ever classified as FOLLOWING_FEED, a deep link would be the cheaper, safer
     * implementation and this feature should be rewritten to use it. Until then there is no
     * deep-link path in [InstagramSurfaces] by design, and these three assertions are why.
     */
    @Test
    fun `neither deep link reaches the following feed and a cold start lands on home`() {
        listOf("variant-link", "scheme-link", "coldstart").forEach { fixture ->
            assertEquals(
                "$fixture landed somewhere other than the home feed — if this is FOLLOWING_FEED, " +
                    "the deep link works and the click sequence should be replaced by it",
                HostSurface.HOME_FEED,
                classify(fixture)
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // Everything at once
    // -----------------------------------------------------------------------------------------

    /**
     * Every committed fixture classifies to its recorded screen. One map, so adding a fixture without
     * deciding what it is fails the build instead of being replayed by nothing.
     */
    @Test
    fun `every committed fixture classifies to its recorded surface`() {
        val expected = mapOf(
            "home" to HostSurface.HOME_FEED,
            "home-scrolled" to HostSurface.HOME_FEED,
            "coldstart" to HostSurface.HOME_FEED,
            "variant-link" to HostSurface.HOME_FEED,
            "scheme-link" to HostSurface.HOME_FEED,
            "following-feed" to HostSurface.FOLLOWING_FEED,
            "relaunch" to HostSurface.FOLLOWING_FEED,
            // The dropdown dump is the popup window alone: no tab_bar, no title_logo, no title. We
            // know nothing about the screen underneath from it, and UNKNOWN is the honest answer —
            // which is also why menu visibility is a separate input to FollowingSteer rather than a
            // HostSurface value.
            "logo-menu" to HostSurface.UNKNOWN
        )
        assertEquals(
            "a fixture was added or removed without deciding what it classifies as",
            expected.keys.sorted(),
            SurfaceFixture.names()
        )
        expected.forEach { (fixture, surface) -> assertEquals(fixture, surface, classify(fixture)) }
    }

    /**
     * Every fixture is the host app this adapter describes; a dump of something else proves nothing.
     *
     * Ids are allowed to be Instagram's or the framework's (`android:id/content`,
     * `android:id/navigationBarBackground` and friends appear in every real dump). Anything from a
     * third package would mean the fixture is not the tree it claims to be.
     */
    @Test
    fun `every fixture is an instagram tree`() {
        SurfaceFixture.names().forEach { name ->
            val ids = SurfaceFixture.observe(name).presentViewIds
            val own = ids.filter { it.startsWith("${InstagramSurfaces.packageName}:id/") }
            assertTrue("fixture '$name' has no ${InstagramSurfaces.packageName} resource ids", own.isNotEmpty())
            val foreign = ids.filterNot {
                it.startsWith("${InstagramSurfaces.packageName}:id/") || it.startsWith("android:id/")
            }
            assertEquals(
                "fixture '$name' carries ids from another app: $foreign",
                emptyList<String>(),
                foreign
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // The title locator
    // -----------------------------------------------------------------------------------------

    /**
     * The title locator finds the Following header, and finds nothing on Home.
     *
     * This is the seam that makes rung 1 of [InstagramSurfaces.classify] reachable at all: the service
     * has to be told WHICH node the title string comes from, and without it every observation carries
     * a null title, the Following feed is never positively recognised, and the steer's once-per-arrival
     * memory is never marked from the destination screen — so a user who backed out of Following to
     * Home would be steered again. Both halves are asserted because the absence on Home is what stops
     * the locator matching the dropdown row of the same name.
     */
    @Test
    fun `the title locator finds the following header and nothing on the home feed`() {
        val locator = InstagramSurfaces.titleLocator
        assertNotNull("Instagram must publish a title locator", locator)

        listOf("following-feed", "relaunch").forEach { fixture ->
            val title = SurfaceFixture.find(fixture, locator!!)
            assertNotNull("$fixture must expose a title node", title)
            assertEquals(
                InstagramSurfaces.LABEL_FOLLOWING,
                title!!.text ?: title.contentDescription
            )
        }
        listOf("home", "home-scrolled", "coldstart", "logo-menu").forEach { fixture ->
            assertNull(
                "$fixture must expose no title node — a match here would misread the screen",
                SurfaceFixture.find(fixture, locator!!)
            )
        }
    }

    /**
     * The whole pipeline, end to end, on the one fixture that needs it: locate the title, read its
     * text, build the observation, classify. This is what the service does, with the same objects.
     */
    @Test
    fun `reading the title through the locator classifies the following feed`() {
        val locator = InstagramSurfaces.titleLocator!!
        val node = SurfaceFixture.find("following-feed", locator)!!
        val observation = SurfaceObservation(
            presentViewIds = SurfaceFixture.load("following-feed").mapNotNull { it.viewId }.toSet(),
            actionBarTitle = node.text ?: node.contentDescription
        )
        assertEquals(HostSurface.FOLLOWING_FEED, InstagramSurfaces.classify(observation))

        // The counterfactual: drop the title and the same tree is UNKNOWN, not FOLLOWING_FEED. This
        // is exactly the behaviour before the title locator existed.
        assertEquals(
            HostSurface.UNKNOWN,
            InstagramSurfaces.classify(observation.copy(actionBarTitle = null))
        )
    }
}
