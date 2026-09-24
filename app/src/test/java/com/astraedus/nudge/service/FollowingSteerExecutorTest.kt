package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.domain.surfaces.InstagramSurfaces
import com.astraedus.nudge.domain.surfaces.NodeLocator
import com.astraedus.nudge.domain.surfaces.SteerAction
import com.astraedus.nudge.util.NudgeLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The steer executor: one tree read into a pure observation, and one already-decided click.
 *
 * Layer **L1** (`docs/testing-strategy.md`). This is the first code in Nudge that acts INSIDE another
 * app, so the cases that matter are the ones where a click would land on the wrong node or on a node
 * that cannot be clicked at all — both silent in production, both caught here.
 *
 * Every id, label and locator is read from [InstagramSurfaces], never typed out: fixture honesty
 * (`docs/testing-strategy.md` rule (b)) and the adapter is the one file allowed to hold host-app ids.
 */
class FollowingSteerExecutorTest {

    private val executor = FollowingSteerExecutor(mockk<NudgeLogger>(relaxed = true))
    private val recipe = InstagramSurfaces.followingSteer

    private fun node(
        viewId: String? = null,
        text: String? = null,
        desc: String? = null,
        clickable: Boolean = false,
        children: List<AccessibilityNodeInfo> = emptyList(),
        byId: Map<String, List<AccessibilityNodeInfo>> = emptyMap(),
        byText: Map<String, List<AccessibilityNodeInfo>> = emptyMap(),
        clickSucceeds: Boolean = true
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
        every { n.performAction(any()) } returns clickSucceeds
        return n
    }

    // ------------------------------------------------------------------------------------------
    // execute
    // ------------------------------------------------------------------------------------------

    /**
     * The measured Home feed: `title_logo` is inert and the ViewAnimator above it is what responds.
     * Clicking the logo returns false and nothing happens, which is a steer that fails for a reason no
     * log line explains.
     */
    @Test
    fun `OpenMenu clicks the clickable ancestor of the entry point`() {
        val entry = node(viewId = InstagramSurfaces.ID_ACTION_BAR_TITLE_VIEW, clickable = true)
        val root = node(byId = mapOf(InstagramSurfaces.ID_ACTION_BAR_TITLE_VIEW to listOf(entry)))

        assertTrue(executor.execute(root, recipe, SteerAction.OpenMenu))
        verify(exactly = 1) { entry.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `OpenMenu walks up from an inert logo to the node that responds`() {
        val logo = node(viewId = InstagramSurfaces.ID_TITLE_LOGO, desc = InstagramSurfaces.DESC_HOME_FEED)
        val animator = node(viewId = InstagramSurfaces.ID_ACTION_BAR_TITLE_VIEW, clickable = true)
        every { logo.parent } returns animator
        // The entry-point locator resolves to the logo's id in a tree that only indexes the logo.
        val root = node(byId = mapOf(InstagramSurfaces.ID_ACTION_BAR_TITLE_VIEW to listOf(logo)))

        assertTrue(executor.execute(root, recipe, SteerAction.OpenMenu))
        verify(exactly = 1) { animator.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { logo.performAction(any()) }
    }

    /**
     * The measured dropdown: every row is a `context_menu_item` Button and only the
     * `context_menu_item_label` TextView inside it says which row it is. So the LABEL is what is
     * located and the BUTTON is what is clicked.
     */
    @Test
    fun `ClickFollowing clicks the row that owns the Following label`() {
        val label = node(
            viewId = InstagramSurfaces.ID_CONTEXT_MENU_ITEM_LABEL,
            text = InstagramSurfaces.LABEL_FOLLOWING
        )
        val row = node(viewId = InstagramSurfaces.ID_CONTEXT_MENU_ITEM, clickable = true)
        every { label.parent } returns row
        val root = node(byId = mapOf(InstagramSurfaces.ID_CONTEXT_MENU_ITEM_LABEL to listOf(label)))

        assertTrue(executor.execute(root, recipe, SteerAction.ClickFollowing))
        verify(exactly = 1) { row.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { label.performAction(any()) }
    }

    @Test
    fun `a missing node returns false and clicks nothing`() {
        val root = node()

        assertFalse(executor.execute(root, recipe, SteerAction.OpenMenu))
        assertFalse(executor.execute(root, recipe, SteerAction.ClickFollowing))
    }

    /**
     * A match whose chain has nothing clickable in it is reported as a failure, not clicked anyway.
     * Dispatching at a node that reports `clickable=false` is the silent no-op this feature's whole
     * find path exists to avoid.
     */
    @Test
    fun `an inert node with no clickable ancestor is not clicked`() {
        val inert = node(viewId = InstagramSurfaces.ID_ACTION_BAR_TITLE_VIEW)
        val root = node(byId = mapOf(InstagramSurfaces.ID_ACTION_BAR_TITLE_VIEW to listOf(inert)))

        assertFalse(executor.execute(root, recipe, SteerAction.OpenMenu))
        verify(exactly = 0) { inert.performAction(any()) }
    }

    /**
     * `performAction` returning false is the ordinary way a click at a host app fails — the node went
     * stale between the find and the click. Reporting that as success would let the caller believe the
     * steer landed and never look again.
     */
    @Test
    fun `a refused click is reported as failure`() {
        val entry = node(
            viewId = InstagramSurfaces.ID_ACTION_BAR_TITLE_VIEW,
            clickable = true,
            clickSucceeds = false
        )
        val root = node(byId = mapOf(InstagramSurfaces.ID_ACTION_BAR_TITLE_VIEW to listOf(entry)))

        assertFalse(executor.execute(root, recipe, SteerAction.OpenMenu))
    }

    @Test
    fun `None does no work at all`() {
        val entry = node(viewId = InstagramSurfaces.ID_ACTION_BAR_TITLE_VIEW, clickable = true)
        val root = node(byId = mapOf(InstagramSurfaces.ID_ACTION_BAR_TITLE_VIEW to listOf(entry)))

        assertFalse(executor.execute(root, recipe, SteerAction.None))
        verify(exactly = 0) { entry.performAction(any()) }
        verify(exactly = 0) { root.findAccessibilityNodeInfosByViewId(any()) }
    }

    /** A tree that goes away mid-click must not take the accessibility service down with it. */
    @Test
    fun `a throwing tree returns false instead of propagating`() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.findAccessibilityNodeInfosByViewId(any()) } throws IllegalStateException("stale")
        every { root.findAccessibilityNodeInfosByText(any()) } throws IllegalStateException("stale")
        every { root.childCount } throws IllegalStateException("stale")

        assertFalse(executor.execute(root, recipe, SteerAction.OpenMenu))
        assertFalse(executor.isMenuVisible(root, recipe))
    }

    // ------------------------------------------------------------------------------------------
    // isMenuVisible
    // ------------------------------------------------------------------------------------------

    @Test
    fun `the menu reads as visible exactly when its rows are present`() {
        val withRows = node(
            byId = mapOf(InstagramSurfaces.ID_CONTEXT_MENU_ITEM to listOf(node(clickable = true)))
        )

        assertTrue(executor.isMenuVisible(withRows, recipe))
        assertFalse(executor.isMenuVisible(node(), recipe))
    }

    // ------------------------------------------------------------------------------------------
    // observe. Facts only; the classification is the adapter's.
    // ------------------------------------------------------------------------------------------

    /**
     * The measured Home feed, replayed through the real adapter: the ids the observation reports must
     * be enough for [InstagramSurfaces.classify] to reach HOME_FEED, and the title must be read from
     * the adapter's own [InstagramSurfaces.titleLocator].
     */
    @Test
    fun `observe reports the host ids that are present and the action bar title`() {
        val tree = node(
            viewId = InstagramSurfaces.ID_TAB_BAR,
            children = listOf(
                node(viewId = InstagramSurfaces.ID_CLIPS_TAB),
                node(viewId = InstagramSurfaces.ID_STICKY_HEADER_LIST),
                node(viewId = "android:id/navigationBarBackground")
            ),
            byId = mapOf(InstagramSurfaces.ID_CLIPS_TAB to listOf(node()))
        )

        val observation = executor.observe(tree, InstagramSurfaces)

        assertTrue(InstagramSurfaces.ID_TAB_BAR in observation.presentViewIds)
        assertTrue(InstagramSurfaces.ID_CLIPS_TAB in observation.presentViewIds)
        assertTrue(InstagramSurfaces.ID_STICKY_HEADER_LIST in observation.presentViewIds)
        assertFalse(
            "ids outside the host package must never reach a host-app classifier",
            observation.presentViewIds.any { !it.startsWith(InstagramSurfaces.packageName) }
        )
        assertNull("no title node in this tree", observation.actionBarTitle)
    }

    @Test
    fun `observe reads the title through the adapter's own locator`() {
        val header = node(
            viewId = InstagramSurfaces.ID_ACTION_BAR_TITLE,
            text = InstagramSurfaces.LABEL_FOLLOWING
        )
        val tree = node(
            children = listOf(header),
            byId = mapOf(InstagramSurfaces.ID_ACTION_BAR_TITLE to listOf(header))
        )

        val observation = executor.observe(tree, InstagramSurfaces)

        assertEquals(InstagramSurfaces.LABEL_FOLLOWING, observation.actionBarTitle)
    }

    /**
     * The observation is the classifier's ONLY input, so the two have to agree. Driving the real
     * adapter's `classify` off a real `observe` is what catches an observation that quietly stops
     * collecting an id the classifier still keys on.
     */
    @Test
    fun `an observation of a home-feed tree classifies as the home feed`() {
        val logo = node(viewId = InstagramSurfaces.ID_TITLE_LOGO, desc = InstagramSurfaces.DESC_HOME_FEED)
        val tree = node(
            viewId = InstagramSurfaces.ID_TAB_BAR,
            children = listOf(logo, node(viewId = InstagramSurfaces.ID_STICKY_HEADER_LIST))
        )

        assertEquals(
            com.astraedus.nudge.domain.surfaces.HostSurface.HOME_FEED,
            InstagramSurfaces.classify(executor.observe(tree, InstagramSurfaces))
        )
    }

    @Test
    fun `an explicit title locator overrides the adapter's`() {
        val custom = node(viewId = "com.example:id/header", text = "Elsewhere")
        val tree = node(byId = mapOf("com.example:id/header" to listOf(custom)))

        val observation = executor.observe(
            tree,
            InstagramSurfaces,
            titleLocator = NodeLocator(viewIds = listOf("com.example:id/header"))
        )

        assertEquals("Elsewhere", observation.actionBarTitle)
    }

    @Test
    fun `a throwing tree yields an empty observation rather than propagating`() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.findAccessibilityNodeInfosByViewId(any()) } throws IllegalStateException("stale")
        every { root.childCount } throws IllegalStateException("stale")

        val observation = executor.observe(root, InstagramSurfaces)

        assertEquals(emptySet<String>(), observation.presentViewIds)
        assertNull(observation.actionBarTitle)
    }
}
