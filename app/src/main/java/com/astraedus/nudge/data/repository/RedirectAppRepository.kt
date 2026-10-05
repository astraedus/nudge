package com.astraedus.nudge.data.repository

import android.content.Context
import android.content.Intent
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.di.IoDispatcher
import com.astraedus.nudge.domain.redirect.RedirectAppPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where [RedirectAppPolicy] meets the device: the saved choice, the rules and groups, the Nuke list,
 * and PackageManager. Every DECISION is the policy's; this class only gathers its inputs.
 *
 * Used by the block overlay (the bubble) and by Settings (the row), through one
 * `RedirectAppController`, so both surfaces offer the same apps and refuse the same ones.
 */
@Singleton
class RedirectAppRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: NudgePreferences,
    private val blockRuleRepository: BlockRuleRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    /**
     * Every package that may not be the redirect app right now. [blockPackages] are the packages of
     * the block on screen (empty from Settings).
     */
    suspend fun excluded(blockPackages: Set<String> = emptySet()): Set<String> {
        val rules = blockRuleRepository.getEnabledRules().first()
        val groupIds = rules.mapNotNull { it.groupId }.toSet()
        val members = groupIds.associateWith { id ->
            blockRuleRepository.getGroupMembers(id).first().mapTo(mutableSetOf()) { it.packageName }
        }
        val targets = RedirectAppPolicy.ruleTargets(
            rules.map { RedirectAppPolicy.RuleTarget(it.packageName, it.groupId, it.enabled) },
            members
        )
        return RedirectAppPolicy.excluded(
            ownPackage = context.packageName,
            ruleTargets = targets,
            nukeList = preferences.nukeState.first().packages,
            blockPackages = blockPackages
        )
    }

    /**
     * The app the bubble should show, or null for the empty state. Re-checked on every call, so a
     * choice that has since been uninstalled or blocked degrades to "pick a better app" instead of
     * crashing or launching into another block.
     */
    suspend fun current(blockPackages: Set<String> = emptySet()): InstalledAppsRepository.AppInfo? {
        // Nothing saved is the common case and costs no rule or PackageManager reads at all; the
        // overlay calls this on its render path.
        val saved = preferences.redirectAppPackage.first() ?: return null
        val pkg = RedirectAppPolicy.resolve(
            saved = saved,
            isLaunchable = isLaunchable(saved),
            excluded = excluded(blockPackages)
        ) ?: return null
        return InstalledAppsRepository.AppInfo(
            packageName = pkg,
            appName = installedAppsRepository.resolveAppName(pkg),
            icon = installedAppsRepository.resolveIcon(pkg)
        )
    }

    /** The picker's rows: launchable apps the policy allows, alphabetical. */
    suspend fun candidates(blockPackages: Set<String> = emptySet()): List<InstalledAppsRepository.AppInfo> =
        RedirectAppPolicy.pickerRows(
            installed = installedAppsRepository.getInstalledApps(),
            excluded = excluded(blockPackages),
            query = ""
        ) { RedirectAppPolicy.Candidate(it.packageName, it.appName) }

    /**
     * Save [packageName] as the redirect app. Refused (returns false, nothing written) when the
     * policy excludes it: the picker already hides those, this is the same rule at the write.
     */
    suspend fun choose(packageName: String, blockPackages: Set<String> = emptySet()): Boolean {
        if (!RedirectAppPolicy.isEligible(packageName, excluded(blockPackages))) return false
        // NonCancellable: the overlay's scope dies with the activity, and a walk-away right after a
        // pick must not leave the screen saying one thing and the preference another.
        withContext(NonCancellable) { preferences.setRedirectAppPackage(packageName) }
        return true
    }

    suspend fun clear() = withContext(NonCancellable) { preferences.setRedirectAppPackage(null) }

    /**
     * The intent that opens [packageName] in its own task, or null when it has no launcher entry
     * (uninstalled, disabled). `QUERY_ALL_PACKAGES` is declared in the manifest, so every installed
     * package is visible on API 30+.
     */
    fun launchIntent(packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private suspend fun isLaunchable(packageName: String): Boolean =
        withContext(ioDispatcher) {
            try {
                launchIntent(packageName) != null
            } catch (_: Exception) {
                false
            }
        }
}
