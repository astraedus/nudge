package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.testing.aBlockRuleData
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L1: what [CoveredApps] counts once an All apps rule is on (spec 3.2, 3.4): launchable apps, minus
 * essentials, minus the rule's allowed apps, minus its allowed groups' members, joined with what the
 * other rules cover.
 */
class CoveredAppsAllAppsTest {

    private val launchable = setOf("youtube", "maps", "spotify", "dialer", "chrome")
    private val essentials = setOf("dialer", "launcher")
    private val music = 3L
    private val memberships = membership(music to "spotify")
    private val hardBlock = aBlockRuleData(mode = BlockMode.HARD_BLOCK)

    private fun covered(rules: List<BlockRuleData>) =
        CoveredApps.packages(rules, memberships, launchable.associateWith { AppCategory.UNDEFINED }, essentials, browsers = emptySet())

    @Test
    fun `All apps covers launchable minus essentials, allowed apps and allowed groups' members`() {
        val rules = listOf(hardBlock.copy(id = 1L, allApps = true, exceptPackages = setOf("maps"), exceptGroupIds = setOf(music)))
        assertEquals(setOf("youtube", "chrome"), covered(rules))
    }

    @Test
    fun `other rules add to it, including on apps the All apps rule allows`() {
        val rules = listOf(
            hardBlock.copy(id = 1L, allApps = true, exceptPackages = setOf("maps"), exceptGroupIds = setOf(music)),
            hardBlock.copy(id = 2L, packageName = "maps"),
            hardBlock.copy(id = 3L, packageName = "not.launchable.service")
        )
        assertEquals(setOf("youtube", "chrome", "maps", "not.launchable.service"), covered(rules))
    }

    @Test
    fun `an essential app's own rule counts it, the All apps rule never does`() {
        assertEquals(setOf("youtube", "maps", "spotify", "chrome"), covered(listOf(hardBlock.copy(id = 1L, allApps = true))))
        assertEquals(
            setOf("youtube", "maps", "spotify", "chrome", "dialer"),
            covered(listOf(hardBlock.copy(id = 1L, allApps = true), hardBlock.copy(id = 2L, packageName = "dialer")))
        )
    }

    @Test
    fun `a disabled All apps rule covers nothing`() {
        assertEquals(emptySet<String>(), covered(listOf(hardBlock.copy(id = 1L, allApps = true, enabled = false))))
        assertEquals(setOf("maps"), covered(listOf(hardBlock.copy(id = 1L, allApps = true, enabled = false), hardBlock.copy(id = 2L, packageName = "maps"))))
    }

    /** Two All apps rules: an app is covered when EITHER covers it (rules only add). */
    @Test
    fun `two All apps rules cover the union`() {
        val rules = listOf(
            hardBlock.copy(id = 1L, allApps = true, exceptPackages = setOf("maps", "youtube")),
            hardBlock.copy(id = 2L, allApps = true, exceptPackages = setOf("maps", "chrome"))
        )
        assertEquals(setOf("youtube", "chrome", "spotify"), covered(rules))
    }
}
