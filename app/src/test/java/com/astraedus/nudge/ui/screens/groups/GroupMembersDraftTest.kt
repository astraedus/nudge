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
        assertEquals(setOf("com.chat", "com.mail"), social.members(installed, essentials, browsers = emptySet()))
    }

    @Test fun `the x on a category app moves it to removed by you`() {
        val after = social.remove("com.chat", AppCategory.SOCIAL, browser = false)
        assertEquals(setOf("com.chat"), after.excludes)
        assertEquals(setOf("com.mail"), after.members(installed, essentials, browsers = emptySet()))
        assertEquals(setOf("com.chat"), after.removedByYou(installed, essentials, browsers = emptySet()))
    }

    @Test fun `the x on an app added by hand just drops it`() {
        val draft = GroupMembersDraft(includes = setOf("com.game"))
        val after = draft.remove("com.game", AppCategory.GAME, browser = false)
        assertEquals(GroupMembersDraft(), after)
    }

    @Test fun `the x on an app both added by hand and in a checked category removes it for good`() {
        val draft = social.copy(includes = setOf("com.chat"))
        val after = draft.remove("com.chat", AppCategory.SOCIAL, browser = false)
        assertFalse("com.chat" in after.members(installed, essentials, browsers = emptySet()))
        assertTrue(after.includes.intersect(after.excludes).isEmpty())
    }

    @Test fun `add back puts it in again`() {
        val after = social.remove("com.chat", AppCategory.SOCIAL, browser = false).addBack("com.chat")
        assertEquals(social, after)
        assertEquals(setOf("com.chat", "com.mail"), after.members(installed, essentials, browsers = emptySet()))
    }

    @Test fun `picking a removed app in Add apps restores it rather than adding a row`() {
        val removed = social.remove("com.chat", AppCategory.SOCIAL, browser = false)
        val pickable = installed.keys - essentials
        val picked = setOf("com.chat", "com.mail")
        val after = removed.afterPick(picked, pickable, installed, essentials, browsers = emptySet())
        assertEquals(social, after)
    }

    @Test fun `picking an app no checked category brings in adds it by hand`() {
        val pickable = installed.keys - essentials
        val after = social.afterPick(setOf("com.chat", "com.mail", "com.game", "com.nocat"), pickable, installed, essentials, browsers = emptySet())
        assertEquals(setOf("com.game", "com.nocat"), after.includes)
        assertEquals(emptySet<String>(), after.excludes)
    }

    @Test fun `unpicking a category app in Add apps is the same as its x`() {
        val pickable = installed.keys - essentials
        val after = social.afterPick(setOf("com.mail"), pickable, installed, essentials, browsers = emptySet())
        assertEquals(setOf("com.chat"), after.excludes)
    }

    @Test fun `a member the picker could not show is left alone`() {
        // An uninstalled hand-added app is not pickable; it must not vanish unseen.
        val draft = GroupMembersDraft(includes = setOf("com.gone"))
        val after = draft.afterPick(emptySet(), installed.keys - essentials, installed, essentials, browsers = emptySet())
        assertEquals(draft, after)
    }

    @Test fun `removed by you lists only apps a checked category would bring in`() {
        val draft = social.remove("com.chat", AppCategory.SOCIAL, browser = false).copy(excludes = setOf("com.chat", "com.game", "com.gone", "com.dialer"))
        assertEquals(setOf("com.chat"), draft.removedByYou(installed, essentials, browsers = emptySet()))
        // Unchecking Social hides the row but keeps the choice: checking it again keeps com.chat out.
        val unchecked = draft.toggleCategory(AppCategory.SOCIAL)
        assertEquals(emptySet<String>(), unchecked.removedByYou(installed, essentials, browsers = emptySet()))
        assertFalse("com.chat" in unchecked.toggleCategory(AppCategory.SOCIAL).members(installed, essentials, browsers = emptySet()))
    }

    @Test fun `chip counts are installed apps in the category minus essentials and removed`() {
        val draft = social.remove("com.chat", AppCategory.SOCIAL, browser = false)
        assertEquals(1, draft.categoryCount(AppCategory.SOCIAL, installed, essentials, browsers = emptySet()))
        assertEquals(1, draft.categoryCount(AppCategory.VIDEO, installed, essentials, browsers = emptySet()))
        assertEquals(0, draft.categoryCount(AppCategory.NEWS, installed, essentials, browsers = emptySet()))
    }

    @Test fun `every offered category is a chip in offered order, even with no app here`() {
        // NEWS has no app on this phone: still offered, so the next one installed can be pre-blocked.
        val chips = GroupMembersDraft(categories = setOf(AppCategory.NEWS)).chips(installed, essentials, browsers = emptySet())
        assertEquals(OFFERED_CATEGORIES, chips.map { it.category })
        val news = chips.single { it.category == AppCategory.NEWS }
        assertEquals(0, news.count)
        assertTrue(news.checked)
        assertEquals(listOf(AppCategory.NEWS), chips.filter { it.checked }.map { it.category })
    }

    @Test fun `a chip whose apps are all removed by you stays offered, at 0`() {
        val draft = social.remove("com.chat", AppCategory.SOCIAL, browser = false).remove("com.mail", AppCategory.SOCIAL, browser = false)
        val chip = draft.chips(installed, essentials, browsers = emptySet()).single { it.category == AppCategory.SOCIAL }
        assertEquals(0, chip.count)
        assertTrue(chip.checked)
    }

    @Test fun `no chip is ever UNDEFINED, and UNDEFINED cannot be checked`() {
        val everything = installed + ("com.x" to AppCategory.UNDEFINED)
        assertFalse(GroupMembersDraft().chips(everything, emptySet(), browsers = emptySet()).any { it.category == AppCategory.UNDEFINED })
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


    // ── Browsers: never by category, only by hand ─────────────────────────────────────────────

    private val chrome = "com.android.chrome"
    private val withChrome = installed + (chrome to AppCategory.SOCIAL)
    private val browsers = setOf(chrome)

    @Test fun `checking Social does not bring in a browser that reports SOCIAL, and the chip does not count it`() {
        assertEquals(setOf("com.chat", "com.mail"), social.members(withChrome, essentials, browsers))
        assertEquals(2, social.categoryCount(AppCategory.SOCIAL, withChrome, essentials, browsers))
        assertEquals(2, social.chips(withChrome, essentials, browsers).single { it.category == AppCategory.SOCIAL }.count)
    }

    @Test fun `picking a browser in a Social group adds it by hand, and the x drops that, never Removed by you`() {
        val picked = social.afterPick(setOf("com.chat", "com.mail", chrome), withChrome.keys - essentials, withChrome, essentials, browsers)
        assertEquals(setOf(chrome), picked.includes)
        assertTrue(chrome in picked.members(withChrome, essentials, browsers))

        val removed = picked.remove(chrome, AppCategory.SOCIAL, browser = true)
        assertEquals(emptySet<String>(), removed.includes)
        assertEquals(emptySet<String>(), removed.excludes)
        assertEquals(emptySet<String>(), removed.removedByYou(withChrome, essentials, browsers))
        assertFalse(chrome in removed.members(withChrome, essentials, browsers))
    }
}
