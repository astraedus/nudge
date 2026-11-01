package com.astraedus.nudge.di

import com.astraedus.nudge.data.repository.RuleSnapshotSource
import com.astraedus.nudge.data.repository.RuleSnapshots
import com.astraedus.nudge.domain.packages.PackageFacts
import com.astraedus.nudge.service.AndroidPackageFacts
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The evaluation engine's ports, bound to their production sources: the in-memory rule snapshot
 * (spec section 5.7) and the per-package facts (category, launchable; spec section 5.2) that group
 * and "All apps" resolution will read in later PRs.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {
    @Binds
    abstract fun bindRuleSnapshots(impl: RuleSnapshotSource): RuleSnapshots

    @Binds
    abstract fun bindPackageFacts(impl: AndroidPackageFacts): PackageFacts
}
