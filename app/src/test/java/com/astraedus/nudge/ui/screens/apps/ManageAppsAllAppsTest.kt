package com.astraedus.nudge.ui.screens.apps

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.testing.aBlockRule
import com.astraedus.nudge.testing.anApp
import com.astraedus.nudge.testing.anAppGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1 (pure unit): Manage Apps' "All apps" section (spec 3.2) and the "+ Block several apps" sheet's
 * row targets. The section sits above Groups inside the one stable top row, only when an All apps
 * rule exists, and search filters it by title.
 */
class ManageAppsAllAppsTest {

    private val installed = listOf(
        anApp("com.instagram", "Instagram"),
        anApp("com.spotify", "Spotify"),
        anApp("com.youtube", "YouTube")
    )

    private val work = anAppGroup("Work", id = 1)
    private val allApps = aBlockRule(id = 10, allApps = true, mode = "HARD_BLOCK", exceptPackages = "com.spotify")
    private val allAppsOff = aBlockRule(
        id = 11, allApps = true, mode = "DELAY", delaySeconds = 15, enabled = false, exceptGroupIds = "1"
    )

    private fun sections(
        rules: List<BlockRule> = emptyList(),
        groups: List<AppGroup> = emptyList(),
        members: List<AppGroupMember> = emptyList(),
        query: String = ""
    ) = buildManageAppsSections(installed, groups, members, rules, emptySet(), emptySet(), query, emptyMap(), emptySet())

    // -- Visibility ---------------------------------------------------------------------------

    @Test
    fun `no All apps rule - no section`() {
        val s = sections(rules = listOf(aBlockRule(id = 1, packageName = "com.youtube", mode = "HARD_BLOCK")))

        assertTrue(s.allApps.isEmpty())
    }

    @Test
    fun `an All apps rule is a row with its title, summary and on state`() {
        val s = sections(rules = listOf(allApps, allAppsOff), groups = listOf(work))

        assertEquals(listOf(10L, 11L), s.allApps.map { it.ruleId })
        assertEquals(listOf("All apps except Spotify", "All apps except Work"), s.allApps.map { it.title })
        assertEquals(listOf("Hard Block", "Delay 15s"), s.allApps.map { it.summary })
        assertEquals(listOf(true, false), s.allApps.map { it.enabled })
    }

    @Test
    fun `an All apps rule is never an app row or a group row`() {
        val s = sections(rules = listOf(allApps), groups = listOf(work))

        assertTrue(s.appsWithRules.isEmpty())
        assertTrue(s.groups.all { it.summary != "Hard Block" })
        assertEquals(installed.map { it.appName }, s.otherApps.map { it.appInfo.appName })
    }

    // -- Order and the stable top row -------------------------------------------------------------

    @Test
    fun `the whole section lives in the one Top row, so the list never re-keys around it`() {
        val without = manageAppsRows(sections())
        val with = manageAppsRows(sections(rules = listOf(allApps)))

        // Same shape either way: the Top row first, then the app rows. The section is inside Top.
        assertEquals(ManageAppsRow.Top, without.first())
        assertEquals(ManageAppsRow.Top, with.first())
        assertEquals(without.drop(1).size, with.drop(1).size)
    }

    @Test
    fun `an All apps rule leaves the first-run layout - the app list is headed Other apps`() {
        assertEquals("Apps", sections().otherAppsTitle)
        assertEquals("Other apps", sections(rules = listOf(allApps)).otherAppsTitle)
    }

    // -- Search -----------------------------------------------------------------------------------

    @Test
    fun `search filters the section by title, ignoring case`() {
        val s = sections(rules = listOf(allApps, allAppsOff), groups = listOf(work), query = "WORK")

        assertEquals(listOf(11L), s.allApps.map { it.ruleId })
    }

    @Test
    fun `a search that matches nothing hides the section`() {
        val s = sections(rules = listOf(allApps), query = "zzz")

        assertTrue(s.allApps.isEmpty())
        assertTrue(s.nothingMatches)
    }

    @Test
    fun `a search only an All apps title matches is not an empty result`() {
        val s = sections(rules = listOf(allApps), query = "except")

        assertEquals(listOf(10L), s.allApps.map { it.ruleId })
        assertFalse(s.nothingMatches)
    }

    // -- "Block several apps" sheet ------------------------------------------------------------------

    @Test
    fun `the sheet offers a group, then All apps except a few, in plain words`() {
        assertEquals(listOf(BlockSeveralOption.GROUP, BlockSeveralOption.ALL_APPS), BlockSeveralOption.entries)
        assertEquals("A group of apps", BlockSeveralOption.GROUP.title)
        assertEquals("Pick the apps to block together", BlockSeveralOption.GROUP.subtitle)
        assertEquals("All apps except a few", BlockSeveralOption.ALL_APPS.title)
        assertEquals("Only the apps you choose stay open", BlockSeveralOption.ALL_APPS.subtitle)
    }

    @Test
    fun `the group row opens the new-group flow, the All apps row a new All apps rule`() {
        val opened = mutableListOf<String>()
        val onGroup: (Long?) -> Unit = { opened += "group:$it" }
        val onAllApps: (Long?) -> Unit = { opened += "allApps:$it" }

        BlockSeveralOption.GROUP.open(onGroup, onAllApps)
        BlockSeveralOption.ALL_APPS.open(onGroup, onAllApps)

        // A null id is "new" for both.
        assertEquals(listOf("group:null", "allApps:null"), opened)
    }
}
