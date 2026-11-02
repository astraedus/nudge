package com.astraedus.nudge.ui.screens.config

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.engine.CoverageFacts
import com.astraedus.nudge.domain.packages.AppCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Also covered by" on App config (spec 3.7): listed only when a group actually blocks the app. */
class CoveringGroupsTest {

    private val app = "com.instagram.android"
    private val social = AppGroup(id = 1, name = "Social")
    private val evening = AppGroup(id = 2, name = "Evening")
    private val work = AppGroup(id = 3, name = "Work")

    private val facts = CoverageFacts(essential = false, launchable = true, category = AppCategory.UNDEFINED)

    private val members = listOf(
        AppGroupMember(1, app),
        AppGroupMember(2, app),
        AppGroupMember(3, "com.slack")
    )

    @Test
    fun `lists every group that contains the app and blocks, by name, with its rule`() {
        val rules = listOf(
            BlockRule(groupId = 1, mode = "DELAY", delaySeconds = 15),
            BlockRule(groupId = 2, mode = "HARD_BLOCK"),
            BlockRule(groupId = 3, mode = "HARD_BLOCK")
        )

        val covering = coveringGroups(app, facts, listOf(social, evening, work), members, rules)

        assertEquals(
            listOf(CoveringGroup(2, "Evening · Hard Block"), CoveringGroup(1, "Social · Delay 15s")),
            covering
        )
    }

    @Test
    fun `a group whose rule is off, or that has none, is not listed`() {
        val rules = listOf(BlockRule(groupId = 1, mode = "DELAY", enabled = false))

        assertTrue(coveringGroups(app, facts, listOf(social, evening), members, rules).isEmpty())
    }

    @Test
    fun `the app's own rule and a group's feature rule do not count`() {
        val rules = listOf(
            BlockRule(packageName = app, mode = "HARD_BLOCK"),
            BlockRule(groupId = 1, mode = "HARD_BLOCK", inAppFeatures = "REELS")
        )

        assertTrue(coveringGroups(app, facts, listOf(social), members, rules).isEmpty())
    }

    // -- category groups (spec 5.2) --

    private val videos = AppGroup(id = 4, name = "Videos", categories = "VIDEO")
    private val videoRule = BlockRule(groupId = 4, mode = "HARD_BLOCK")

    @Test
    fun `an app covered only by its category lists the group, with no member row`() {
        val youtube = facts.copy(category = AppCategory.VIDEO)

        assertEquals(
            listOf(CoveringGroup(4, "Videos · Hard Block")),
            coveringGroups("com.youtube", youtube, listOf(videos), emptyList(), listOf(videoRule))
        )
    }

    @Test
    fun `an app of another category is not listed`() {
        val maps = facts.copy(category = AppCategory.MAPS)
        assertTrue(coveringGroups("com.maps", maps, listOf(videos), emptyList(), listOf(videoRule)).isEmpty())
    }

    @Test
    fun `an essential app with a matching category lists nothing`() {
        val essentialVideo = CoverageFacts(essential = true, launchable = true, category = AppCategory.VIDEO)
        assertTrue(
            coveringGroups("com.dialer", essentialVideo, listOf(videos), emptyList(), listOf(videoRule)).isEmpty()
        )
    }

    @Test
    fun `an app removed from a category group by the user is not listed`() {
        val youtube = facts.copy(category = AppCategory.VIDEO)
        val removed = listOf(AppGroupMember(4, "com.youtube", excluded = true))

        assertTrue(coveringGroups("com.youtube", youtube, listOf(videos), removed, listOf(videoRule)).isEmpty())
    }

    @Test
    fun `an app of unknown category is covered by a category group only when added by hand`() {
        val added = listOf(AppGroupMember(4, "com.other"))

        assertTrue(coveringGroups("com.other", facts, listOf(videos), emptyList(), listOf(videoRule)).isEmpty())
        assertEquals(
            listOf(CoveringGroup(4, "Videos · Hard Block")),
            coveringGroups("com.other", facts, listOf(videos), added, listOf(videoRule))
        )
    }
}
