package com.astraedus.nudge.ui.screens.allapps

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.ExceptCsv

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

    fun app(pkg: String, name: String = pkg.substringAfterLast('.').replaceFirstChar(Char::uppercase)) =
        InstalledAppsRepository.AppInfo(pkg, name, null)

    val installed = listOf(app(CHAT), app(DIALER), app(FEED), app(GAME), app(MAIL), app(NOTES))

    val device = DeviceApps(
        installed = installed,
        essentials = setOf(DIALER, LAUNCHER),
        alwaysAllowed = listOf(app(DIALER))
    )

    val groups = listOf(AppGroup(id = WORK, name = "Work"), AppGroup(id = SOCIAL, name = "Social"))

    val members = listOf(
        AppGroupMember(WORK, MAIL),
        AppGroupMember(WORK, NOTES),
        AppGroupMember(WORK, GONE),
        AppGroupMember(SOCIAL, FEED),
        AppGroupMember(DELETED, CHAT)
    )

    fun tables(rules: List<BlockRule> = emptyList()) = RuleTables(rules, groups, members)

    fun allAppsRule(
        id: Long = 0,
        allowedPackages: Collection<String> = emptyList(),
        allowedGroupIds: Collection<Long> = emptyList(),
        enabled: Boolean = true,
        mode: BlockMode = BlockMode.DELAY
    ) = BlockRule(
        id = id,
        mode = mode.name,
        enabled = enabled,
        allApps = true,
        exceptPackages = ExceptCsv.ofPackages(allowedPackages),
        exceptGroupIds = ExceptCsv.ofGroupIds(allowedGroupIds)
    )

    fun appRule(id: Long, pkg: String, enabled: Boolean = true, features: String? = null) = BlockRule(
        id = id,
        packageName = pkg,
        mode = BlockMode.DELAY.name,
        enabled = enabled,
        inAppFeatures = features
    )

    fun groupRule(id: Long, groupId: Long, enabled: Boolean = true) = BlockRule(
        id = id,
        groupId = groupId,
        mode = BlockMode.DELAY.name,
        enabled = enabled
    )
}
