package com.astraedus.nudge.ui.screens.allapps

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.ExceptCsv
import com.astraedus.nudge.testing.aBlockRule
import com.astraedus.nudge.testing.aGroupMember
import com.astraedus.nudge.testing.anApp
import com.astraedus.nudge.testing.anAppGroup

/**
 * One small phone for the All apps editor's tests. Launchable: chat, feed, game, mail, notes and
 * the dialer; the dialer and a launcher (no launcher entry of its own) are essential. Two groups
 * exist, Work (mail, notes, and an app that is no longer installed) and Social (feed); the members
 * of a deleted group 9 are still in the member table, as a stale row would be.
 */
internal object AllAppsFixtures {
    const val CHAT = "com.example.chat"
    const val FEED = "com.example.feed"
    const val GAME = "com.example.game"
    const val MAIL = "com.example.mail"
    const val NOTES = "com.example.notes"
    const val DIALER = "com.example.dialer"
    const val LAUNCHER = "com.example.launcher"
    const val GONE = "com.example.gone"

    const val WORK = 1L
    const val SOCIAL = 2L
    const val DELETED = 9L

    /** An installed app labelled as the launcher would: its package's last word, capitalised. */
    private fun labelled(pkg: String) = anApp(pkg, pkg.substringAfterLast('.').replaceFirstChar(Char::uppercase))

    val installed = listOf(CHAT, DIALER, FEED, GAME, MAIL, NOTES).map(::labelled)

    val device = DeviceApps(
        installed = installed,
        essentials = setOf(DIALER, LAUNCHER),
        alwaysAllowed = listOf(labelled(DIALER))
    )

    val groups = listOf(anAppGroup("Work", id = WORK), anAppGroup("Social", id = SOCIAL))

    val members = listOf(
        aGroupMember(WORK, MAIL),
        aGroupMember(WORK, NOTES),
        aGroupMember(WORK, GONE),
        aGroupMember(SOCIAL, FEED),
        aGroupMember(DELETED, CHAT)
    )

    fun tables(rules: List<BlockRule> = emptyList()) = RuleTables(rules, groups, members)

    fun allAppsRule(
        id: Long = 0,
        allowedPackages: Collection<String> = emptyList(),
        allowedGroupIds: Collection<Long> = emptyList(),
        enabled: Boolean = true,
        mode: BlockMode = BlockMode.DELAY
    ) = aBlockRule(
        id = id,
        mode = mode.name,
        enabled = enabled,
        allApps = true,
        exceptPackages = ExceptCsv.ofPackages(allowedPackages),
        exceptGroupIds = ExceptCsv.ofGroupIds(allowedGroupIds)
    )

    fun appRule(id: Long, pkg: String, enabled: Boolean = true, features: String? = null) =
        aBlockRule(id = id, packageName = pkg, enabled = enabled, inAppFeatures = features)
}
