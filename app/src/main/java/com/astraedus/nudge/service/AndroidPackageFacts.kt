package com.astraedus.nudge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.astraedus.nudge.di.IoDispatcher
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.domain.packages.PackageFact
import com.astraedus.nudge.domain.packages.PackageFacts
import com.astraedus.nudge.domain.packages.PackageFactsCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PackageFacts] backed by [PackageManager], cached per package.
 *
 * Failure direction: a package that cannot be read is treated as not launchable and uncategorised,
 * so it is not pulled into a group or "all apps" coverage - the same direction as today, where such
 * apps are simply not on any rule. A not-installed package is cached (an install broadcast
 * invalidates it); any other failure is NOT cached, so the next read retries.
 */
@Singleton
class AndroidPackageFacts @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    logger: NudgeLog
) : PackageFacts {
    private val log = logger.tagged("PackageFacts")
    private val pm: PackageManager = context.packageManager
    private val cache = PackageFactsCache(::resolve)

    init {
        // Runtime, not manifest: manifest receivers for these stopped firing at API 26. They are
        // protected system broadcasts, so no export flag is needed. A failed registration degrades
        // to "cache never invalidated" - stale facts, never a crash.
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        intent.data?.schemeSpecificPart?.let(::invalidate)
                    }
                },
                filter
            )
        } catch (e: Exception) {
            log.w("failed to register package-change receiver; facts will not be invalidated", e)
        }
    }

    override suspend fun factsFor(packageName: String): PackageFact {
        cache.peek(packageName)?.let { return it }
        return withContext(io) {
            try {
                cache.get(packageName)
            } catch (e: Exception) {
                log.w("failed to read package facts for $packageName", e)
                PackageFact.NOT_INSTALLED
            }
        }
    }

    override fun invalidate(packageName: String) = cache.invalidate(packageName)

    override fun invalidateAll() = cache.invalidateAll()

    private fun resolve(packageName: String): PackageFact = try {
        PackageFact(
            category = categoryOf(pm.getApplicationInfo(packageName, 0).category),
            launchable = pm.getLaunchIntentForPackage(packageName) != null
        )
    } catch (_: PackageManager.NameNotFoundException) {
        PackageFact.NOT_INSTALLED
    }
}

/** Maps `ApplicationInfo.category` to [AppCategory]; anything unlisted (incl. API 31+ ACCESSIBILITY) is UNDEFINED. */
internal fun categoryOf(androidCategory: Int): AppCategory = when (androidCategory) {
    ApplicationInfo.CATEGORY_GAME -> AppCategory.GAME
    ApplicationInfo.CATEGORY_AUDIO -> AppCategory.AUDIO
    ApplicationInfo.CATEGORY_VIDEO -> AppCategory.VIDEO
    ApplicationInfo.CATEGORY_IMAGE -> AppCategory.IMAGE
    ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.SOCIAL
    ApplicationInfo.CATEGORY_NEWS -> AppCategory.NEWS
    ApplicationInfo.CATEGORY_MAPS -> AppCategory.MAPS
    ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppCategory.PRODUCTIVITY
    else -> AppCategory.UNDEFINED
}
