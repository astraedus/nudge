package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.domain.surfaces.NodeLocator
import com.astraedus.nudge.domain.surfaces.PlatformSurfaces
import com.astraedus.nudge.domain.surfaces.SteerAction
import com.astraedus.nudge.domain.surfaces.SteerRecipe
import com.astraedus.nudge.domain.surfaces.SurfaceObservation
import com.astraedus.nudge.util.NudgeLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads one host-app tree into a pure [SurfaceObservation], and performs one already-decided
 * [SteerAction] inside that app.
 *
 * ## This is the first time Nudge acts INSIDE another app
 *
 * Every other enforcement Nudge does is additive and external: it draws its own window in front of
 * an app, or it sends the user home. The following steer dispatches `ACTION_CLICK` at somebody
 * else's buttons. That is a categorically louder thing to get wrong — a misfire opens a menu under
 * the user's thumb, or taps a row that is not the one we meant — which is why the feature is
 * per-rule, default OFF, one attempt per arrival, and never a retry loop.
 *
 * It is also why **every attempt logs exactly one line with its action and its outcome**. A field
 * report about this feature ("Instagram keeps opening a menu at me") has no other mechanism to carry
 * it: nothing is written to the database, no overlay is drawn, and the evidence is one click in
 * another process's UI. One line per attempt is the whole audit trail, and it has to be there before
 * the first report arrives, not after.
 *
 * ## It decides nothing
 *
 * Whether to steer, and which step comes next, belong to the pure state machine in
 * `domain/surfaces/`. [observe] gathers facts and does not classify; [execute] performs an action it
 * is handed. This class holds no state at all — no "have we steered", no timestamps — so there is
 * nothing here that a JVM test of the policy cannot reach.
 *
 * ## Never throws
 *
 * A tree read or a click on a host app whose window has gone away must not take the accessibility
 * service down, because that would stop blocking completely. Every path catches and returns
 * false / an empty observation, the same rule [HostNodeFinder] and `InAppDetector` run on.
 */
@Singleton
class FollowingSteerExecutor @Inject constructor(
    private val logger: NudgeLogger
) {

    /**
     * One tree read, reduced to the facts [PlatformSurfaces.classify] needs.
     *
     * Collects the union of every id the adapter names — the vanishable tabs' locator ids plus the
     * steer recipe's ids — and the action-bar title text. That union is deliberately derived from
     * the adapter rather than hand-listed here: a host-app update that renames an id is then one
     * edit in the adapter, and this method cannot fall out of step with the classifier that consumes
     * its output.
     *
     * It does **not** classify. Which screen the observation means is the adapter's judgement, and
     * keeping the two apart is what lets a committed hierarchy fixture feed the identical classifier
     * production runs.
     *
     * Carries no node text beyond the one chrome title, by construction: [SurfaceObservation] has
     * nowhere to put any. Nudge's fixtures are committed to a PUBLIC repo and an observation that
     * carried arbitrary node text would make each one a transcript of somebody's feed.
     *
     * @param titleLocator where this host app keeps its action-bar title. Defaults to the adapter's
     *   own [PlatformSurfaces.titleLocator]. Null means the observation carries no title, and a
     *   classifier whose only positive identification of a screen is its title then reports UNKNOWN,
     *   which changes nothing. It stays a parameter rather than being read inline so a caller can
     *   observe a surface the adapter declares no title for, without the adapter inventing one.
     */
    fun observe(
        root: AccessibilityNodeInfo,
        surfaces: PlatformSurfaces,
        titleLocator: NodeLocator? = surfaces.titleLocator
    ): SurfaceObservation = try {
        // Adapter-named ids first, by indexed framework lookup: unbounded in depth and cheap, so a
        // tab or steer node deep in a long feed is never missed.
        val wantedIds = LinkedHashSet<String>()
        surfaces.vanishableTabs.values.forEach { wantedIds += it.viewIds }
        surfaces.followingSteer?.let { recipe ->
            wantedIds += recipe.entryPoint.viewIds
            wantedIds += recipe.menuItem.viewIds
            wantedIds += recipe.menuItemLabel.viewIds
        }

        // Then the bounded harvest, which is what makes the set COMPLETE for a classifier keying on
        // markers the adapter never had to publish (Instagram's sticky story-tray header, say).
        // Union, not either/or: the harvest is depth-capped and the targeted lookups are not.
        val present = HostNodeFinder.presentViewIds(root, wantedIds) +
            HostNodeFinder.harvestViewIds(root, prefix = "${surfaces.packageName}:id/")

        SurfaceObservation(
            presentViewIds = present,
            actionBarTitle = titleLocator?.let { HostNodeFinder.textOf(root, it) }
        )
    } catch (e: Exception) {
        logger.w("surface observe failed package=${surfaces.packageName}", e)
        SurfaceObservation(presentViewIds = emptySet(), actionBarTitle = null)
    }

    /**
     * True when the host app's steer menu is currently open.
     *
     * Keyed on the ROW locator ([SteerRecipe.menuItem]), not the label: the rows share one id, so
     * their presence is exactly the question "is the dropdown up" and nothing finer. The caller uses
     * it to bound its wait for the menu rather than to pick a row.
     */
    fun isMenuVisible(root: AccessibilityNodeInfo, recipe: SteerRecipe): Boolean = try {
        HostNodeFinder.exists(root, recipe.menuItem)
    } catch (e: Exception) {
        logger.w("steer menu probe failed", e)
        false
    }

    /**
     * Perform [action]. Returns true only if a clickable node was found **and**
     * `ACTION_CLICK` itself reported success.
     *
     * Both steps matter. `performAction` returning false is the ordinary way a click at a host app
     * fails — the node went stale between the find and the click — and reporting that as success
     * would let the caller believe the steer landed and never look again.
     *
     * Every target goes through [HostNodeFinder.findClickable], which walks UP to the nearest
     * clickable ancestor: Instagram's measured entry point is an inert `title_logo` under a clickable
     * ViewAnimator, and a dropdown row's label is a TextView under the clickable Button. Clicking
     * either child is a silent no-op.
     */
    fun execute(root: AccessibilityNodeInfo, recipe: SteerRecipe, action: SteerAction): Boolean {
        val target: NodeLocator = when (action) {
            // No work, no log line: "nothing to do" arrives constantly and is not an attempt.
            is SteerAction.None -> return false
            is SteerAction.OpenMenu -> recipe.entryPoint
            is SteerAction.ClickFollowing -> recipe.menuItemLabel
            // Dismissing the dropdown is a GLOBAL action (back), not a click on a node, so it
            // belongs to the service and not to a class whose whole job is "find a node and click
            // it". Returning false here rather than silently doing nothing would be a lie to the
            // caller; the service never routes CloseMenu through this method, and this branch exists
            // because the `when` is exhaustive on purpose -- adding an action forces a decision here
            // instead of letting a new one fall into an `else` and quietly do nothing.
            is SteerAction.CloseMenu -> return false
        }

        val name = action::class.simpleName
        return try {
            val node = HostNodeFinder.findClickable(root, target)
            if (node == null) {
                logger.d("steer attempt action=$name outcome=no_clickable_node")
                return false
            }
            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            logger.d("steer attempt action=$name outcome=${if (clicked) "clicked" else "click_refused"}")
            clicked
        } catch (e: Exception) {
            logger.d("steer attempt action=$name outcome=threw ${e.javaClass.simpleName}")
            false
        }
    }
}
