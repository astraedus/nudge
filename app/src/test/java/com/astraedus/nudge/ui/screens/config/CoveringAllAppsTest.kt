package com.astraedus.nudge.ui.screens.config

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.engine.CoverageFacts
import com.astraedus.nudge.domain.packages.AppCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1 (pure unit): "Also covered by" on App config with All apps rules (spec 3.7). Coverage is the
 * ONE [com.astraedus.nudge.domain.engine.RuleCoverage] answer, so the list can never claim an app is
 * covered when the engine would leave it open: an essential app, an allowed app, an app in an allowed
 * group and an app with no launcher entry are never listed.
 */
class CoveringAllAppsTest {

    private val app = "com.instagram.android"
    private val normal = CoverageFacts(essential = false, launchable = true, category = AppCategory.UNDEFINED, browser = false)
    private val work = AppGroup(id = 1, name = "Work")
    private val groups = listOf(work)
    private val labels = mapOf("com.spotify" to "Spotify", app to "Instagram")

    private fun cover(
        rules: List<BlockRule>,
        members: List<AppGroupMember> = emptyList(),
        facts: CoverageFacts = normal
    ) = coveringAllApps(app, groups, members, rules, labels, facts)

    private fun allApps(
        id: Long,
        enabled: Boolean = true,
        mode: String = "HARD_BLOCK",
        exceptPackages: String? = null,
        exceptGroupIds: String? = null
    ) = BlockRule(
        id = id, allApps = true, mode = mode, enabled = enabled,
        exceptPackages = exceptPackages, exceptGroupIds = exceptGroupIds
    )

    @Test
    fun `an enabled All apps rule covers the app - title, then its summary, opening that rule`() {
        val covering = cover(listOf(allApps(7, mode = "DELAY", exceptPackages = "com.spotify").copy(delaySeconds = 15)))

        assertEquals(
            listOf(CoveringAllApps(7, "All apps except Spotify · Delay 15s")),
            covering
        )
    }

    @Test
    fun `oldest rule first`() {
        val covering = cover(listOf(allApps(9), allApps(2)))

        assertEquals(listOf(2L, 9L), covering.map { it.ruleId })
    }

    @Test
    fun `a rule that is off covers nothing`() {
        assertTrue(cover(listOf(allApps(1, enabled = false))).isEmpty())
    }

    @Test
    fun `an app the rule allows is not covered by it`() {
        assertTrue(cover(listOf(allApps(1, exceptPackages = app))).isEmpty())
    }

    @Test
    fun `an app in a group the rule allows is not covered by it`() {
        val rules = listOf(allApps(1, exceptGroupIds = "1"))

        assertTrue(cover(rules, members = listOf(AppGroupMember(1, app))).isEmpty())
        // The same rule does cover the app once it is not in that group.
        assertEquals(listOf(1L), cover(rules).map { it.ruleId })
    }

    @Test
    fun `an essential app is never covered`() {
        assertTrue(cover(listOf(allApps(1)), facts = CoverageFacts(essential = true, launchable = true, category = AppCategory.UNDEFINED, browser = false)).isEmpty())
    }

    @Test
    fun `an app with no launcher entry is not covered`() {
        assertTrue(cover(listOf(allApps(1)), facts = CoverageFacts(essential = false, launchable = false, category = AppCategory.UNDEFINED, browser = false)).isEmpty())
    }

    @Test
    fun `app rules and group rules are not All apps rules`() {
        val rules = listOf(
            BlockRule(id = 1, packageName = app, mode = "HARD_BLOCK"),
            BlockRule(id = 2, groupId = 1, mode = "HARD_BLOCK")
        )

        assertTrue(cover(rules, members = listOf(AppGroupMember(1, app))).isEmpty())
    }

    @Test
    fun `an app in an allowed group by its category is not covered`() {
        val social = AppGroup(id = 2, name = "Social", categories = "SOCIAL")
        val rules = listOf(allApps(1, exceptGroupIds = "2"))
        val socialApp = normal.copy(category = AppCategory.SOCIAL)

        assertTrue(coveringAllApps(app, listOf(social), emptyList(), rules, labels, socialApp).isEmpty())
        // The same rule covers an app of another category.
        assertEquals(
            listOf(1L),
            coveringAllApps(app, listOf(social), emptyList(), rules, labels, normal.copy(category = AppCategory.GAME))
                .map { it.ruleId }
        )
    }
}
