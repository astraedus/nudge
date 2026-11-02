package com.astraedus.nudge.data.repository

import com.astraedus.nudge.domain.groups.GroupMembershipResolver
import com.astraedus.nudge.domain.packages.EssentialPackages
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who is in a group on THIS phone, outside the engine: the saved groups' [GroupMembershipResolver]
 * fed the device facts it resolves against (each installed app's category, the essentials, the one
 * browser list). The redirect app and the All apps block screen both ask it, so they see the same
 * members the engine blocks or allows (spec 5.2).
 *
 * The installed apps and the two lists are only read when one of the asked groups checks a
 * category; otherwise a group's members are its "Add apps" rows minus "Removed by you", and this
 * costs no PackageManager call.
 */
@Singleton
class DeviceGroupMembers @Inject constructor(
    private val blockRuleRepository: BlockRuleRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val essentialPackages: EssentialPackages
) {
    /** The members of each of [groupIds] as saved now, by group id. A deleted group has none. */
    suspend fun of(groupIds: Collection<Long>): Map<Long, Set<String>> {
        if (groupIds.isEmpty()) return emptyMap()
        val membership = groupResolverOf(
            blockRuleRepository.getAllGroups().first(),
            blockRuleRepository.getAllGroupMembers().first()
        )
        val usesCategories = membership.usesCategories(groupIds)
        val installed = if (usesCategories) {
            installedAppsRepository.getInstalledApps().associate { it.packageName to it.category }
        } else {
            emptyMap()
        }
        val essentials = if (usesCategories) essentialPackages.current() else emptySet()
        val browsers = if (usesCategories) essentialPackages.browsers() else emptySet()
        return groupIds.associateWith { id -> membership.membersOf(id, installed, essentials, browsers) }
    }
}
