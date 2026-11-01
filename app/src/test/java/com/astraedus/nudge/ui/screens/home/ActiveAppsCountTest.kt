package com.astraedus.nudge.ui.screens.home

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.RuleSnapshot
import com.astraedus.nudge.domain.engine.CoverageFacts
import com.astraedus.nudge.domain.engine.CoveredApps
import com.astraedus.nudge.domain.engine.RuleEvaluator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Home tile "Active Apps" (spec 3.2): apps covered by a rule that is on. It used to count the
 * packages named by enabled rule rows, so a group covering nine apps added nothing to it.
 */
class ActiveAppsCountTest {

    private val social = AppGroup(id = 1, name = "Social")
    private val games = AppGroup(id = 2, name = "Games")
    private val members = listOf(
        AppGroupMember(1, "com.instagram"),
        AppGroupMember(1, "com.tiktok"),
        AppGroupMember(2, "com.chess")
    )

    /** No All apps rule in these cases, so the device facts never matter. */
    private fun count(rules: List<BlockRule>, groups: List<AppGroup>, members: List<AppGroupMember>) =
        activeAppsCount(rules, groups, members, launchable = emptySet(), essentials = emptySet())

    @Test
    fun `nothing set up counts nothing`() {
        assertEquals(0, count(emptyList(), emptyList(), emptyList()))
        assertEquals(0, count(emptyList(), listOf(social), members))
    }

    @Test
    fun `an app with its own rule and a place in a blocking group counts once`() {
        val rules = listOf(
            BlockRule(id = 1, packageName = "com.instagram", mode = "DELAY"),
            BlockRule(id = 2, packageName = "com.youtube", mode = "HARD_BLOCK"),
            BlockRule(id = 3, groupId = 1, mode = "DELAY")
        )
        // instagram (direct + Social), tiktok (Social), youtube (direct).
        assertEquals(3, count(rules, listOf(social, games), members))
    }

    @Test
    fun `a disabled rule covers nothing`() {
        val rules = listOf(
            BlockRule(id = 1, packageName = "com.youtube", mode = "HARD_BLOCK", enabled = false),
            BlockRule(id = 2, packageName = "com.reddit", mode = "DELAY")
        )
        assertEquals(1, count(rules, listOf(social), members))
    }

    @Test
    fun `a group whose rule is off covers none of its apps`() {
        val rules = listOf(
            BlockRule(id = 1, groupId = 1, mode = "DELAY", enabled = false),
            BlockRule(id = 2, groupId = 2, mode = "HARD_BLOCK")
        )
        assertEquals(1, count(rules, listOf(social, games), members))
    }

    @Test
    fun `an app whose own rule is off still counts while a group covers it`() {
        val rules = listOf(
            BlockRule(id = 1, packageName = "com.instagram", mode = "DELAY", enabled = false),
            BlockRule(id = 2, groupId = 1, mode = "DELAY")
        )
        assertEquals(2, count(rules, listOf(social), members))
    }

    @Test
    fun `members of a group that no longer exists count for nothing`() {
        val rules = listOf(BlockRule(id = 1, groupId = 9, mode = "DELAY"))
        assertEquals(0, count(rules, listOf(social), members + AppGroupMember(9, "com.x")))
    }

    // --- With an All apps rule on (spec PR 4): the tile counts covered apps, each once ---

    private val launchable = setOf("com.instagram", "com.tiktok", "com.chess", "com.maps", "com.dialer")
    private val essentials = setOf("com.dialer")

    private fun allApps(id: Long, except: String? = null, exceptGroups: String? = null, enabled: Boolean = true) =
        BlockRule(id = id, allApps = true, mode = "HARD_BLOCK", exceptPackages = except, exceptGroupIds = exceptGroups, enabled = enabled)

    @Test
    fun `an app covered by its own rule, its group and an All apps rule counts once`() {
        val rules = listOf(
            BlockRule(id = 1, packageName = "com.instagram", mode = "DELAY"),
            BlockRule(id = 2, groupId = 1, mode = "DELAY"),
            allApps(id = 3, except = "com.maps")
        )
        // instagram, tiktok, chess. Not maps (allowed), not the dialer (essential): 3, not 3 + 2 + 1.
        assertEquals(3, activeAppsCount(rules, listOf(social, games), members, launchable, essentials))
    }

    @Test
    fun `two All apps rules on at once count each app once`() {
        val rules = listOf(allApps(id = 1), allApps(id = 2, except = "com.maps"))
        // Every launchable app but the essential dialer; maps is still covered by rule 1.
        assertEquals(4, activeAppsCount(rules, emptyList(), emptyList(), launchable, essentials))
    }

    @Test
    fun `an app the All apps rule allows still counts while its own rule blocks it (rules only add)`() {
        val rules = listOf(
            BlockRule(id = 1, packageName = "com.maps", mode = "DELAY"),
            allApps(id = 2, except = "com.maps")
        )
        assertEquals(4, activeAppsCount(rules, emptyList(), emptyList(), launchable, essentials))
    }

    @Test
    fun `an All apps rule that is off adds nothing`() {
        val rules = listOf(BlockRule(id = 1, packageName = "com.instagram", mode = "DELAY"), allApps(id = 2, enabled = false))
        assertEquals(1, activeAppsCount(rules, emptyList(), emptyList(), launchable, essentials))
    }

    @Test
    fun `with every kind of rule on at once, the count is the distinct apps, never a sum per rule`() {
        val rules = listOf(
            BlockRule(id = 1, packageName = "com.instagram", mode = "DELAY"),
            BlockRule(id = 2, packageName = "com.notinstalled", mode = "DELAY"),
            BlockRule(id = 3, groupId = 1, mode = "DELAY"),
            allApps(id = 4),
            allApps(id = 5, exceptGroups = "1")
        )
        val count = activeAppsCount(rules, listOf(social, games), members, launchable, essentials)
        val ceiling = (launchable - essentials + "com.notinstalled").size
        assertEquals(ceiling, count)
    }

    /** The count is the engine's own answer, package by package, not a second implementation. */
    @Test
    fun `every counted app is one the engine resolves an enabled rule for, and vice versa`() {
        val rules = listOf(
            BlockRule(id = 1, packageName = "com.instagram", mode = "DELAY"),
            BlockRule(id = 2, packageName = "com.youtube", mode = "HARD_BLOCK", enabled = false),
            BlockRule(id = 3, groupId = 1, mode = "DELAY"),
            BlockRule(id = 4, groupId = 2, mode = "DELAY", enabled = false)
        )
        val snapshot = RuleSnapshot.of(rules.filter { it.enabled }, listOf(social, games), members)
        val covered = CoveredApps.packages(snapshot.ruleData, snapshot.memberships, emptySet(), emptySet())
        val everyPackage = listOf("com.instagram", "com.tiktok", "com.youtube", "com.chess", "com.other")

        everyPackage.forEach { pkg ->
            val facts = CoverageFacts(essential = false, launchable = false)
            val engine = RuleEvaluator().resolveRulesForPackage(pkg, snapshot.ruleData, snapshot.memberships, facts)
            assertEquals(pkg, engine.any { it.enabled }, pkg in covered)
        }
        assertEquals(setOf("com.instagram", "com.tiktok"), covered)
    }
}
