package com.astraedus.nudge.domain.surfaces

/**
 * Instagram's surfaces, measured on **Instagram 447.0.0.55.81, Pixel 3, 1080x2160, 2026-09-22**.
 * The hierarchy dumps that back every constant below are committed, scrubbed, at
 * `app/src/test/resources/surface-fixtures/` and are replayed by the tests in this package.
 *
 * ## This file is the seam
 *
 * **Every `com.instagram.android:id/…` string in the codebase belongs here.** That is the point of
 * the adapter: when Instagram ships a release that renames a view, exactly one file changes, and
 * `InstagramIdsLiveInOneFileTest` fails the build if a second file starts carrying those ids.
 * (`service/InAppDetector.kt` is a grandfathered exception — it predates this seam and still holds
 * the reel-player container ids. It is the ONE legacy holder the test allows.)
 *
 * ## The measured facts, and what each one forced
 *
 * - `tab_bar` sits at `[0,1896][1080,2028]` and stays put while the feed scrolls. It is **absent**
 *   on the Following screen, in the reel player, and in stories and DM threads — which is exactly
 *   why its presence is a usable "a bottom-nav screen is showing" signal, and why the cover has to
 *   be torn down rather than left to float when it goes away.
 * - `clips_tab` sits at `[216,1896][432,2028]` with content-description "Reels".
 * - **Every tab reports `selected=false` in the dump, the active one included.** Nothing in this
 *   file, or anywhere downstream, may key on tab selection. The cover keys on `clips_tab` BOUNDS
 *   and nothing else.
 * - `title_logo` (ImageView, content-description "Instagram Home Feed") marks the Home feed, but it
 *   is NOT clickable. The clickable parent is `action_bar_title_view`, a ViewAnimator at
 *   `[132,97][948,210]` — so the steer's entry point is the parent, not the logo.
 * - The dropdown's rows are all `context_menu_item` (Button) with a `context_menu_item_label`
 *   (TextView) child reading "Following" or "Favorites". The row id cannot pick a row; the label can.
 * - Following is a SEPARATE full-screen activity: `action_bar_title` reads "Following", and
 *   `tab_bar`/`clips_tab`/`feed_tab` are gone from the tree entirely.
 * - **Deep links are dead.** `https://www.instagram.com/?variant=following` is claimed by the app
 *   but the parameter is ignored, and `instagram://feed?variant=following` does not resolve; both
 *   land on Home. The `variant-link` and `scheme-link` fixtures are the recorded proof. A click
 *   sequence is the only route, and no deep-link path exists in this adapter by design.
 */
object InstagramSurfaces : PlatformSurfaces {

    override val packageName: String = "com.instagram.android"

    // ---------------------------------------------------------------------------------------
    // View ids. THE one place they live.
    // ---------------------------------------------------------------------------------------

    /** The bottom-nav container. Present on every tab screen, absent everywhere else. */
    const val ID_TAB_BAR = "com.instagram.android:id/tab_bar"

    /** The Reels tab. The tab-vanish cover is drawn over THIS node's bounds. */
    const val ID_CLIPS_TAB = "com.instagram.android:id/clips_tab"

    /** The Home-feed logo. Present only on Home, and only while the action bar is not scrolled away. */
    const val ID_TITLE_LOGO = "com.instagram.android:id/title_logo"

    /** The clickable ancestor of [ID_TITLE_LOGO]. The steer's entry point — the logo itself is inert. */
    const val ID_ACTION_BAR_TITLE_VIEW = "com.instagram.android:id/action_bar_title_view"

    /** The Following activity's header. Its TEXT is what identifies that screen. */
    const val ID_ACTION_BAR_TITLE = "com.instagram.android:id/action_bar_title"

    /** One row of the title dropdown. Shared by every row, so it cannot identify a row alone. */
    const val ID_CONTEXT_MENU_ITEM = "com.instagram.android:id/context_menu_item"

    /** The label inside a dropdown row. This is what separates "Following" from "Favorites". */
    const val ID_CONTEXT_MENU_ITEM_LABEL = "com.instagram.android:id/context_menu_item_label"

    /**
     * The Home feed's sticky story-tray header. The one Home marker that SURVIVES scrolling.
     *
     * Present in every Home-feed dump (`home`, `home-scrolled`, `coldstart`, `variant-link`,
     * `scheme-link`) and absent from both Following dumps. It exists because `title_logo` does not
     * survive a scroll — Instagram's action bar scrolls away — and without a second marker a user
     * scrolling the feed would read as OTHER_TAB, reset the steer's memory, and get re-steered the
     * moment they scrolled back up. See [classify].
     */
    const val ID_STICKY_HEADER_LIST = "com.instagram.android:id/sticky_header_list"

    // ---------------------------------------------------------------------------------------
    // Text labels. Version- and locale-fragile by nature; see NodeLocator.
    // ---------------------------------------------------------------------------------------

    /** The Following screen's header text, and the dropdown row that reaches it. */
    const val LABEL_FOLLOWING = "Following"

    /** `title_logo`'s content description. */
    const val DESC_HOME_FEED = "Instagram Home Feed"

    /** `clips_tab`'s content description. Fallback for [ID_CLIPS_TAB]. */
    const val DESC_REELS_TAB = "Reels"

    // ---------------------------------------------------------------------------------------
    // Colours. THE one place to adjust them.
    // ---------------------------------------------------------------------------------------

    /**
     * ARGB `0xFFFFFFFF`. **Measured** off the nav bar in light mode on the spike device.
     *
     * An accessibility service cannot sample a host app's pixels, so this is a constant rather than
     * a reading. If a future Instagram release tints the nav bar, this is the one line to change.
     */
    const val NAV_BAR_COLOR_LIGHT: Int = -0x1

    /**
     * ARGB `0xFF000000`. **Assumed, not measured** — the spike only captured light mode. Same
     * caveat and same one line to change as [NAV_BAR_COLOR_LIGHT].
     */
    const val NAV_BAR_COLOR_DARK: Int = -0x1000000

    // ---------------------------------------------------------------------------------------
    // The adapter surface.
    // ---------------------------------------------------------------------------------------

    /**
     * Reels only. Instagram's Explore tab is also a feature Nudge can block, but Explore is
     * reachable by a swipe from the Home feed as well as by its tab, so covering the tab would
     * imply an enforcement it does not deliver. Adding a key here is all a future Explore cover
     * would need from this file.
     */
    override val vanishableTabs: Map<String, NodeLocator> = mapOf(
        "REELS" to NodeLocator(
            viewIds = listOf(ID_CLIPS_TAB),
            contentDescriptions = listOf(DESC_REELS_TAB)
        )
    )

    /**
     * The Following activity's header. Its TEXT is the first rung of [classify], and the only positive
     * identification of that screen — `tab_bar` and `clips_tab` are simply absent there, and absence
     * cannot tell the Following feed from a reel player.
     *
     * Matched by id only, deliberately. Matching on the text "Following" as well would make the
     * locator find the dropdown ROW of the same name on the Home feed, and the classifier would then
     * read an open menu as the destination screen.
     */
    override val titleLocator: NodeLocator = NodeLocator(viewIds = listOf(ID_ACTION_BAR_TITLE))

    override val followingSteer: SteerRecipe = SteerRecipe(
        // The ViewAnimator, NOT title_logo: the logo reports clickable=false, so a tap dispatched
        // at it does nothing at all.
        entryPoint = NodeLocator(viewIds = listOf(ID_ACTION_BAR_TITLE_VIEW)),
        // Every row shares this id, so this locator finds rows, never THE row.
        menuItem = NodeLocator(viewIds = listOf(ID_CONTEXT_MENU_ITEM)),
        // The row is chosen by its label. `text` carries it on the TextView child; `contentDesc`
        // carries it on the Button itself — both are in the dumps, so both are matched.
        menuItemLabel = NodeLocator(
            viewIds = listOf(ID_CONTEXT_MENU_ITEM_LABEL),
            texts = listOf(LABEL_FOLLOWING),
            contentDescriptions = listOf(LABEL_FOLLOWING),
            // AND, not OR, and this is the one locator in the app that needs it. Every row in the
            // dropdown carries `context_menu_item_label` -- "Favorites" as much as "Following" -- so
            // the id alone picks whichever is first in the tree, and the label alone runs a substring
            // search that a feed caption containing "following" satisfies. Only both together name
            // the row. See NodeLocator.isScopedLabel.
            scopedLabel = true
        )
    )

    override fun navBarColor(nightMode: Boolean): Int =
        if (nightMode) NAV_BAR_COLOR_DARK else NAV_BAR_COLOR_LIGHT

    /**
     * Classify one tree read into a [HostSurface].
     *
     * The ladder, and the evidence for each rung:
     *
     * 1. **`action_bar_title` reads "Following" -> [HostSurface.FOLLOWING_FEED].** Following is its
     *    own full-screen activity whose header carries that exact string in both `text` and
     *    `contentDescription` (`following-feed`, `relaunch` fixtures). Checked FIRST because it is
     *    the only positive identification we have, and because a warm relaunch resumes here.
     *
     * 2. **`title_logo` present -> [HostSurface.HOME_FEED].** The logo is unique to the Home feed
     *    (`home`, `coldstart`, `variant-link`, `scheme-link`).
     *
     * 3. **`tab_bar` AND `sticky_header_list` present -> [HostSurface.HOME_FEED].** This rung is
     *    the scrolled Home feed, and it is here because the measured dumps forced it:
     *    `home-scrolled` has **no `title_logo`** — Instagram's action bar scrolls away with the
     *    content. Without this rung a scrolled feed falls to rung 4, reads as OTHER_TAB, resets the
     *    steer's `attempted` memory, and the user gets the menu opened under them again every time
     *    they scroll back to the top. `sticky_header_list` is present in all five Home dumps and in
     *    neither Following dump, which is as much as the spike can prove; no dump of Search,
     *    Messages or Profile was taken. The ordering is chosen so the unproven case fails SAFE: if
     *    some other tab also carries `sticky_header_list` it reads as Home, and the worst outcome is
     *    one steer attempt whose entry point does not exist, which times out and gives up silently.
     *    The reverse mistake — a scrolled Home read as another tab — is a visible re-steer loop.
     *
     * 4. **`tab_bar` present, nothing above matched -> [HostSurface.OTHER_TAB].** A bottom-nav
     *    screen that is positively not the Home feed.
     *
     * 5. **Otherwise -> [HostSurface.UNKNOWN].** The reel player, a story, a DM thread, settings.
     *    `tab_bar` is absent from all of them. We know nothing, so we change nothing.
     *
     * Note what is NOT here: tab selection. Every tab reports `selected=false` in the dump,
     * including the active one, so selection cannot answer any question this method is asked.
     */
    override fun classify(observation: SurfaceObservation): HostSurface {
        if (observation.actionBarTitle == LABEL_FOLLOWING) return HostSurface.FOLLOWING_FEED

        val ids = observation.presentViewIds
        if (ID_TITLE_LOGO in ids) return HostSurface.HOME_FEED
        if (ID_TAB_BAR in ids) {
            return if (ID_STICKY_HEADER_LIST in ids) HostSurface.HOME_FEED else HostSurface.OTHER_TAB
        }
        return HostSurface.UNKNOWN
    }
}
