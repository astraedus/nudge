package com.astraedus.nudge.ui.screens.config

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Also covered by" on App config (spec 3.7): listed only when a group actually blocks the app. */
class CoveringGroupsTest {

    private val app = "com.instagram.android"
    private val social = AppGroup(id = 1, name = "Social")
    private val evening = AppGroup(id = 2, name = "Evening")
    private val work = AppGroup(id = 3, name = "Work")

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

        val covering = coveringGroups(app, listOf(social, evening, work), members, rules)

        assertEquals(
            listOf(CoveringGroup(2, "Evening · Hard Block"), CoveringGroup(1, "Social · Delay 15s")),
            covering
        )
    }

    @Test
    fun `a group whose rule is off, or that has none, is not listed`() {
        val rules = listOf(BlockRule(groupId = 1, mode = "DELAY", enabled = false))

        assertTrue(coveringGroups(app, listOf(social, evening), members, rules).isEmpty())
    }

    @Test
    fun `the app's own rule and a group's feature rule do not count`() {
        val rules = listOf(
            BlockRule(packageName = app, mode = "HARD_BLOCK"),
            BlockRule(groupId = 1, mode = "HARD_BLOCK", inAppFeatures = "REELS")
        )

        assertTrue(coveringGroups(app, listOf(social), members, rules).isEmpty())
    }
}
