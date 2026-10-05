package com.astraedus.nudge.domain.redirect

import com.astraedus.nudge.NudgeIdentity
import com.astraedus.nudge.domain.redirect.RedirectAppPolicy.Candidate
import com.astraedus.nudge.domain.redirect.RedirectAppPolicy.RuleTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RedirectAppPolicy]: which app may be the "better app" on a block screen.
 *
 * The rule under test, everywhere: the redirect app can never be an app Nudge would block, because
 * tapping it would walk the user out of one block and into another one offering the same bubble.
 */
class RedirectAppPolicyTest {

    // The real applicationId, derived, never retyped (docs/testing-strategy.md rule (b)).
    private val own = NudgeIdentity.APPLICATION_ID
    private val instagram = "com.instagram.android"
    private val tiktok = "com.zhiliaoapp.musically"
    private val youtube = "com.google.android.youtube"
    private val wikipedia = "org.wikipedia"
    private val todo = "org.tasks"
    private val chrome = "com.android.chrome"

    // ── ruleTargets ──

    @Test
    fun `a direct rule targets its package and a group rule targets every member`() {
        val targets = RedirectAppPolicy.ruleTargets(
            rules = listOf(
                RuleTarget(packageName = instagram, groupId = null, enabled = true),
                RuleTarget(packageName = null, groupId = 7L, enabled = true)
            ),
            groupMembers = mapOf(7L to setOf(tiktok, youtube))
        )
        assertEquals(setOf(instagram, tiktok, youtube), targets)
    }

    @Test
    fun `a disabled rule targets nothing, and neither does a group with no members`() {
        val targets = RedirectAppPolicy.ruleTargets(
            rules = listOf(
                RuleTarget(instagram, null, enabled = false),
                RuleTarget(null, 3L, enabled = false),
                RuleTarget(null, 9L, enabled = true)
            ),
            groupMembers = mapOf(3L to setOf(tiktok))
        )
        assertTrue(targets.isEmpty())
    }

    @Test
    fun `a blank rule package is ignored rather than excluding the empty string`() {
        assertTrue(RedirectAppPolicy.ruleTargets(listOf(RuleTarget(" ", null, true)), emptyMap()).isEmpty())
    }

    // ── excluded ──

    @Test
    fun `Nudge, every rule target, the whole Nuke list and the block's own packages are excluded`() {
        val excluded = RedirectAppPolicy.excluded(
            ownPackage = own,
            ruleTargets = setOf(instagram),
            nukeList = setOf(tiktok),
            blockPackages = setOf(youtube, chrome)
        )
        assertEquals(setOf(own, instagram, tiktok, youtube, chrome), excluded)
    }

    @Test
    fun `blank packages never enter the excluded set`() {
        val excluded = RedirectAppPolicy.excluded(own, emptySet(), setOf(""), setOf("", " "))
        assertEquals(setOf(own), excluded)
    }

    // ── resolve: the bubble's state ──

    @Test
    fun `nothing saved is the empty state`() {
        assertNull(RedirectAppPolicy.resolve(saved = null, isLaunchable = true, excluded = setOf(own)))
        assertNull(RedirectAppPolicy.resolve(saved = "  ", isLaunchable = true, excluded = setOf(own)))
    }

    @Test
    fun `a saved, launchable, allowed app is shown`() {
        assertEquals(wikipedia, RedirectAppPolicy.resolve(wikipedia, isLaunchable = true, excluded = setOf(own)))
    }

    @Test
    fun `an uninstalled or unlaunchable saved app falls back to the empty state`() {
        assertNull(RedirectAppPolicy.resolve(wikipedia, isLaunchable = false, excluded = setOf(own)))
    }

    /** Chosen while allowed; a rule or a Nuke entry added afterwards must win. */
    @Test
    fun `a saved app that has since become blocked or nuked falls back to the empty state`() {
        val blockedLater = RedirectAppPolicy.excluded(own, ruleTargets = setOf(wikipedia), nukeList = emptySet())
        val nukedLater = RedirectAppPolicy.excluded(own, ruleTargets = emptySet(), nukeList = setOf(wikipedia))
        assertNull(RedirectAppPolicy.resolve(wikipedia, isLaunchable = true, excluded = blockedLater))
        assertNull(RedirectAppPolicy.resolve(wikipedia, isLaunchable = true, excluded = nukedLater))
    }

    /** A browser is a fine redirect from an app block and a bypass from a web block inside it. */
    @Test
    fun `the app the block is in is never offered as the way out of it`() {
        val appBlock = RedirectAppPolicy.excluded(own, setOf(instagram), emptySet(), setOf(instagram))
        val webBlockInChrome = RedirectAppPolicy.excluded(own, setOf(instagram), emptySet(), setOf(instagram, chrome))
        assertEquals(chrome, RedirectAppPolicy.resolve(chrome, true, appBlock))
        assertNull(RedirectAppPolicy.resolve(chrome, true, webBlockInChrome))
    }

    @Test
    fun `Nudge itself is never shown even if it were somehow saved`() {
        assertNull(RedirectAppPolicy.resolve(own, isLaunchable = true, excluded = RedirectAppPolicy.excluded(own, emptySet(), emptySet())))
    }

    // ── pickerRows ──

    private data class Row(val pkg: String, val label: String)
    private val toCandidate: (Row) -> Candidate = { Candidate(it.pkg, it.label) }

    private val installed = listOf(
        Row(todo, "Tasks"),
        Row(wikipedia, "Wikipedia"),
        Row(instagram, "Instagram"),
        Row(own, "Nudge"),
        Row(tiktok, "TikTok"),
        Row(chrome, "Chrome"),
        Row(wikipedia, "Wikipedia (duplicate launcher entry)")
    )

    @Test
    fun `the picker hides excluded apps, keeps one row per package and sorts by label`() {
        val rows = RedirectAppPolicy.pickerRows(
            installed,
            excluded = RedirectAppPolicy.excluded(own, setOf(instagram), setOf(tiktok)),
            query = "",
            candidate = toCandidate
        )
        assertEquals(listOf("Chrome", "Tasks", "Wikipedia"), rows.map { it.label })
    }

    @Test
    fun `search matches the label or the package, ignoring case and surrounding space`() {
        val excluded = setOf(own)
        assertEquals(
            listOf(wikipedia),
            RedirectAppPolicy.pickerRows(installed, excluded, "  WIKI ", toCandidate).map { it.pkg }
        )
        assertEquals(
            listOf(todo),
            RedirectAppPolicy.pickerRows(installed, excluded, "org.tasks", toCandidate).map { it.pkg }
        )
        assertTrue(RedirectAppPolicy.pickerRows(installed, excluded, "zzz", toCandidate).isEmpty())
    }

    /**
     * The class, not the instance: over every combination of rules, Nuke list and block packages
     * built from this fixture, no picker row and no resolved bubble is ever an excluded package.
     * That one property is what makes a redirect loop impossible.
     */
    @Test
    fun `no combination of rules, Nuke list and block can offer or show an app Nudge would block`() {
        val pool = installed.map { it.pkg }.distinct()
        val subsets = (0 until (1 shl pool.size)).map { mask ->
            pool.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
        }
        for (rules in subsets) {
            for (nuke in listOf(emptySet(), setOf(tiktok), setOf(wikipedia, todo))) {
                for (block in listOf(emptySet(), setOf(instagram), setOf(instagram, chrome))) {
                    val excluded = RedirectAppPolicy.excluded(own, rules, nuke, block)
                    val rows = RedirectAppPolicy.pickerRows(installed, excluded, "", toCandidate)
                    assertFalse(rows.any { it.pkg in excluded })
                    assertFalse(rows.any { it.pkg == own })
                    pool.forEach { saved ->
                        val shown = RedirectAppPolicy.resolve(saved, isLaunchable = true, excluded = excluded)
                        if (shown != null) {
                            assertFalse("$shown is excluded but was shown", shown in excluded)
                        }
                    }
                }
            }
        }
    }
}
