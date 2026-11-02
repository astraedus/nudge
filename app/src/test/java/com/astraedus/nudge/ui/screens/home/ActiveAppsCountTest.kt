package com.astraedus.nudge.ui.screens.home

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.RuleSnapshot
import com.astraedus.nudge.domain.engine.CoverageFacts
import com.astraedus.nudge.domain.engine.CoveredApps
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.testing.aBlockRule
import com.astraedus.nudge.testing.aGroupMember
import com.astraedus.nudge.testing.anAppGroup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Home tile "Active Apps" (spec 3.2): apps covered by a rule that is on. It used to count the
 * packages named by enabled rule rows, so a group covering nine apps added nothing to it.
 */
class ActiveAppsCountTest {

    private val social = anAppGroup("Social", id = 1)
    private val games = anAppGroup("Games", id = 2)
    private val members = listOf(
        aGroupMember(1, "com.instagram"),
        aGroupMember(1, "com.tiktok"),
        aGroupMember(2, "com.chess")
    )

    /** No All apps rule in these cases, so the device facts never matter. */
    private fun count(rules: List<BlockRule>, groups: List<AppGroup>, members: List<AppGroupMember>) =
        activeAppsCount(rules, groups, members, launchable = emptyMap(), essentials = emptySet(), browsers = emptySet())

    @Test
    fun `nothing set up counts nothing`() {
        assertEquals(0, count(emptyList(), emptyList(), emptyList()))
        assertEquals(0, count(emptyList(), listOf(social), members))
    }

    @Test
    fun `an app with its own rule and a place in a blocking group counts once`() {
        val rules = listOf(
            aBlockRule(id = 1, packageName = "com.instagram"),
            aBlockRule(id = 2, packageName = "com.youtube", mode = "HARD_BLOCK"),
            aBlockRule(id = 3, groupId = 1)
        )
        // instagram (direct + Social), tiktok (Social), youtube (direct).
        assertEquals(3, count(rules, listOf(social, games), members))
    }

    @Test
    fun `a disabled rule covers nothing`() {
        val rules = listOf(
            aBlockRule(id = 1, packageName = "com.youtube", mode = "HARD_BLOCK", enabled = false),
            aBlockRule(id = 2, packageName = "com.reddit")
        )
        assertEquals(1, count(rules, listOf(social), members))
    }

    @Test
    fun `a group whose rule is off covers none of its apps`() {
        val rules = listOf(
            aBlockRule(id = 1, groupId = 1, enabled = false),
            aBlockRule(id = 2, groupId = 2, mode = "HARD_BLOCK")
        )
        assertEquals(1, count(rules, listOf(social, games), members))
    }

    @Test
    fun `an app whose own rule is off still counts while a group covers it`() {
        val rules = listOf(
            aBlockRule(id = 1, packageName = "com.instagram", enabled = false),
            aBlockRule(id = 2, groupId = 1)
        )
        assertEquals(2, count(rules, listOf(social), members))
    }

    @Test
    fun `members of a group that no longer exists count for nothing`() {
        val rules = listOf(aBlockRule(id = 1, groupId = 9))
        assertEquals(0, count(rules, listOf(social), members + aGroupMember(9, "com.x")))
    }

    // --- With an All apps rule on (spec PR 4): the tile counts covered apps, each once ---

    private val launchable = setOf("com.instagram", "com.tiktok", "com.chess", "com.maps", "com.dialer")
        .associateWith { AppCategory.UNDEFINED }
    private val essentials = setOf("com.dialer")

    private fun allApps(id: Long, except: String? = null, exceptGroups: String? = null, enabled: Boolean = true) =
        aBlockRule(id = id, allApps = true, mode = "HARD_BLOCK", exceptPackages = except, exceptGroupIds = exceptGroups, enabled = enabled)

    @Test
    fun `an app covered by its own rule, its group and an All apps rule counts once`() {
        val rules = listOf(
            aBlockRule(id = 1, packageName = "com.instagram"),
            aBlockRule(id = 2, groupId = 1),
            allApps(id = 3, except = "com.maps")
        )
        // instagram, tiktok, chess. Not maps (allowed), not the dialer (essential): 3, not 3 + 2 + 1.
        assertEquals(3, activeAppsCount(rules, listOf(social, games), members, launchable, essentials, browsers = emptySet()))
    }

    @Test
    fun `two All apps rules on at once count each app once`() {
        val rules = listOf(allApps(id = 1), allApps(id = 2, except = "com.maps"))
        // Every launchable app but the essential dialer; maps is still covered by rule 1.
        assertEquals(4, activeAppsCount(rules, emptyList(), emptyList(), launchable, essentials, browsers = emptySet()))
    }

    @Test
    fun `an app the All apps rule allows still counts while its own rule blocks it (rules only add)`() {
        val rules = listOf(
            aBlockRule(id = 1, packageName = "com.maps"),
            allApps(id = 2, except = "com.maps")
        )
        assertEquals(4, activeAppsCount(rules, emptyList(), emptyList(), launchable, essentials, browsers = emptySet()))
    }

    @Test
    fun `an All apps rule that is off adds nothing`() {
        val rules = listOf(aBlockRule(id = 1, packageName = "com.instagram"), allApps(id = 2, enabled = false))
        assertEquals(1, activeAppsCount(rules, emptyList(), emptyList(), launchable, essentials, browsers = emptySet()))
    }

    @Test
    fun `with every kind of rule on at once, the count is the distinct apps, never a sum per rule`() {
        val rules = listOf(
            aBlockRule(id = 1, packageName = "com.instagram"),
            aBlockRule(id = 2, packageName = "com.notinstalled"),
            aBlockRule(id = 3, groupId = 1),
            allApps(id = 4),
            allApps(id = 5, exceptGroups = "1")
        )
        val count = activeAppsCount(rules, listOf(social, games), members, launchable, essentials, browsers = emptySet())
        val ceiling = (launchable.keys - essentials + "com.notinstalled").size
        assertEquals(ceiling, count)
    }

    /** The count is the engine's own answer, package by package, not a second implementation. */
    @Test
    fun `every counted app is one the engine resolves an enabled rule for, and vice versa`() {
        val rules = listOf(
            aBlockRule(id = 1, packageName = "com.instagram"),
            aBlockRule(id = 2, packageName = "com.youtube", mode = "HARD_BLOCK", enabled = false),
            aBlockRule(id = 3, groupId = 1),
            aBlockRule(id = 4, groupId = 2, enabled = false)
        )
        val snapshot = RuleSnapshot.of(rules.filter { it.enabled }, listOf(social, games), members)
        val covered = CoveredApps.packages(snapshot.ruleData, snapshot.membership, emptyMap(), emptySet(), browsers = emptySet())
        val everyPackage = listOf("com.instagram", "com.tiktok", "com.youtube", "com.chess", "com.other")

        everyPackage.forEach { pkg ->
            val facts = CoverageFacts(essential = false, launchable = false, category = AppCategory.UNDEFINED, browser = false)
            val engine = RuleEvaluator().resolveRulesForPackage(pkg, snapshot.ruleData, snapshot.membership, facts)
            assertEquals(pkg, engine.any { it.enabled }, pkg in covered)
        }
        assertEquals(setOf("com.instagram", "com.tiktok"), covered)
    }

    // --- Category groups (spec 5.2): members come from the category, with no member row ---

    @Test
    fun `an app in a blocking category group counts once, even when also added by hand`() {
        val cat = anAppGroup("Social apps", id = 5, categories = "SOCIAL")
        val installed = mapOf(
            "com.instagram" to AppCategory.SOCIAL,
            "com.tiktok" to AppCategory.SOCIAL,
            "com.chess" to AppCategory.GAME
        )
        val rules = listOf(
            aBlockRule(id = 1, groupId = 5),
            aBlockRule(id = 2, packageName = "com.instagram", mode = "HARD_BLOCK")
        )
        val added = listOf(aGroupMember(5, "com.instagram"))

        // instagram (category + hand + own rule) once, tiktok (category): 2. Chess is a game.
        assertEquals(2, activeAppsCount(rules, listOf(cat), added, installed, emptySet(), browsers = emptySet()))
    }

    @Test
    fun `an essential app of a blocked category does not count, nor does one removed by the user`() {
        val cat = anAppGroup("Social apps", id = 5, categories = "SOCIAL")
        val installed = mapOf(
            "com.instagram" to AppCategory.SOCIAL,
            "com.tiktok" to AppCategory.SOCIAL,
            "com.dialer" to AppCategory.SOCIAL
        )
        val rules = listOf(aBlockRule(id = 1, groupId = 5))
        val removed = listOf(aGroupMember(5, "com.tiktok", excluded = true))

        assertEquals(1, activeAppsCount(rules, listOf(cat), removed, installed, setOf("com.dialer"), browsers = emptySet()))
    }
}
