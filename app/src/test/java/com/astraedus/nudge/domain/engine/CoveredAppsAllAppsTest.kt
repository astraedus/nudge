package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.model.GroupMembership
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
    private val memberships = listOf(GroupMembership(music, "spotify"))

    private fun rule(
        id: Long,
        packageName: String? = null,
        groupId: Long? = null,
        allApps: Boolean = false,
        except: Set<String> = emptySet(),
        exceptGroups: Set<Long> = emptySet(),
        enabled: Boolean = true
    ) = BlockRuleData(
        id = id,
        packageName = packageName,
        groupId = groupId,
        mode = BlockMode.HARD_BLOCK,
        delaySeconds = 15,
        dailyLimitMinutes = null,
        enabled = enabled,
        allApps = allApps,
        exceptPackages = except,
        exceptGroupIds = exceptGroups
    )

    private fun covered(rules: List<BlockRuleData>) =
        CoveredApps.packages(rules, memberships, launchable, essentials)

    @Test
    fun `All apps covers launchable minus essentials, allowed apps and allowed groups' members`() {
        val rules = listOf(rule(1L, allApps = true, except = setOf("maps"), exceptGroups = setOf(music)))
        assertEquals(setOf("youtube", "chrome"), covered(rules))
    }

    @Test
    fun `other rules add to it, including on apps the All apps rule allows`() {
        val rules = listOf(
            rule(1L, allApps = true, except = setOf("maps"), exceptGroups = setOf(music)),
            rule(2L, packageName = "maps"),
            rule(3L, packageName = "not.launchable.service")
        )
        assertEquals(setOf("youtube", "chrome", "maps", "not.launchable.service"), covered(rules))
    }

    @Test
    fun `an essential app's own rule counts it, the All apps rule never does`() {
        assertEquals(setOf("youtube", "maps", "spotify", "chrome"), covered(listOf(rule(1L, allApps = true))))
        assertEquals(
            setOf("youtube", "maps", "spotify", "chrome", "dialer"),
            covered(listOf(rule(1L, allApps = true), rule(2L, packageName = "dialer")))
        )
    }

    @Test
    fun `a disabled All apps rule covers nothing`() {
        assertEquals(emptySet<String>(), covered(listOf(rule(1L, allApps = true, enabled = false))))
        assertEquals(setOf("maps"), covered(listOf(rule(1L, allApps = true, enabled = false), rule(2L, packageName = "maps"))))
    }

    /** Two All apps rules: an app is covered when EITHER covers it (rules only add). */
    @Test
    fun `two All apps rules cover the union`() {
        val rules = listOf(
            rule(1L, allApps = true, except = setOf("maps", "youtube")),
            rule(2L, allApps = true, except = setOf("maps", "chrome"))
        )
        assertEquals(setOf("youtube", "chrome", "spotify"), covered(rules))
    }
}
