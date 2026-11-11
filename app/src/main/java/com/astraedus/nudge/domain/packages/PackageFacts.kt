package com.astraedus.nudge.domain.packages

import java.util.concurrent.ConcurrentHashMap

/**
 * Coarse app category, mirroring `ApplicationInfo.CATEGORY_*` without the Android dependency.
 *
 * There is deliberately no ACCESSIBILITY value: `ApplicationInfo.CATEGORY_ACCESSIBILITY` is API 31+
 * and is never offered as a group (spec 6.4), so it maps to [UNDEFINED] like any unknown category.
 */
enum class AppCategory { GAME, AUDIO, VIDEO, IMAGE, SOCIAL, NEWS, MAPS, PRODUCTIVITY, UNDEFINED }

/**
 * What the engine needs to know about one installed package.
 *
 * [homeScreen] is true when the package handles `CATEGORY_HOME` (any launcher, the default or
 * not). The bounce check-in reads it so a launcher never counts as an app even when the cached
 * launcher set is empty or stale (#181).
 */
data class PackageFact(
    val category: AppCategory,
    val launchable: Boolean,
    val homeScreen: Boolean = false
) {
    companion object {
        /** A package that is not installed (or could not be read): uncategorised, not launchable. */
        val NOT_INSTALLED = PackageFact(AppCategory.UNDEFINED, launchable = false)
    }
}

/**
 * The port the engine reads a package's category and launchability through (spec 5.2/5.7).
 * The implementation caches per package and reads off the main thread; an install, update or
 * removal invalidates the affected package.
 */
interface PackageFacts {
    suspend fun factsFor(packageName: String): PackageFact

    /**
     * The cached facts for [packageName], or null if they have not been read since the last
     * invalidation. Never reads PackageManager: it is for the synchronous hot-path checks, which
     * must fail closed on null. The default (no cache) is always null, which is that safe side.
     */
    fun cachedOrNull(packageName: String): PackageFact? = null

    fun invalidate(packageName: String)
    fun invalidateAll()
}

/**
 * Pure, thread-safe per-package cache over [resolve]. A hit never calls [resolve]; a miss resolves
 * and stores. If [resolve] throws, nothing is cached and the exception propagates, so the caller
 * decides how to degrade and the next read retries.
 */
class PackageFactsCache(private val resolve: (String) -> PackageFact) {
    private val facts = ConcurrentHashMap<String, PackageFact>()

    fun get(packageName: String): PackageFact = facts.computeIfAbsent(packageName, resolve)

    fun peek(packageName: String): PackageFact? = facts[packageName]

    fun invalidate(packageName: String) {
        facts.remove(packageName)
    }

    fun invalidateAll() = facts.clear()
}
