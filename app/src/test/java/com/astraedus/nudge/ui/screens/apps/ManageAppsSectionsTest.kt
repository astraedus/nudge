package com.astraedus.nudge.ui.screens.apps

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.InstalledAppsRepository.AppInfo
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.testing.aBlockRule
import com.astraedus.nudge.testing.aGroupMember
import com.astraedus.nudge.testing.anApp
import com.astraedus.nudge.testing.anAppGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manage Apps' sections (spec 3.2): which rows land in which section, in what order, under which
 * heading, for an empty phone, a typical one and a heavy setup, and what a search keeps.
 */
class ManageAppsSectionsTest {

    private val installed = listOf(
        anApp("com.android.calculator", "Calculator"),
        anApp("com.android.camera", "Camera"),
        anApp("com.instagram", "Instagram"),
        anApp("com.reddit", "Reddit"),
        anApp("com.tiktok", "TikTok"),
        anApp("com.youtube", "YouTube")
    )

    private fun sections(
        groups: List<AppGroup> = emptyList(),
        members: List<AppGroupMember> = emptyList(),
        rules: List<BlockRule> = emptyList(),
        query: String = "",
        extraApps: Map<String, AppInfo> = emptyMap(),
        keepInPlace: Set<String> = emptySet()
    ) = buildManageAppsSections(installed, groups, members, rules, emptySet(), emptySet(), query, extraApps, keepInPlace)

    private fun ManageAppsSections.names(list: List<AppRow>) = list.map { it.appInfo.appName }

    // ── Empty phone: the first-run layout ─────────────────────────────────

    @Test
    fun `no rules and no groups - the one row, then every app under Apps`() {
        val s = sections()

        assertTrue(s.groups.isEmpty())
        assertTrue(s.showNewGroup)
        assertTrue(s.appsWithRules.isEmpty())
        assertEquals("Apps", s.otherAppsTitle)
        assertEquals(installed.map { it.appName }, s.names(s.otherApps))
        assertTrue(s.otherApps.none { it.hasRule || it.isOn })
        assertEquals(
            listOf(ManageAppsRow.Top, ManageAppsRow.Header(ManageAppsRow.OTHER_APPS_KEY, "Apps")) +
                s.otherApps.map { ManageAppsRow.App(it) },
            manageAppsRows(s)
        )
    }

    // ── Typical: 2 app rules + 1 group ─────────────────────────────────────

    private val social = anAppGroup("Social", id = 1)
    private val typicalRules = listOf(
        aBlockRule(id = 1, packageName = "com.youtube", mode = "DELAY", delaySeconds = 15, dailyLimitMinutes = 30),
        aBlockRule(id = 2, packageName = "com.instagram", mode = "HARD_BLOCK", enabled = false),
        aBlockRule(id = 3, groupId = 1, mode = "DELAY", delaySeconds = 15)
    )
    private val typicalMembers = listOf(aGroupMember(1, "com.tiktok"), aGroupMember(1, "com.reddit"))

    @Test
    fun `typical - groups, the row, apps with rules by name, then Other apps`() {
        val s = sections(listOf(social), typicalMembers, typicalRules)

        assertEquals(listOf("Social · 2 apps"), s.groups.map { it.title })
        assertEquals(listOf("Delay 15s"), s.groups.map { it.summary })
        assertTrue(s.showNewGroup)
        assertEquals(listOf("Instagram", "YouTube"), s.names(s.appsWithRules))
        assertEquals(listOf("Hard Block", "Delay 15s · 30min limit"), s.appsWithRules.map { it.summary })
        // The switch shows the app's own rule: Instagram's is off.
        assertEquals(listOf(false, true), s.appsWithRules.map { it.isOn })
        assertEquals("Other apps", s.otherAppsTitle)
        // A group member with no rule of its own is an "other" app; the group row covers it.
        assertEquals(listOf("Calculator", "Camera", "Reddit", "TikTok"), s.names(s.otherApps))
        assertTrue(s.otherApps.none { it.hasRule })
    }

    // ── Apps a blocking group covers say so ───────────────────────────────

    /**
     * Orchestrator review (PR 3): TikTok and Reddit sat in "Other apps" with no line and the switch
     * off, reading as unblocked while Social blocked them. They stay there (the switch still means
     * "a rule of its own"), with the group on the second line.
     */
    @Test
    fun `an app covered by one blocking group says In Social`() {
        val s = sections(listOf(social), typicalMembers, typicalRules)

        val byName = s.otherApps.associateBy { it.appInfo.appName }
        assertEquals("In Social", byName.getValue("TikTok").coveredBy)
        assertEquals("In Social", byName.getValue("Reddit").supportingText)
        assertFalse(byName.getValue("TikTok").isOn)
        assertFalse(byName.getValue("TikTok").hasRule)
        // Not in a group: no line at all.
        assertEquals(null, byName.getValue("Calculator").supportingText)
    }

    @Test
    fun `a group whose rule is off covers nothing, so no line`() {
        val rules = typicalRules.map { if (it.groupId == 1L) it.copy(enabled = false) else it }
        val s = sections(listOf(social), typicalMembers, rules)

        assertTrue(s.otherApps.all { it.supportingText == null })
    }

    @Test
    fun `two blocking groups are named, three are counted, disabled ones are not counted`() {
        val work = anAppGroup("Work", id = 2)
        val reading = anAppGroup("Reading", id = 3)
        val evening = anAppGroup("Evening", id = 4)
        val members = typicalMembers + listOf(
            aGroupMember(2, "com.tiktok"),
            aGroupMember(3, "com.reddit"), aGroupMember(2, "com.reddit"),
            aGroupMember(4, "com.reddit"), aGroupMember(4, "com.tiktok")
        )
        val rules = typicalRules + listOf(
            aBlockRule(id = 20, groupId = 2, mode = "HARD_BLOCK"),
            aBlockRule(id = 21, groupId = 3, mode = "HARD_BLOCK"),
            aBlockRule(id = 22, groupId = 4, mode = "HARD_BLOCK", enabled = false)
        )
        val s = sections(listOf(social, work, reading, evening), members, rules)

        val byName = s.otherApps.associateBy { it.appInfo.appName }
        // TikTok: Social + Work blocking, Evening off.
        assertEquals("In Social, Work", byName.getValue("TikTok").coveredBy)
        // Reddit: Reading + Social + Work blocking, Evening off.
        assertEquals("In 3 groups", byName.getValue("Reddit").coveredBy)
    }

    @Test
    fun `search results keep the covered-by line`() {
        val s = sections(listOf(social), typicalMembers, typicalRules, query = "tik")
        assertEquals(listOf("TikTok"), s.names(s.otherApps))
        assertEquals("In Social", s.otherApps.single().supportingText)
    }

    @Test
    fun `an app's own rule is its line, even when a group also covers it`() {
        val rules = typicalRules + aBlockRule(id = 9, packageName = "com.tiktok", mode = "HARD_BLOCK")
        val tiktok = sections(listOf(social), typicalMembers, rules).appsWithRules.single { it.appInfo.appName == "TikTok" }
        assertEquals("Hard Block", tiktok.supportingText)
        assertEquals(null, tiktok.coveredBy)
    }

    @Test
    fun `a group alone already makes the rest Other apps`() {
        assertEquals("Other apps", sections(groups = listOf(social)).otherAppsTitle)
    }

    @Test
    fun `an app rule alone already makes the rest Other apps`() {
        val s = sections(rules = typicalRules.take(1))
        assertEquals("Other apps", s.otherAppsTitle)
        assertTrue(s.groups.isEmpty())
    }

    // ── Heavy: 3 groups + 8 app rules, some off ────────────────────────────

    @Test
    fun `heavy - every rule is listed once, each app's rules read as one line`() {
        val many = (1..8).map { anApp("com.heavy.app$it", "App $it") }
        val groups = listOf(anAppGroup("Work", id = 3), anAppGroup("games", id = 1), anAppGroup("Social", id = 2))
        val rules = many.mapIndexed { i, a ->
            aBlockRule(id = i + 10L, packageName = a.packageName, mode = "DELAY", enabled = i % 3 != 0)
        } + listOf(
            // App 1 also blocks Reels and has a weekend Hard Block: still one row.
            aBlockRule(id = 30, packageName = "com.heavy.app1", mode = "HARD_BLOCK", inAppFeatures = "REELS"),
            aBlockRule(id = 31, packageName = "com.heavy.app1", mode = "HARD_BLOCK", scheduleDays = "6,7"),
            aBlockRule(id = 40, groupId = 1, mode = "HARD_BLOCK"),
            aBlockRule(id = 41, groupId = 2, mode = "DELAY", enabled = false)
        )
        val s = buildManageAppsSections(installed + many, groups, emptyList(), rules, emptySet(), query = "", browsers = emptySet())

        assertEquals(listOf("games · 0 apps", "Social · 0 apps", "Work · 0 apps"), s.groups.map { it.title })
        assertEquals(listOf(true, false, false), s.groups.map { it.blocking })
        assertEquals("Not blocked", s.groups.last().summary)
        assertEquals((1..8).map { "App $it" }, s.names(s.appsWithRules))
        assertEquals(
            "Delay 15s · Hard Block Sat, Sun · Reels: Hard Block",
            s.appsWithRules.first().summary
        )
        // App 1's plain rule is off, but its other two are on: the row is on (Active Rules' "any").
        assertTrue(s.appsWithRules.first().isOn)
        assertEquals(listOf(false, true, true), s.appsWithRules.drop(3).take(3).map { it.isOn })
        assertEquals(installed.map { it.appName }, s.names(s.otherApps))

        val keys = manageAppsRows(s).map { it.key }
        assertEquals(keys, keys.distinct())
    }

    // ── Search ────────────────────────────────────────────────────────────

    @Test
    fun `search matching a group name keeps the group and hides the new-group row`() {
        val s = sections(listOf(social), typicalMembers, typicalRules, query = "soc")

        assertEquals(listOf("Social · 2 apps"), s.groups.map { it.title })
        assertFalse(s.showNewGroup)
        assertTrue(s.appsWithRules.isEmpty())
        assertTrue(s.otherApps.isEmpty())
        assertEquals(listOf(ManageAppsRow.Top), manageAppsRows(s))
    }

    @Test
    fun `search matching an app with rules keeps it in its section, and nothing else`() {
        val s = sections(listOf(social), typicalMembers, typicalRules, query = " insta ")

        assertTrue(s.groups.isEmpty())
        assertEquals(listOf("Instagram"), s.names(s.appsWithRules))
        assertEquals("Hard Block", s.appsWithRules.single().summary)
        assertTrue(s.otherApps.isEmpty())
        assertEquals(
            listOf(ManageAppsRow.Top, ManageAppsRow.Header(ManageAppsRow.WITH_RULES_KEY, "Apps with rules")) +
                ManageAppsRow.App(s.appsWithRules.single()),
            manageAppsRows(s)
        )
    }

    @Test
    fun `search matches package names in both app sections`() {
        val s = sections(rules = typicalRules, query = "com.android")
        assertEquals(listOf("Calculator", "Camera"), s.names(s.otherApps))
        assertTrue(s.appsWithRules.isEmpty())
        // The heading does not flip back to "Apps" just because the rules are filtered out.
        assertEquals("Other apps", s.otherAppsTitle)
    }

    /**
     * Device QA (PR 3): searching "soc" showed the Social group AND "No apps or groups match". The
     * empty state looked at the app rows only, and the groups live inside the top row.
     */
    @Test
    fun `nothing matches only when no group and no app does`() {
        assertFalse(sections(listOf(social), typicalMembers, typicalRules, query = "soc").nothingMatches)
        assertFalse(sections(listOf(social), typicalMembers, typicalRules, query = "you").nothingMatches)
        assertTrue(sections(listOf(social), typicalMembers, typicalRules, query = "zzzz").nothingMatches)
        assertFalse(sections().nothingMatches)
    }

    // ── An app switched on here stays where it was, this visit ─────────────

    /**
     * Device QA (PR 3): switching an "other" app on moved it up into "Apps with rules", the next app
     * slid into its spot, and a quick second tap switched THAT app on. Kept in place, the row under
     * the finger is the same row; it just gains its summary.
     */
    @Test
    fun `an app switched on this visit keeps its place among the other apps`() {
        val rules = typicalRules + aBlockRule(id = 9, packageName = "com.android.camera", mode = "DELAY", delaySeconds = 15)
        val kept = sections(listOf(social), typicalMembers, rules, keepInPlace = setOf("com.android.camera"))

        assertEquals(listOf("Instagram", "YouTube"), kept.names(kept.appsWithRules))
        assertEquals(listOf("Calculator", "Camera", "Reddit", "TikTok"), kept.names(kept.otherApps))
        val camera = kept.otherApps[1]
        assertEquals("Delay 15s", camera.summary)
        assertTrue(camera.isOn)

        // Next visit (nothing kept): it has moved up, in name order.
        val next = sections(listOf(social), typicalMembers, rules)
        assertEquals(listOf("Camera", "Instagram", "YouTube"), next.names(next.appsWithRules))
    }

    @Test
    fun `the first app switched on keeps the first-run heading too`() {
        val rules = listOf(aBlockRule(id = 1, packageName = "com.android.camera", mode = "DELAY", delaySeconds = 15))
        val s = sections(rules = rules, keepInPlace = setOf("com.android.camera"))

        assertEquals("Apps", s.otherAppsTitle)
        assertTrue(s.appsWithRules.isEmpty())
        assertEquals(installed.map { it.appName }, s.names(s.otherApps))
    }

    // ── Rules for apps the launcher list does not have ─────────────────────

    @Test
    fun `a rule for an app not in the launcher list is still listed, once it has a name`() {
        val rules = listOf(aBlockRule(id = 1, packageName = "com.gone", mode = "HARD_BLOCK"))

        assertTrue(sections(rules = rules).appsWithRules.isEmpty())

        val s = sections(rules = rules, extraApps = mapOf("com.gone" to anApp("com.gone", "Gone")))
        assertEquals(listOf("Gone"), s.names(s.appsWithRules))
        assertEquals("Hard Block", s.appsWithRules.single().summary)
        assertTrue(s.otherApps.none { it.packageName == "com.gone" })
    }

    @Test
    fun `a group's rule never lands on an app row`() {
        val s = sections(listOf(social), typicalMembers, typicalRules.filter { it.groupId != null })
        assertTrue(s.appsWithRules.isEmpty())
        assertEquals(installed.size, s.otherApps.size)
    }

    // ── Category groups (spec 5.2) ─────────────────────────────────────────

    private val phone = listOf(
        anApp("com.insta", "Instagram", category = AppCategory.SOCIAL),
        anApp("com.tiktok", "TikTok", category = AppCategory.SOCIAL),
        anApp("com.dialer", "Phone", category = AppCategory.SOCIAL),
        anApp("com.chess", "Chess", category = AppCategory.GAME)
    )
    private val socialApps = anAppGroup("Social apps", id = 7, categories = "SOCIAL")

    @Test
    fun `a category group row counts its category apps, minus essentials and removed ones`() {
        val s = buildManageAppsSections(
            phone, listOf(socialApps), listOf(aGroupMember(7, "com.tiktok", excluded = true)),
            emptyList(), setOf("com.dialer"), query = "", browsers = emptySet()
        )

        assertEquals(listOf("Social apps · 1 app"), s.groups.map { it.title })
    }

    @Test
    fun `an app covered only by a blocking category group reads In that group`() {
        val rules = listOf(aBlockRule(id = 1, groupId = 7, mode = "HARD_BLOCK"))
        val s = buildManageAppsSections(phone, listOf(socialApps), emptyList(), rules, setOf("com.dialer"), query = "", browsers = emptySet())
        val byName = s.otherApps.associateBy { it.appInfo.appName }

        assertEquals("In Social apps", byName.getValue("Instagram").coveredBy)
        assertEquals(null, byName.getValue("Chess").coveredBy)
        // The essential app carries the category but is never covered.
        assertEquals(null, byName.getValue("Phone").coveredBy)
    }
}
