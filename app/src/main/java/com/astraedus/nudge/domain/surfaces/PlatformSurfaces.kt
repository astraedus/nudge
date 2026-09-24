package com.astraedus.nudge.domain.surfaces

/**
 * Everything Nudge knows about ONE host app's on-screen surfaces, as pure data.
 *
 * Two features need it: **Tab Vanish** (draw a cover over the bottom-nav tab that opens a
 * hard-blocked feature, so the icon disappears and the tap is eaten) and the **Following steer**
 * (on arrival at a home feed, click through to the chronological following feed instead).
 *
 * Both are node-tree features, and node trees are the thing a host app changes without warning.
 * Putting every selector behind this interface means a host-app update is a data edit in one file
 * ([InstagramSurfaces]) rather than an archaeology session across `service/`, and it means the
 * same selectors that run in production can be driven against a recorded hierarchy dump by an
 * ordinary JVM test. Nothing in this package imports `android.*`.
 */
interface PlatformSurfaces {

    /** The host app this adapter describes, e.g. `com.instagram.android`. */
    val packageName: String

    /**
     * Feature key (matches `InAppDetector.Feature.key`, e.g. `"REELS"`) -> the bottom-nav tab that
     * opens it.
     *
     * Keyed by the feature string rather than by the enum because the enum lives in `service/` and
     * this package may not depend on it — `domain` does not import `service`. The integrator wires
     * `Feature.key` in at the call site.
     */
    val vanishableTabs: Map<String, NodeLocator>

    /** Present when this app has a "following"-style feed we can steer to; null when it does not. */
    val followingSteer: SteerRecipe?

    /**
     * The ARGB colour the tab cover is painted with, so the vanished tab reads as empty nav bar
     * rather than as a black rectangle.
     *
     * Takes the system night-mode flag because host apps follow the system theme and there is no
     * way to sample a host app's actual pixel colour from an accessibility service.
     */
    fun navBarColor(nightMode: Boolean): Int

    /** Which of this app's screens the given node-presence observation is. */
    fun classify(observation: SurfaceObservation): HostSurface
}

/**
 * The three nodes a following steer clicks through, in order.
 *
 * Instagram's shape, which is why it is three and not two: the Home-feed logo is not itself
 * clickable, the menu items all share one resource id, and only a CHILD of each item carries the
 * label that tells "Following" from "Favorites".
 */
data class SteerRecipe(
    /** The clickable node that opens the menu (Instagram: the title ViewAnimator, not the logo). */
    val entryPoint: NodeLocator,
    /** The clickable menu row. Shared by every row, so it cannot identify WHICH row on its own. */
    val menuItem: NodeLocator,
    /** The label inside the wanted row. This is what picks "Following" out of the menu. */
    val menuItemLabel: NodeLocator
)

/**
 * Which screen of the host app is showing.
 *
 * Deliberately coarse. Nudge only needs to answer three questions — may we steer, should we forget
 * that we already steered, and do we know anything at all — and a coarse closed set keeps every
 * consumer's `when` exhaustive.
 */
enum class HostSurface {
    /** The app's main/home feed: the one place a steer may start. */
    HOME_FEED,

    /** The following-style feed: the steer's destination, reached deliberately or by us. */
    FOLLOWING_FEED,

    /** A different bottom-nav tab (search, messages, profile): positively not the home feed. */
    OTHER_TAB,

    /** We recognise nothing. Reel player, story, DM thread, settings — change NOTHING. */
    UNKNOWN
}

/**
 * ONE tree read, reduced to the facts a classifier needs — and deliberately nothing else.
 *
 * It holds no nodes. The service walks the live tree once, collects the resource ids it saw and the
 * one title string, and hands over this value; a JVM test builds the identical value from a
 * recorded hierarchy dump. Because it is plain data, the classification RULE is the same object
 * under test as in production, which is the only way a fixture can contradict the code instead of
 * agreeing with it.
 *
 * Keeping nodes out is also a privacy constraint. Nudge has no internet permission and its device
 * fixtures are committed to a PUBLIC repository; an observation that carried arbitrary node text
 * would make every future fixture a transcript of someone's feed. Ids are structure, and the one
 * text field is a host-app chrome label. See `app/src/test/resources/surface-fixtures/README.md`.
 *
 * @param presentViewIds every fully-qualified resource id seen in this tree read.
 * @param actionBarTitle the host app's action-bar title, when the tree has one; null otherwise.
 */
data class SurfaceObservation(
    val presentViewIds: Set<String>,
    val actionBarTitle: String? = null
)
