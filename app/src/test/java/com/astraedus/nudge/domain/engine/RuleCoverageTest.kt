package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1: what each rule kind covers (spec 4.1), the one answer evaluation, browser routing, the Home
 * count and the editors share.
 */
class RuleCoverageTest {

    private val plain = CoverageFacts(essential = false, launchable = true, category = null)
    private val essential = CoverageFacts(essential = true, launchable = true, category = null)
    private val notLaunchable = CoverageFacts(essential = false, launchable = false, category = null)

    private fun appRule(pkg: String) = rule(packageName = pkg)
    private fun groupRule(groupId: Long) = rule(groupId = groupId)
    private fun allAppsRule(except: Set<String> = emptySet(), exceptGroups: Set<Long> = emptySet()) =
        rule(allApps = true, exceptPackages = except, exceptGroupIds = exceptGroups)

    private fun rule(
        packageName: String? = null,
        groupId: Long? = null,
        allApps: Boolean = false,
        exceptPackages: Set<String> = emptySet(),
        exceptGroupIds: Set<Long> = emptySet()
    ) = BlockRuleData(
        id = 1L,
        packageName = packageName,
        groupId = groupId,
        mode = BlockMode.HARD_BLOCK,
        delaySeconds = 15,
        dailyLimitMinutes = null,
        enabled = true,
        allApps = allApps,
        exceptPackages = exceptPackages,
        exceptGroupIds = exceptGroupIds
    )

    @Test
    fun `each kind covers what it names`() {
        assertTrue(RuleCoverage.covers(appRule("a"), "a", emptySet(), plain))
        assertFalse(RuleCoverage.covers(appRule("a"), "b", emptySet(), plain))
        assertTrue(RuleCoverage.covers(groupRule(7L), "a", setOf(7L), plain))
        assertFalse(RuleCoverage.covers(groupRule(7L), "a", setOf(8L), plain))
        assertTrue(RuleCoverage.covers(allAppsRule(), "a", emptySet(), plain))
    }

    /** Spec 4.2: never lock the user out. An explicit app rule is the user's own choice and stands. */
    @Test
    fun `an essential app is never covered by a group or All apps rule, but its own app rule covers it`() {
        assertFalse(RuleCoverage.covers(groupRule(7L), "dialer", setOf(7L), essential))
        assertFalse(RuleCoverage.covers(allAppsRule(), "dialer", emptySet(), essential))
        assertTrue(RuleCoverage.covers(appRule("dialer"), "dialer", emptySet(), essential))
    }

    @Test
    fun `an app with no launcher entry is never covered by All apps, but its group and app rules still cover it`() {
        assertFalse(RuleCoverage.covers(allAppsRule(), "svc", emptySet(), notLaunchable))
        assertTrue(RuleCoverage.covers(groupRule(7L), "svc", setOf(7L), notLaunchable))
        assertTrue(RuleCoverage.covers(appRule("svc"), "svc", emptySet(), notLaunchable))
    }

    @Test
    fun `an allowed app and a member of an allowed group are not covered by that All apps rule`() {
        val rule = allAppsRule(except = setOf("maps"), exceptGroups = setOf(3L))
        assertFalse(RuleCoverage.covers(rule, "maps", emptySet(), plain))
        assertFalse(RuleCoverage.covers(rule, "spotify", setOf(3L), plain))
        assertFalse("a member of an allowed AND a blocked group", RuleCoverage.covers(rule, "x", setOf(3L, 4L), plain))
        assertTrue(RuleCoverage.covers(rule, "youtube", setOf(4L), plain))
    }

    /** Allowed groups are live references: whoever is a member NOW is allowed (spec 4.1). */
    @Test
    fun `an app that joins an allowed group later is no longer covered`() {
        val rule = allAppsRule(exceptGroups = setOf(3L))
        val before = membership(3L to "maps")
        val after = membership(3L to "maps", 3L to "spotify")

        assertTrue(RuleCoverage.covers(rule, "spotify", RuleCoverage.groupIdsOf("spotify", before, plain), plain))
        assertFalse(RuleCoverage.covers(rule, "spotify", RuleCoverage.groupIdsOf("spotify", after, plain), plain))
    }

    /** A deleted allowed group allows nothing: the rule fails strict, never open. */
    @Test
    fun `a stale allowed group id covers exactly as if it were absent`() {
        val stale = allAppsRule(exceptGroups = setOf(99L))
        val none = allAppsRule()
        for (pkg in listOf("a", "b")) {
            val groups = RuleCoverage.groupIdsOf(pkg, membership(1L to "b"), plain)
            assertEquals(
                RuleCoverage.covers(none, pkg, groups, plain),
                RuleCoverage.covers(stale, pkg, groups, plain)
            )
            assertTrue(RuleCoverage.covers(stale, pkg, groups, plain))
        }
    }

    @Test
    fun `coveringRules lists only enabled covering rules, in order`() {
        val rules = listOf(
            appRule("a").copy(id = 1L),
            groupRule(7L).copy(id = 2L),
            allAppsRule().copy(id = 3L, enabled = false),
            allAppsRule(except = setOf("a")).copy(id = 4L),
            allAppsRule().copy(id = 5L)
        )
        val covering = RuleCoverage.coveringRules("a", rules, membership(7L to "a"), plain)
        assertEquals(listOf(1L, 2L, 5L), covering.map { it.id })
    }

    /** The hot path's promise: a package with only its own rules reads no device fact. */
    @Test
    fun `factsNeeded reads nothing for app rules and groups the package is not in`() {
        val none = RuleCoverage.FactsNeeded(essential = false, launchable = false)
        assertEquals(none, RuleCoverage.factsNeeded(listOf(appRule("a"), appRule("b")), emptySet()))
        assertEquals(none, RuleCoverage.factsNeeded(listOf(appRule("a"), groupRule(7L)), setOf(8L)))
        assertEquals(none, RuleCoverage.factsNeeded(emptyList(), setOf(7L)))
    }

    @Test
    fun `factsNeeded asks for essentials under a group it is in, and both facts under All apps`() {
        assertEquals(
            RuleCoverage.FactsNeeded(essential = true, launchable = false),
            RuleCoverage.factsNeeded(listOf(groupRule(7L)), setOf(7L))
        )
        assertEquals(
            RuleCoverage.FactsNeeded(essential = true, launchable = true),
            RuleCoverage.factsNeeded(listOf(appRule("a"), allAppsRule()), emptySet())
        )
    }

    /**
     * The class, not the instance: wherever factsNeeded says a fact is NOT needed, flipping that
     * fact must not change any rule's coverage. So the lazy read on the hot path can never skip a
     * fact that would have changed the answer.
     */
    @Test
    fun `a fact factsNeeded skips never changes coverage`() {
        val pkgs = listOf("a", "b")
        val ruleSets = listOf(
            listOf(appRule("a")),
            listOf(appRule("a"), groupRule(7L)),
            listOf(groupRule(7L)),
            listOf(allAppsRule(except = setOf("a"))),
            listOf(appRule("b"), allAppsRule(exceptGroups = setOf(7L)))
        )
        val groupSets = listOf(emptySet(), setOf(7L), setOf(8L))
        for (rules in ruleSets) for (pkg in pkgs) for (groups in groupSets) {
            val needed = RuleCoverage.factsNeeded(rules, groups)
            for (e in listOf(false, true)) for (l in listOf(false, true)) {
                val facts = CoverageFacts(essential = e, launchable = l, category = null)
                val flipped = CoverageFacts(
                    essential = if (needed.essential) e else !e,
                    launchable = if (needed.launchable) l else !l,
                    category = null
                )
                for (rule in rules) {
                    assertEquals(
                        "rules=$rules pkg=$pkg groups=$groups facts=$facts",
                        RuleCoverage.covers(rule, pkg, groups, facts),
                        RuleCoverage.covers(rule, pkg, groups, flipped)
                    )
                }
            }
        }
    }

    /** A row naming no target covers nothing, whatever the facts. */
    @Test
    fun `a rule with no target covers nothing`() {
        assertFalse(RuleCoverage.covers(rule(), "a", setOf(7L), plain))
    }
}
