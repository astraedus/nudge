package com.astraedus.nudge.ui.screens.config

import com.astraedus.nudge.domain.engine.CoverageFacts
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.testing.aBlockRule
import com.astraedus.nudge.testing.aGroupMember
import com.astraedus.nudge.testing.anAppGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Also covered by" on App config (spec 3.7): listed only when a group actually blocks the app. */
class CoveringGroupsTest {

    private val app = "com.instagram.android"
    private val social = anAppGroup("Social", id = 1)
    private val evening = anAppGroup("Evening", id = 2)
    private val work = anAppGroup("Work", id = 3)

    private val facts = CoverageFacts(essential = false, launchable = true, category = AppCategory.UNDEFINED, browser = false)

    private val members = listOf(
        aGroupMember(1, app),
        aGroupMember(2, app),
        aGroupMember(3, "com.slack")
    )

    @Test
    fun `lists every group that contains the app and blocks, by name, with its rule`() {
        val rules = listOf(
            aBlockRule(groupId = 1, mode = "DELAY", delaySeconds = 15),
            aBlockRule(groupId = 2, mode = "HARD_BLOCK"),
            aBlockRule(groupId = 3, mode = "HARD_BLOCK")
        )

        val covering = coveringGroups(app, facts, listOf(social, evening, work), members, rules)

        assertEquals(
            listOf(CoveringGroup(2, "Evening · Hard Block"), CoveringGroup(1, "Social · Delay 15s")),
            covering
        )
    }

    @Test
    fun `a group whose rule is off, or that has none, is not listed`() {
        val rules = listOf(aBlockRule(groupId = 1, mode = "DELAY", enabled = false))

        assertTrue(coveringGroups(app, facts, listOf(social, evening), members, rules).isEmpty())
    }

    @Test
    fun `the app's own rule and a group's feature rule do not count`() {
        val rules = listOf(
            aBlockRule(packageName = app, mode = "HARD_BLOCK"),
            aBlockRule(groupId = 1, mode = "HARD_BLOCK", inAppFeatures = "REELS")
        )

        assertTrue(coveringGroups(app, facts, listOf(social), members, rules).isEmpty())
    }

    // -- category groups (spec 5.2) --

    private val videos = anAppGroup("Videos", id = 4, categories = "VIDEO")
    private val videoRule = aBlockRule(groupId = 4, mode = "HARD_BLOCK")

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
        val essentialVideo = CoverageFacts(essential = true, launchable = true, category = AppCategory.VIDEO, browser = false)
        assertTrue(
            coveringGroups("com.dialer", essentialVideo, listOf(videos), emptyList(), listOf(videoRule)).isEmpty()
        )
    }

    @Test
    fun `an app removed from a category group by the user is not listed`() {
        val youtube = facts.copy(category = AppCategory.VIDEO)
        val removed = listOf(aGroupMember(4, "com.youtube", excluded = true))

        assertTrue(coveringGroups("com.youtube", youtube, listOf(videos), removed, listOf(videoRule)).isEmpty())
    }

    @Test
    fun `an app of unknown category is covered by a category group only when added by hand`() {
        val added = listOf(aGroupMember(4, "com.other"))

        assertTrue(coveringGroups("com.other", facts, listOf(videos), emptyList(), listOf(videoRule)).isEmpty())
        assertEquals(
            listOf(CoveringGroup(4, "Videos · Hard Block")),
            coveringGroups("com.other", facts, listOf(videos), added, listOf(videoRule))
        )
    }
}
