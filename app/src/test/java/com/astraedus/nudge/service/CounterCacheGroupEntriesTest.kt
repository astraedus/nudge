package com.astraedus.nudge.service

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.RuleSnapshot
import com.astraedus.nudge.domain.groups.AppCategoryCsv
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.ExceptCsv
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.domain.web.WebSessionKey
import com.astraedus.nudge.testing.aBlockRule
import com.astraedus.nudge.testing.aGroupMember
import com.astraedus.nudge.testing.anAppGroup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * L1 (pure): which packages the counter cache holds an entry for when a GROUP or ALL APPS rule
 * carries a clock feature, issue [#146](https://github.com/astraedus/nudge/issues/146).
 *
 * The bug: the cache was built only from rules that name a package, so a group's members got no
 * entry, no foreground clock started, and a group's daily limit was enforced only at the next open.
 * Every test here builds a real [RuleSnapshot] (the production mapper and membership resolver) and
 * reads [CounterCacheCoverage], the function the service's loader calls.
 *
 * Counterfactual: with [CounterCacheCoverage.appEntries] swapped back to the pre-fix
 * `enabledRules.mapNotNull(CounterCacheRefresher::appEntryFor)`, every test asserting a group or All
 * apps member HAS an entry fails (the member has none).
 */
class CounterCacheGroupEntriesTest {

    private val instagram = "com.instagram.android"
    private val tiktok = "com.zhiliaoapp.musically"
    private val reddit = "com.reddit.frontpage"
    private val maps = "com.google.android.apps.maps"
    private val chrome = "com.android.chrome"
    private val dialer = "com.google.android.dialer"

    private val social = 7L
    private val scrolling = 8L

    private val socialCsv: String? = AppCategoryCsv.format(setOf(AppCategory.SOCIAL))

    private fun snapshot(
        rules: List<BlockRule>,
        groups: List<AppGroup> = emptyList(),
        members: List<AppGroupMember> = emptyList()
    ) = RuleSnapshot.of(enabledRules = rules, groups = groups, members = members)

    /** Everything installed with a launcher entry, as InstalledAppsRepository lists it. */
    private val device = CounterCacheDevice(
        launchable = mapOf(
            instagram to AppCategory.SOCIAL,
            tiktok to AppCategory.SOCIAL,
            reddit to AppCategory.SOCIAL,
            maps to AppCategory.MAPS,
            chrome to AppCategory.SOCIAL, // a browser that reports a matching category
            dialer to AppCategory.UNDEFINED
        ),
        essentials = setOf(dialer),
        browsers = setOf(chrome)
    )

    private fun entries(snapshot: RuleSnapshot, device: CounterCacheDevice = this.device) =
        CounterCacheRefresher.mergeEntries(CounterCacheCoverage.appEntries(snapshot, device))

    private fun groupLimit(groupId: Long, minutes: Int?, id: Long = groupId, enabled: Boolean = true) =
        aBlockRule(
            id = id,
            groupId = groupId,
            mode = BlockMode.HARD_BLOCK.name,
            dailyLimitMinutes = minutes,
            enabled = enabled
        )

    // ── group members ──

    @Test
    fun `every hand-added member of a group with a daily limit gets an entry that ticks`() {
        val cache = entries(
            snapshot(
                rules = listOf(groupLimit(social, 2)),
                groups = listOf(anAppGroup("Social", id = social)),
                members = listOf(aGroupMember(social, instagram), aGroupMember(social, tiktok))
            )
        )

        assertEquals(setOf(instagram, tiktok), cache.keys)
        for (pkg in listOf(instagram, tiktok)) {
            val entry = cache.getValue(pkg)
            assertEquals(2, entry.dailyLimitMinutes)
            assertTrue("$pkg needs the clock", entry.needsForegroundTimeTick)
        }
    }

    @Test
    fun `a member Removed by you from a category group gets no entry`() {
        val cache = entries(
            snapshot(
                rules = listOf(groupLimit(social, 30)),
                groups = listOf(anAppGroup("Social", id = social, categories = socialCsv)),
                members = listOf(aGroupMember(social, reddit, excluded = true))
            )
        )

        assertFalse(reddit in cache)
        assertTrue(instagram in cache)
    }

    @Test
    fun `a category group covers launchable apps of its category, never another category or a browser`() {
        val cache = entries(
            snapshot(
                rules = listOf(groupLimit(social, 30)),
                groups = listOf(anAppGroup("Social", id = social, categories = socialCsv))
            )
        )

        assertEquals(setOf(instagram, tiktok, reddit), cache.keys)
        assertFalse("other category", maps in cache)
        assertFalse("a browser joins by hand only", chrome in cache)
    }

    @Test
    fun `an essential app never gets an entry from a group, but a direct rule on it still does`() {
        val groupOnly = entries(
            snapshot(
                rules = listOf(groupLimit(social, 30)),
                groups = listOf(anAppGroup("Social", id = social)),
                members = listOf(aGroupMember(social, dialer), aGroupMember(social, instagram))
            )
        )
        assertFalse("essential under a group", dialer in groupOnly)
        assertTrue(instagram in groupOnly)

        val withDirect = entries(
            snapshot(
                rules = listOf(
                    groupLimit(social, 30),
                    aBlockRule(id = 2, packageName = dialer, dailyLimitMinutes = 90)
                ),
                groups = listOf(anAppGroup("Social", id = social)),
                members = listOf(aGroupMember(social, dialer))
            )
        )
        assertEquals("the user's explicit rule on an essential is honoured", 90, withDirect[dialer]?.dailyLimitMinutes)
    }

    @Test
    fun `a browser added to a group by hand is covered like any member`() {
        val cache = entries(
            snapshot(
                rules = listOf(groupLimit(social, 30)),
                groups = listOf(anAppGroup("Social", id = social)),
                members = listOf(aGroupMember(social, chrome))
            )
        )
        assertEquals(30, cache[chrome]?.dailyLimitMinutes)
    }

    // ── strictest wins ──

    @Test
    fun `a stricter direct limit wins over the group's`() {
        val cache = entries(
            snapshot(
                rules = listOf(
                    groupLimit(social, 30),
                    aBlockRule(id = 2, packageName = instagram, dailyLimitMinutes = 10)
                ),
                groups = listOf(anAppGroup("Social", id = social)),
                members = listOf(aGroupMember(social, instagram))
            )
        )
        assertEquals(10, cache[instagram]?.dailyLimitMinutes)
    }

    @Test
    fun `a stricter group limit wins over a direct one`() {
        val cache = entries(
            snapshot(
                rules = listOf(
                    groupLimit(social, 10),
                    aBlockRule(id = 2, packageName = instagram, dailyLimitMinutes = 30, showTimeRemaining = true)
                ),
                groups = listOf(anAppGroup("Social", id = social)),
                members = listOf(aGroupMember(social, instagram))
            )
        )
        val entry = cache.getValue(instagram)
        assertEquals(10, entry.dailyLimitMinutes)
        assertTrue("the direct rule's readout is kept (any overlay asked for)", entry.showTimeRemaining)
    }

    @Test
    fun `an app in two limited groups takes the lower limit`() {
        val cache = entries(
            snapshot(
                rules = listOf(groupLimit(social, 45), groupLimit(scrolling, 20)),
                groups = listOf(anAppGroup("Social", id = social), anAppGroup("Scrolling", id = scrolling)),
                members = listOf(aGroupMember(social, tiktok), aGroupMember(scrolling, tiktok))
            )
        )
        assertEquals(20, cache[tiktok]?.dailyLimitMinutes)
    }

    // ── rules that contribute nothing ──

    @Test
    fun `a disabled group rule contributes nothing`() {
        val cache = entries(
            snapshot(
                rules = listOf(groupLimit(social, 30, enabled = false)),
                groups = listOf(anAppGroup("Social", id = social)),
                members = listOf(aGroupMember(social, instagram))
            )
        )
        assertTrue(cache.isEmpty())
    }

    @Test
    fun `a group rule with no clock feature gets no entry and reads no device list`() {
        val delayOnly = snapshot(
            rules = listOf(aBlockRule(groupId = social, mode = BlockMode.DELAY.name)),
            groups = listOf(anAppGroup("Social", id = social, categories = socialCsv)),
            members = listOf(aGroupMember(social, instagram))
        )

        assertTrue(entries(delayOnly).isEmpty())
        assertEquals(CounterCacheCoverage.Needs(essentials = false, launchable = false), CounterCacheCoverage.needs(delayOnly))
    }

    @Test
    fun `a rule for a deleted group covers nothing`() {
        val cache = entries(
            snapshot(
                rules = listOf(groupLimit(social, 30)),
                groups = emptyList(),
                members = listOf(aGroupMember(social, instagram))
            )
        )
        assertTrue(cache.isEmpty())
    }

    // ── All apps ──

    @Test
    fun `an All apps rule with a limit covers every launchable app except essentials and its allowed apps and groups`() {
        val allApps = aBlockRule(
            allApps = true,
            mode = BlockMode.DELAY.name,
            dailyLimitMinutes = 60,
            exceptPackages = ExceptCsv.ofPackages(listOf(maps)),
            exceptGroupIds = ExceptCsv.ofGroupIds(listOf(scrolling))
        )
        val cache = entries(
            snapshot(
                rules = listOf(allApps),
                groups = listOf(anAppGroup("Scrolling", id = scrolling)),
                members = listOf(aGroupMember(scrolling, reddit))
            )
        )

        assertEquals(setOf(instagram, tiktok, chrome), cache.keys)
        assertEquals(60, cache[instagram]?.dailyLimitMinutes)
    }

    // ── cooldown merge ──

    @Test
    fun `a limit-only group row does not stretch a direct rule's kick cooldown`() {
        val cache = entries(
            snapshot(
                rules = listOf(
                    groupLimit(social, 30),
                    aBlockRule(id = 2, packageName = instagram, autoKickAfterMinutes = 5, autoKickCooldownSeconds = 30)
                ),
                groups = listOf(anAppGroup("Social", id = social)),
                members = listOf(aGroupMember(social, instagram))
            )
        )
        val entry = cache.getValue(instagram)
        assertEquals(30, entry.autoKickCooldownSeconds)
        assertEquals(5, entry.autoKickAfterMinutes)
        assertEquals(30, entry.dailyLimitMinutes)
    }

    // ── battery (#63): what load() reads ──

    @Test
    fun `load with app rules only never reads a device list`() = runTest {
        val cache = CounterCacheCoverage.load(
            snapshot = snapshot(rules = listOf(aBlockRule(packageName = instagram, dailyLimitMinutes = 20))),
            readLaunchable = { fail("launchable read"); emptyMap() },
            readEssentials = { fail("essentials read"); emptySet() },
            readBrowsers = { fail("browsers read"); emptySet() }
        )
        assertEquals(20, cache[instagram]?.dailyLimitMinutes)
    }

    @Test
    fun `load for a hand-picked group reads essentials but not the installed apps`() = runTest {
        var essentialReads = 0
        val cache = CounterCacheCoverage.load(
            snapshot = snapshot(
                rules = listOf(groupLimit(social, 2)),
                groups = listOf(anAppGroup("Social", id = social)),
                members = listOf(aGroupMember(social, instagram), aGroupMember(social, dialer))
            ),
            readLaunchable = { fail("launchable read"); emptyMap() },
            readEssentials = { essentialReads++; setOf(dialer) },
            readBrowsers = { fail("browsers read"); emptySet() }
        )
        assertEquals(1, essentialReads)
        assertEquals(setOf(instagram), cache.keys)
    }

    @Test
    fun `load for a category group reads all three and covers by category`() = runTest {
        val cache = CounterCacheCoverage.load(
            snapshot = snapshot(
                rules = listOf(groupLimit(social, 5)),
                groups = listOf(anAppGroup("Social", id = social, categories = socialCsv))
            ),
            readLaunchable = { device.launchable },
            readEssentials = { device.essentials },
            readBrowsers = { device.browsers }
        )
        assertEquals(setOf(instagram, tiktok, reddit), cache.keys)
        assertNull(cache[chrome])
    }

    @Test
    fun `load keeps website entries alongside group entries`() = runTest {
        val cache = CounterCacheCoverage.load(
            snapshot = snapshot(
                rules = listOf(
                    groupLimit(social, 5),
                    aBlockRule(id = 2, packageName = chrome, mode = BlockMode.HARD_BLOCK.name, webDomains = "reddit.com", autoKickAfterMinutes = 3)
                ),
                groups = listOf(anAppGroup("Social", id = social)),
                members = listOf(aGroupMember(social, instagram))
            ),
            readLaunchable = { emptyMap() },
            readEssentials = { emptySet() },
            readBrowsers = { emptySet() }
        )
        assertTrue(instagram in cache)
        assertTrue("website entries survive", WebSessionKey.forDomain("reddit.com")!! in cache)
    }
}
