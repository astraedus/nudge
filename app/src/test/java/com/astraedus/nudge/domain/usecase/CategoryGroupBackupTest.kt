package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.dao.AppGroupDao
import com.astraedus.nudge.data.db.dao.BlockRuleDao
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.export.ExportedSettings
import com.astraedus.nudge.data.export.RuleExporter
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.domain.packages.EssentialPackages
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L4. Category groups through a whole backup (spec 5.2): the real [RuleExporter],
 * [ExportRulesUseCase], [ImportRulesUseCase] and [BlockRuleRepository] over an in-memory stand-in for
 * the two DAOs. Device facts (installed categories, essentials) differ per phone, which is the point:
 * `members` is the exporting phone's resolution, the definition is what travels.
 */
class CategoryGroupBackupTest {

    private val installedHere = mapOf(
        "com.insta" to AppCategory.SOCIAL,
        "com.tiktok" to AppCategory.SOCIAL,
        "com.phone" to AppCategory.SOCIAL, // essential on this device
        "com.game" to AppCategory.GAME,
        "com.misc" to AppCategory.UNDEFINED
    )
    private val essentialsHere = setOf("com.phone")

    private class FakeDb(firstId: Long = 1) {
        val rules = mutableListOf<BlockRule>()
        val groups = mutableListOf<AppGroup>()
        val members = mutableListOf<AppGroupMember>()
        private var nextId = firstId

        val repository: BlockRuleRepository = run {
            val ruleDao = mockk<BlockRuleDao>(relaxed = true)
            every { ruleDao.getAll() } answers { flowOf(rules.toList()) }
            coEvery { ruleDao.insert(any()) } answers {
                val row = firstArg<BlockRule>().copy(id = nextId++)
                rules += row
                row.id
            }
            val groupDao = mockk<AppGroupDao>(relaxed = true)
            every { groupDao.getAll() } answers { flowOf(groups.toList()) }
            coEvery { groupDao.insert(any()) } answers {
                val row = firstArg<AppGroup>().copy(id = nextId++)
                groups += row
                row.id
            }
            coEvery { groupDao.setCategories(any(), any()) } answers {
                val id = firstArg<Long>()
                val csv = secondArg<String?>()
                groups.replaceAll { if (it.id == id) it.copy(categories = csv) else it }
            }
            // REPLACE on the (groupId, packageName) primary key, as the real DAO does.
            coEvery { groupDao.addMember(any()) } answers {
                val row = firstArg<AppGroupMember>()
                members.removeAll { it.groupId == row.groupId && it.packageName == row.packageName }
                members += row
            }
            every { groupDao.getMembersForGroup(any()) } answers {
                val id = firstArg<Long>()
                flowOf(members.filter { it.groupId == id })
            }
            BlockRuleRepository(ruleDao, groupDao)
        }

        fun group(name: String, categories: String? = null, includes: List<String> = emptyList(), excludes: List<String> = emptyList()): Long {
            val id = nextId++
            groups += AppGroup(id = id, name = name, categories = categories)
            includes.forEach { members += AppGroupMember(id, it) }
            excludes.forEach { members += AppGroupMember(id, it, excluded = true) }
            return id
        }

        fun rows(name: String): Map<String, Boolean> {
            val id = groups.single { it.name == name }.id
            return members.filter { it.groupId == id }.associate { it.packageName to it.excluded }
        }
    }

    private fun installedRepo(installed: Map<String, AppCategory>) = mockk<InstalledAppsRepository> {
        coEvery { getInstalledApps() } returns installed.map { (pkg, category) ->
            InstalledAppsRepository.AppInfo(pkg, pkg, icon = null, category = category)
        }
    }

    private fun essentialsOf(set: Set<String>) = mockk<EssentialPackages> { coEvery { current() } returns set }

    private fun exportUseCase(db: FakeDb, installed: Map<String, AppCategory> = installedHere, essentials: Set<String> = essentialsHere) =
        ExportRulesUseCase(
            db.repository,
            mockk<UsageRepository> { coEvery { getAllEventsForExport() } returns emptyList() },
            mockk<NudgePreferences> { coEvery { exportableSettings() } returns ExportedSettings() },
            RuleExporter(),
            installedRepo(installed),
            essentialsOf(essentials)
        )

    private fun importUseCase(db: FakeDb, installed: Map<String, AppCategory> = installedHere, essentials: Set<String> = essentialsHere) =
        ImportRulesUseCase(
            db.repository,
            mockk<UsageRepository>(relaxed = true),
            mockk<NudgePreferences>(relaxed = true),
            RuleExporter(),
            installedRepo(installed),
            essentialsOf(essentials)
        )

    private suspend fun import(db: FakeDb, json: String, installed: Map<String, AppCategory> = installedHere): ImportOutcome {
        val useCase = importUseCase(db, installed)
        return useCase.execute(useCase.preview(json).result)
    }

    private suspend fun weakens(db: FakeDb, json: String, installed: Map<String, AppCategory> = installedHere): Boolean {
        val useCase = importUseCase(db, installed)
        return useCase.weakensProtection(useCase.preview(json).result)
    }

    private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }

    /** Social category + a hand-added UNDEFINED app + "Removed by you" tiktok. */
    private fun socialPhone() = FakeDb().apply {
        group("Social", categories = "SOCIAL", includes = listOf("com.misc"), excludes = listOf("com.tiktok"))
    }

    private fun groupJson(json: String, name: String = "Social"): JSONObject {
        val groups = JSONObject(json).getJSONArray("groups")
        return (0 until groups.length()).map { groups.getJSONObject(it) }.single { it.getString("name") == name }
    }

    // --- Export ---

    @Test
    fun `export carries the definition and members as the resolved snapshot`() = runTest {
        val json = exportUseCase(socialPhone()).invoke()
        val group = groupJson(json)

        assertEquals(1, JSONObject(json).getInt("version"))
        assertEquals(listOf("SOCIAL"), strings(group.getJSONArray("categories")))
        assertEquals(listOf("com.misc"), strings(group.getJSONArray("includes")))
        assertEquals(listOf("com.tiktok"), strings(group.getJSONArray("excluded")))
        // Category app present, hand-added UNDEFINED app present; essential, removed and other-category absent.
        assertEquals(setOf("com.insta", "com.misc"), strings(group.getJSONArray("members")).toSet())
    }

    @Test
    fun `an UNDEFINED app is not in members through a category`() = runTest {
        val db = FakeDb().apply { group("Everything", categories = "SOCIAL,GAME") }
        val members = strings(groupJson(exportUseCase(db).invoke(), "Everything").getJSONArray("members")).toSet()
        assertEquals(setOf("com.insta", "com.tiktok", "com.game"), members)
        assertFalse("com.misc" in members)
    }

    @Test
    fun `an older reader still sees name and members as plain strings`() = runTest {
        val group = groupJson(exportUseCase(socialPhone()).invoke())

        // What every shipped importer reads: `name` as a string, `members` as an array of strings.
        assertEquals("Social", group.getString("name"))
        val members = group.getJSONArray("members")
        assertTrue((0 until members.length()).all { members.get(it) is String })
        assertEquals(setOf("com.insta", "com.misc"), strings(members).toSet())
    }

    @Test
    fun `a hand-picked group exports with an empty categories list`() = runTest {
        val db = FakeDb().apply { group("Work", includes = listOf("com.slack")) }
        val group = groupJson(exportUseCase(db).invoke(), "Work")
        assertEquals(0, group.getJSONArray("categories").length())
        assertEquals(listOf("com.slack"), strings(group.getJSONArray("members")))
    }

    // --- Import ---

    @Test
    fun `an old format file imports its members as include rows`() = runTest {
        val db = FakeDb()
        val outcome = import(db, """{"version":1,"rules":[],"groups":[{"name":"Social","members":["com.insta","com.tiktok"]}]}""")

        assertNull(outcome.error)
        assertEquals(1, outcome.groupsCreated)
        assertEquals(mapOf("com.insta" to false, "com.tiktok" to false), db.rows("Social"))
        assertNull(db.groups.single().categories)
    }

    @Test
    fun `round trip recreates categories, includes and excludes, not members as includes`() = runTest {
        val json = exportUseCase(socialPhone()).invoke()

        val target = FakeDb(firstId = 100)
        val outcome = import(target, json)

        assertNull(outcome.error)
        assertEquals(1, outcome.groupsCreated)
        assertEquals("SOCIAL", target.groups.single().categories)
        assertEquals(mapOf("com.misc" to false, "com.tiktok" to true), target.rows("Social"))
        // com.insta was in `members` only because of the category: no frozen include row.
        assertFalse("com.insta" in target.rows("Social"))
    }

    @Test
    fun `importing the same file twice changes nothing the second time`() = runTest {
        val json = exportUseCase(socialPhone()).invoke()
        val target = FakeDb(firstId = 100)
        import(target, json)
        val groupsAfterFirst = target.groups.toList()
        val rowsAfterFirst = target.rows("Social")

        val second = import(target, json)

        assertEquals(0, second.groupsCreated)
        assertEquals(groupsAfterFirst, target.groups)
        assertEquals(rowsAfterFirst, target.rows("Social"))
    }

    @Test
    fun `merge unions categories into an existing group of the same name`() = runTest {
        val db = FakeDb().apply { group("Social", categories = "GAME") }
        import(db, """{"version":1,"rules":[],"groups":[{"name":"Social","members":[],"categories":["SOCIAL","NOPE","UNDEFINED"],"includes":[],"excluded":[]}]}""")
        assertEquals("SOCIAL,GAME".split(",").sorted(), db.groups.single().categories!!.split(",").sorted())
    }

    @Test
    fun `an exclude that would remove an existing member is skipped, and an include flips a removed row`() = runTest {
        val db = FakeDb().apply {
            group("Social", categories = "SOCIAL", includes = listOf("com.keep"), excludes = listOf("com.tiktok"))
        }
        import(
            db,
            """{"version":1,"rules":[],"groups":[{"name":"Social","members":[],"categories":["SOCIAL"],
               "includes":["com.tiktok"],"excluded":["com.insta","com.keep","com.game"]}]}"""
        )

        val rows = db.rows("Social")
        assertEquals(false, rows["com.tiktok"]) // flipped to an include
        assertFalse("com.insta" in rows) // would remove a category member: skipped
        assertEquals(false, rows["com.keep"]) // an include is never turned into an exclude
        assertEquals(true, rows["com.game"]) // installed, outside the group's categories: safe
    }

    @Test
    fun `a file with the new fields never writes members as includes into an existing group`() = runTest {
        val db = FakeDb().apply { group("Social", categories = "SOCIAL") }
        import(db, """{"version":1,"rules":[],"groups":[{"name":"Social","members":["com.insta","com.tiktok"],"categories":["SOCIAL"],"includes":[],"excluded":[]}]}""")
        assertTrue(db.rows("Social").isEmpty())
    }

    // --- Strict Mode gate ---

    private fun allowing(groupId: Long) =
        BlockRule(mode = "HARD_BLOCK", allApps = true, exceptGroupIds = "$groupId")

    private val addSocial =
        """{"version":1,"rules":[],"groups":[{"name":"Social","members":[],"categories":["SOCIAL"],"includes":[],"excluded":[]}]}"""

    @Test
    fun `a category merge that brings apps into an All apps allowed group is weakening`() = runTest {
        val db = FakeDb()
        val id = db.group("Social")
        db.rules += allowing(id).copy(id = 90)

        assertTrue(weakens(db, addSocial))
    }

    @Test
    fun `a category merge that resolves to no apps here is not weakening`() = runTest {
        val db = FakeDb()
        val id = db.group("Social")
        db.rules += allowing(id).copy(id = 90)

        // Same file, but this phone has no social apps installed, and the one that is is essential.
        assertFalse(weakens(db, addSocial, installed = mapOf("com.phone" to AppCategory.SOCIAL, "com.game" to AppCategory.GAME)))
    }

    @Test
    fun `the same category merge is free when no All apps rule allows the group`() = runTest {
        val db = FakeDb()
        db.group("Social")
        assertFalse(weakens(db, addSocial))
    }

    @Test
    fun `an exclude-only file never weakens`() = runTest {
        val db = FakeDb()
        val id = db.group("Social", categories = "SOCIAL")
        db.rules += allowing(id).copy(id = 90)
        assertFalse(
            weakens(db, """{"version":1,"rules":[],"groups":[{"name":"Social","members":[],"categories":[],"includes":[],"excluded":["com.insta"]}]}""")
        )
    }
}
