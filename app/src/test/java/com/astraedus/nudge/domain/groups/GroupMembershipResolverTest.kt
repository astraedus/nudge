package com.astraedus.nudge.domain.groups

import com.astraedus.nudge.domain.packages.AppCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1: the membership formula (spec 5.2) and its precedence. Every reader of group membership goes
 * through [GroupMembershipResolver], so these cases are the contract for all of them.
 */
class GroupMembershipResolverTest {

    private val social = GroupDefinition(
        id = 1,
        categories = setOf(AppCategory.SOCIAL),
        includes = setOf("com.handpicked"),
        excludes = setOf("com.removed")
    )
    private val resolver = GroupMembershipResolver(listOf(social))

    @Test fun `an app whose category is checked is a member`() {
        assertTrue(resolver.isMember(1, "com.chat", AppCategory.SOCIAL, essential = false, browser = false))
        assertEquals(setOf(1L), resolver.groupIdsOf("com.chat", AppCategory.SOCIAL, browser = false))
    }

    @Test fun `an app in another category is not`() {
        assertFalse(resolver.isMember(1, "com.game", AppCategory.GAME, essential = false, browser = false))
        assertEquals(emptySet<Long>(), resolver.groupIdsOf("com.game", AppCategory.GAME, browser = false))
    }

    @Test fun `an app added by hand is a member whatever its category`() {
        assertTrue(resolver.isMember(1, "com.handpicked", AppCategory.GAME, essential = false, browser = false))
        assertTrue(resolver.isMember(1, "com.handpicked", null, essential = false, browser = false))
        assertEquals(setOf(1L), resolver.groupIdsOf("com.handpicked", null, browser = false))
    }

    @Test fun `removed by you beats the category`() {
        assertFalse(resolver.isMember(1, "com.removed", AppCategory.SOCIAL, essential = false, browser = false))
        assertEquals(emptySet<Long>(), resolver.groupIdsOf("com.removed", AppCategory.SOCIAL, browser = false))
    }

    @Test fun `an essential app is never a member, however it would match`() {
        val handPickedEssential = GroupDefinition(2, setOf(AppCategory.SOCIAL), includes = setOf("com.dialer"))
        for (category in AppCategory.entries + listOf(null)) {
            assertFalse(GroupMembershipResolver.isMember(handPickedEssential, "com.dialer", category, essential = true, browser = false))
            assertFalse(GroupMembershipResolver.isMember(handPickedEssential, "com.chat", category, essential = true, browser = false))
        }
    }

    @Test fun `UNDEFINED never matches, even in a definition that holds it`() {
        // A definition can only hold UNDEFINED through a bug or a hand-edited row; it still matches nothing.
        val corrupt = GroupDefinition(3, categories = AppCategory.entries.toSet())
        val r = GroupMembershipResolver(listOf(corrupt))
        assertFalse(r.isMember(3, "com.nocat", AppCategory.UNDEFINED, essential = false, browser = false))
        assertEquals(emptySet<Long>(), r.groupIdsOf("com.nocat", AppCategory.UNDEFINED, browser = false))
        assertFalse(GroupMembershipResolver.matchesCategory(corrupt, AppCategory.UNDEFINED, browser = false))
    }

    @Test fun `a null category matches only by hand`() {
        assertFalse(resolver.isMember(1, "com.chat", null, essential = false, browser = false))
        assertEquals(emptySet<Long>(), resolver.groupIdsOf("com.chat", null, browser = false))
    }

    @Test fun `groupIdsOf finds every group, by hand or by category, minus the ones that removed it`() {
        val video = GroupDefinition(4, setOf(AppCategory.VIDEO), includes = setOf("com.chat"))
        val removesChat = GroupDefinition(5, setOf(AppCategory.SOCIAL), excludes = setOf("com.chat"))
        val r = GroupMembershipResolver(listOf(social, video, removesChat))
        assertEquals(setOf(1L, 4L), r.groupIdsOf("com.chat", AppCategory.SOCIAL, browser = false))
    }

    @Test fun `membersOf resolves includes, installed category apps, minus removed and essentials`() {
        val installed = mapOf(
            "com.chat" to AppCategory.SOCIAL,
            "com.mail" to AppCategory.SOCIAL,
            "com.removed" to AppCategory.SOCIAL,
            "com.dialer" to AppCategory.SOCIAL,
            "com.game" to AppCategory.GAME,
            "com.nocat" to AppCategory.UNDEFINED
        )
        assertEquals(
            setOf("com.handpicked", "com.chat", "com.mail"),
            resolver.membersOf(1, installed, essentials = setOf("com.dialer"), browsers = emptySet())
        )
    }

    @Test fun `a hand-added app that is not installed stays a member`() {
        assertEquals(setOf("com.handpicked"), resolver.membersOf(1, emptyMap(), emptySet(), browsers = emptySet()))
    }

    @Test fun `a stale group id has no members and uses no categories`() {
        assertEquals(emptySet<String>(), resolver.membersOf(99, mapOf("com.chat" to AppCategory.SOCIAL), emptySet(), browsers = emptySet()))
        assertFalse(resolver.isMember(99, "com.chat", AppCategory.SOCIAL, essential = false, browser = false))
        assertFalse(resolver.usesCategories(listOf(99L)))
    }

    @Test fun `usesCategories is true only for a group that checks a real category`() {
        val handPicked = GroupDefinition(6, includes = setOf("com.a"))
        val onlyUndefined = GroupDefinition(7, categories = setOf(AppCategory.UNDEFINED))
        val r = GroupMembershipResolver(listOf(social, handPicked, onlyUndefined))
        assertTrue(r.usesCategories)
        assertTrue(r.usesCategories(listOf(1L)))
        assertFalse(r.usesCategories(listOf(6L, 7L)))
        assertFalse(GroupMembershipResolver(listOf(handPicked, onlyUndefined)).usesCategories)
        assertFalse(GroupMembershipResolver.NONE.usesCategories)
    }

    @Test fun `the CSV round-trips every offered category and drops unknown names and UNDEFINED`() {
        assertEquals(OFFERED_CATEGORIES.toSet(), AppCategoryCsv.parse(AppCategoryCsv.format(OFFERED_CATEGORIES.toSet())))
        assertEquals(setOf(AppCategory.SOCIAL, AppCategory.GAME), AppCategoryCsv.parse("SOCIAL, UNDEFINED,FUTURE_THING,GAME"))
        assertEquals(emptySet<AppCategory>(), AppCategoryCsv.parse(null))
        assertEquals(emptySet<AppCategory>(), AppCategoryCsv.parse(""))
        assertNull(AppCategoryCsv.format(emptySet()))
        assertNull(AppCategoryCsv.format(setOf(AppCategory.UNDEFINED)))
        assertNull(AppCategoryCsv.fromName("UNDEFINED"))
    }

    @Test fun `the CSV is written in offered order whatever the set order`() {
        assertEquals("SOCIAL,GAME", AppCategoryCsv.format(linkedSetOf(AppCategory.GAME, AppCategory.SOCIAL)))
    }

    @Test fun `UNDEFINED is never offered`() {
        assertFalse(AppCategory.UNDEFINED in OFFERED_CATEGORIES)
        assertEquals(OFFERED_CATEGORIES.size, OFFERED_CATEGORIES.toSet().size)
        assertEquals(AppCategory.entries.toSet() - AppCategory.UNDEFINED, OFFERED_CATEGORIES.toSet())
    }


    // ── A browser never joins by category (Chrome reports SOCIAL, Firefox PRODUCTIVITY) ──────

    @Test fun `a browser whose category is checked is not a member`() {
        assertFalse(resolver.isMember(1, "com.android.chrome", AppCategory.SOCIAL, essential = false, browser = true))
        assertEquals(emptySet<Long>(), resolver.groupIdsOf("com.android.chrome", AppCategory.SOCIAL, browser = true))
        assertFalse(GroupMembershipResolver.matchesCategory(social, AppCategory.SOCIAL, browser = true))
        assertEquals(
            setOf("com.handpicked", "com.chat"),
            resolver.membersOf(
                1,
                mapOf("com.chat" to AppCategory.SOCIAL, "com.android.chrome" to AppCategory.SOCIAL),
                essentials = emptySet(),
                browsers = setOf("com.android.chrome")
            )
        )
    }

    @Test fun `a browser added by hand is a member`() {
        val withChrome = GroupMembershipResolver(listOf(social.copy(includes = setOf("com.android.chrome"))))
        assertTrue(withChrome.isMember(1, "com.android.chrome", AppCategory.SOCIAL, essential = false, browser = true))
        assertTrue(withChrome.isMember(1, "com.android.chrome", null, essential = false, browser = true))
        assertEquals(setOf(1L), withChrome.groupIdsOf("com.android.chrome", AppCategory.SOCIAL, browser = true))
        assertEquals(
            setOf("com.android.chrome"),
            withChrome.membersOf(
                1,
                mapOf("com.android.chrome" to AppCategory.SOCIAL),
                essentials = emptySet(),
                browsers = setOf("com.android.chrome")
            )
        )
    }

    @Test fun `Removed by you still beats a hand-added browser`() {
        val both = GroupMembershipResolver(
            listOf(social.copy(includes = setOf("com.android.chrome"), excludes = setOf("com.android.chrome")))
        )
        assertFalse(both.isMember(1, "com.android.chrome", AppCategory.SOCIAL, essential = false, browser = true))
    }
}
