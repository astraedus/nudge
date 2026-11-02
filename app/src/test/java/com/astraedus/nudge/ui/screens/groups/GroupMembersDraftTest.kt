package com.astraedus.nudge.ui.screens.groups

import com.astraedus.nudge.domain.groups.GroupDefinition
import com.astraedus.nudge.domain.groups.OFFERED_CATEGORIES
import com.astraedus.nudge.domain.packages.AppCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** L1: every edit the group editor makes to who is in a group (spec 3.3). */
class GroupMembersDraftTest {

    private val installed = mapOf(
        "com.chat" to AppCategory.SOCIAL,
        "com.mail" to AppCategory.SOCIAL,
        "com.dialer" to AppCategory.SOCIAL,
        "com.tube" to AppCategory.VIDEO,
        "com.game" to AppCategory.GAME,
        "com.nocat" to AppCategory.UNDEFINED
    )
    private val essentials = setOf("com.dialer")
    private val social = GroupMembersDraft(categories = setOf(AppCategory.SOCIAL))

    @Test fun `checking a category brings in its installed apps, never an essential`() {
        assertEquals(setOf("com.chat", "com.mail"), social.members(installed, essentials))
    }

    @Test fun `the x on a category app moves it to removed by you`() {
        val after = social.remove("com.chat", AppCategory.SOCIAL)
        assertEquals(setOf("com.chat"), after.excludes)
        assertEquals(setOf("com.mail"), after.members(installed, essentials))
        assertEquals(setOf("com.chat"), after.removedByYou(installed, essentials))
    }

    @Test fun `the x on an app added by hand just drops it`() {
        val draft = GroupMembersDraft(includes = setOf("com.game"))
        val after = draft.remove("com.game", AppCategory.GAME)
        assertEquals(GroupMembersDraft(), after)
    }

    @Test fun `the x on an app both added by hand and in a checked category removes it for good`() {
        val draft = social.copy(includes = setOf("com.chat"))
        val after = draft.remove("com.chat", AppCategory.SOCIAL)
        assertFalse("com.chat" in after.members(installed, essentials))
        assertTrue(after.includes.intersect(after.excludes).isEmpty())
    }

    @Test fun `add back puts it in again`() {
        val after = social.remove("com.chat", AppCategory.SOCIAL).addBack("com.chat")
        assertEquals(social, after)
        assertEquals(setOf("com.chat", "com.mail"), after.members(installed, essentials))
    }

    @Test fun `picking a removed app in Add apps restores it rather than adding a row`() {
        val removed = social.remove("com.chat", AppCategory.SOCIAL)
        val pickable = installed.keys - essentials
        val picked = setOf("com.chat", "com.mail")
        val after = removed.afterPick(picked, pickable, installed, essentials)
        assertEquals(social, after)
    }

    @Test fun `picking an app no checked category brings in adds it by hand`() {
        val pickable = installed.keys - essentials
        val after = social.afterPick(setOf("com.chat", "com.mail", "com.game", "com.nocat"), pickable, installed, essentials)
        assertEquals(setOf("com.game", "com.nocat"), after.includes)
        assertEquals(emptySet<String>(), after.excludes)
    }

    @Test fun `unpicking a category app in Add apps is the same as its x`() {
        val pickable = installed.keys - essentials
        val after = social.afterPick(setOf("com.mail"), pickable, installed, essentials)
        assertEquals(setOf("com.chat"), after.excludes)
    }

    @Test fun `a member the picker could not show is left alone`() {
        // An uninstalled hand-added app is not pickable; it must not vanish unseen.
        val draft = GroupMembersDraft(includes = setOf("com.gone"))
        val after = draft.afterPick(emptySet(), installed.keys - essentials, installed, essentials)
        assertEquals(draft, after)
    }

    @Test fun `removed by you lists only apps a checked category would bring in`() {
        val draft = social.remove("com.chat", AppCategory.SOCIAL).copy(excludes = setOf("com.chat", "com.game", "com.gone", "com.dialer"))
        assertEquals(setOf("com.chat"), draft.removedByYou(installed, essentials))
        // Unchecking Social hides the row but keeps the choice: checking it again keeps com.chat out.
        val unchecked = draft.toggleCategory(AppCategory.SOCIAL)
        assertEquals(emptySet<String>(), unchecked.removedByYou(installed, essentials))
        assertFalse("com.chat" in unchecked.toggleCategory(AppCategory.SOCIAL).members(installed, essentials))
    }

    @Test fun `chip counts are installed apps in the category minus essentials and removed`() {
        val draft = social.remove("com.chat", AppCategory.SOCIAL)
        assertEquals(1, draft.categoryCount(AppCategory.SOCIAL, installed, essentials))
        assertEquals(1, draft.categoryCount(AppCategory.VIDEO, installed, essentials))
        assertEquals(0, draft.categoryCount(AppCategory.NEWS, installed, essentials))
    }

    @Test fun `chips offered are the categories with an app here, plus any checked one, in offered order`() {
        assertEquals(
            listOf(AppCategory.SOCIAL, AppCategory.VIDEO, AppCategory.GAME),
            GroupMembersDraft().offeredCategories(installed, essentials)
        )
        val newsChecked = GroupMembersDraft(categories = setOf(AppCategory.NEWS))
        assertEquals(
            listOf(AppCategory.SOCIAL, AppCategory.VIDEO, AppCategory.GAME, AppCategory.NEWS),
            newsChecked.offeredCategories(installed, essentials)
        )
    }

    @Test fun `no chip is ever UNDEFINED, and UNDEFINED cannot be checked`() {
        val everything = installed + ("com.x" to AppCategory.UNDEFINED)
        assertFalse(AppCategory.UNDEFINED in GroupMembersDraft().offeredCategories(everything, emptySet()))
        val draft = GroupMembersDraft()
        assertSame(draft, draft.toggleCategory(AppCategory.UNDEFINED))
        for (category in OFFERED_CATEGORIES) {
            assertEquals(setOf(category), draft.toggleCategory(category).categories)
        }
    }

    @Test fun `of and definition round-trip`() {
        val def = GroupDefinition(7, setOf(AppCategory.GAME), setOf("com.a"), setOf("com.b"))
        assertEquals(def, GroupMembersDraft.of(def).definition(7))
    }
}
