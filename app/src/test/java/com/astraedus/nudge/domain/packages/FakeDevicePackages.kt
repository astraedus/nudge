package com.astraedus.nudge.domain.packages

/**
 * Test doubles for the two device facts coverage reads (spec 4.1). Every test that builds an
 * `EvaluateBlockUseCase` says what the device holds; there is no defaulted "nothing is essential",
 * because that default is how an essential app ends up under an All apps rule.
 */
class FakeEssentialPackages(var packages: Set<String> = emptySet()) : EssentialPackages {
    var reads = 0
        private set

    override suspend fun current(): Set<String> {
        reads++
        return packages
    }

    override fun cachedOrNull(): Set<String>? = packages

    /** The one browser list; empty unless a test sets it. */
    var browsers: Set<String> = emptySet()

    override suspend fun browsers(): Set<String> = browsers
}

/**
 * Launchable = in [launchable]; everything else reads as not installed. A launchable app's category
 * is its entry in [categories], UNDEFINED when it has none. A package in [home] answers a scoped
 * `CATEGORY_HOME` query on this device (installed whether or not it is also launchable), and its
 * `homeScreen` fact is [homeScreenRule] of that: by default the raw answer, and the production rule
 * (`isHomeScreenPackage`) wherever a test installs it, as `BounceHarness` does.
 *
 * By default [cachedOrNull] is always null (the port's no-cache default). With [caches] on, it
 * behaves like the real implementation: a fact appears only once [factsFor] has read it, and
 * [invalidate] drops it. [failing] packages throw from [factsFor] and are never cached.
 */
class FakePackageFacts(
    var launchable: Set<String> = emptySet(),
    var categories: Map<String, AppCategory> = emptyMap(),
    var home: Set<String> = emptySet(),
    var caches: Boolean = false,
    var failing: Set<String> = emptySet()
) : PackageFacts {
    /** `(package, its scoped HOME query resolves) -> homeScreen`. */
    var homeScreenRule: (String, Boolean) -> Boolean = { _, resolvesHome -> resolvesHome }

    var reads = 0
        private set

    private val cache = HashMap<String, PackageFact>()
    private val readsByPackage = HashMap<String, Int>()

    /** How many times [factsFor] was asked about [packageName]. */
    fun readsOf(packageName: String): Int = readsByPackage[packageName] ?: 0

    override suspend fun factsFor(packageName: String): PackageFact {
        reads++
        readsByPackage.merge(packageName, 1, Int::plus)
        if (packageName in failing) error("fake read failure for $packageName")
        val fact = factOf(packageName)
        if (caches) cache[packageName] = fact
        return fact
    }

    override fun cachedOrNull(packageName: String): PackageFact? = if (caches) cache[packageName] else null

    override fun invalidate(packageName: String) {
        cache.remove(packageName)
    }

    override fun invalidateAll() = cache.clear()

    private fun factOf(packageName: String): PackageFact = when {
        packageName in launchable || packageName in home -> PackageFact(
            category = categories[packageName] ?: AppCategory.UNDEFINED,
            launchable = packageName in launchable,
            homeScreen = homeScreenRule(packageName, packageName in home)
        )
        else -> PackageFact.NOT_INSTALLED
    }
}
