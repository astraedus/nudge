package com.astraedus.nudge.di

import com.astraedus.nudge.data.repository.FirstUnlockStateCache
import com.astraedus.nudge.data.repository.RuleSnapshotSource
import com.astraedus.nudge.data.repository.RuleSnapshots
import com.astraedus.nudge.domain.engine.FirstUnlockState
import com.astraedus.nudge.domain.packages.EssentialPackages
import com.astraedus.nudge.domain.packages.PackageFacts
import com.astraedus.nudge.service.AndroidPackageFacts
import com.astraedus.nudge.service.EssentialApps
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The evaluation engine's ports, bound to their production sources: the in-memory rule snapshot
 * (spec section 5.7) and the per-package facts (category, launchable; spec section 5.2) that group
 * and "All apps" resolution read, and the one essentials list (spec section 5.4).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {
    @Binds
    abstract fun bindRuleSnapshots(impl: RuleSnapshotSource): RuleSnapshots

    @Binds
    abstract fun bindPackageFacts(impl: AndroidPackageFacts): PackageFacts

    @Binds
    abstract fun bindEssentialPackages(impl: EssentialApps): EssentialPackages

    /**
     * The first-unlock anchor (spec 5.3). Bound as a port so `domain/engine` never sees the
     * preferences class: [com.astraedus.nudge.domain.engine.ScheduleEvaluator] asks this for the
     * stamp, the day start and the zone, and the cache answers from memory.
     */
    @Binds
    abstract fun bindFirstUnlockState(impl: FirstUnlockStateCache): FirstUnlockState
}
