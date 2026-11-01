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
}

/** Launchable = in [launchable]; everything else reads as not installed. */
class FakePackageFacts(var launchable: Set<String> = emptySet()) : PackageFacts {
    var reads = 0
        private set

    override suspend fun factsFor(packageName: String): PackageFact {
        reads++
        return if (packageName in launchable) {
            PackageFact(AppCategory.UNDEFINED, launchable = true)
        } else {
            PackageFact.NOT_INSTALLED
        }
    }

    override fun invalidate(packageName: String) = Unit

    override fun invalidateAll() = Unit
}
