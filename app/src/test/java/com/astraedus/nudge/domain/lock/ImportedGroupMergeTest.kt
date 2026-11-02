package com.astraedus.nudge.domain.lock

import com.astraedus.nudge.data.export.ExportedGroup
import com.astraedus.nudge.domain.groups.GroupDefinition
import com.astraedus.nudge.domain.packages.AppCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/** L1. [ImportedGroupMerge]: a backup's group merged into a definition; rules only add. */
class ImportedGroupMergeTest {

    private val installed = mapOf(
        "com.insta" to AppCategory.SOCIAL,
        "com.tiktok" to AppCategory.SOCIAL,
        "com.game" to AppCategory.GAME,
        "com.misc" to AppCategory.UNDEFINED
    )

    private fun def(
        categories: Set<AppCategory> = emptySet(),
        includes: Set<String> = emptySet(),
        excludes: Set<String> = emptySet()
    ) = GroupDefinition(id = 5, categories = categories, includes = includes, excludes = excludes)

    private fun file(
        categories: List<String> = emptyList(),
        includes: List<String> = emptyList(),
        excluded: List<String> = emptyList(),
        members: List<String> = emptyList()
    ) = ExportedGroup("Social", members, categories, includes, excluded, hasDefinition = true)

    @Test
    fun `an old file's members are includes`() {
        val merged = ImportedGroupMerge.merge(
            def(),
            listOf(ExportedGroup("Social", listOf("com.insta", "com.tiktok"))),
            installed
        )
        assertEquals(setOf("com.insta", "com.tiktok"), merged.includes)
        assertEquals(emptySet<String>(), merged.categories)
    }

    @Test
    fun `a new file ignores members, which are the source device's resolution`() {
        val merged = ImportedGroupMerge.merge(
            def(),
            listOf(file(categories = listOf("SOCIAL"), includes = listOf("com.x"), members = listOf("com.insta", "com.x"))),
            installed
        )
        assertEquals(setOf(AppCategory.SOCIAL), merged.categories)
        assertEquals(setOf("com.x"), merged.includes)
    }

    @Test
    fun `unknown and UNDEFINED category names are skipped`() {
        val merged = ImportedGroupMerge.merge(
            def(),
            listOf(file(categories = listOf("SOCIAL", "HOLOGRAMS", "UNDEFINED", "ACCESSIBILITY"))),
            installed
        )
        assertEquals(setOf(AppCategory.SOCIAL), merged.categories)
    }

    @Test
    fun `categories are unioned with the existing group's`() {
        val merged = ImportedGroupMerge.merge(def(categories = setOf(AppCategory.GAME)), listOf(file(categories = listOf("SOCIAL"))), installed)
        assertEquals(setOf(AppCategory.GAME, AppCategory.SOCIAL), merged.categories)
    }

    @Test
    fun `an incoming include flips an existing removed-by-you row to an include`() {
        val merged = ImportedGroupMerge.merge(
            def(categories = setOf(AppCategory.SOCIAL), excludes = setOf("com.insta")),
            listOf(file(includes = listOf("com.insta"))),
            installed
        )
        assertEquals(setOf("com.insta"), merged.includes)
        assertEquals(emptySet<String>(), merged.excludes)
    }

    @Test
    fun `an exclude that would remove a current category member is skipped`() {
        val merged = ImportedGroupMerge.merge(
            def(categories = setOf(AppCategory.SOCIAL)),
            listOf(file(excluded = listOf("com.insta"))),
            installed
        )
        assertEquals(emptySet<String>(), merged.excludes)
    }

    @Test
    fun `an exclude of a package not installed here is skipped when the group has categories`() {
        // Its category is unknown here, so it could join later.
        val merged = ImportedGroupMerge.merge(
            def(categories = setOf(AppCategory.SOCIAL)),
            listOf(file(excluded = listOf("com.notinstalled"))),
            installed
        )
        assertEquals(emptySet<String>(), merged.excludes)
    }

    @Test
    fun `an exclude of an installed app outside the group's categories is written`() {
        val merged = ImportedGroupMerge.merge(
            def(categories = setOf(AppCategory.SOCIAL)),
            listOf(file(excluded = listOf("com.game"))),
            installed
        )
        assertEquals(setOf("com.game"), merged.excludes)
    }

    @Test
    fun `an exclude is written on a group without categories, unless the package is an include`() {
        val merged = ImportedGroupMerge.merge(
            def(includes = setOf("com.insta")),
            listOf(file(excluded = listOf("com.insta", "com.tiktok"))),
            installed
        )
        assertEquals(setOf("com.tiktok"), merged.excludes)
        assertEquals(setOf("com.insta"), merged.includes)
    }

    @Test
    fun `a package in both lists of the file is an include`() {
        val merged = ImportedGroupMerge.merge(
            def(),
            listOf(file(includes = listOf("com.insta"), excluded = listOf("com.insta"))),
            installed
        )
        assertEquals(setOf("com.insta"), merged.includes)
        assertEquals(emptySet<String>(), merged.excludes)
    }

    @Test
    fun `a new group keeps the excludes it was exported with`() {
        val merged = ImportedGroupMerge.merge(
            def(),
            listOf(file(categories = listOf("SOCIAL"), excluded = listOf("com.insta"))),
            installed
        )
        assertEquals(setOf(AppCategory.SOCIAL), merged.categories)
        assertEquals(setOf("com.insta"), merged.excludes)
    }
}
