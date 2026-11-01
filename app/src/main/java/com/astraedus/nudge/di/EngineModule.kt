package com.astraedus.nudge.di

import com.astraedus.nudge.data.repository.RuleSnapshotSource
import com.astraedus.nudge.data.repository.RuleSnapshots
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The evaluation engine's ports, bound to their production sources (spec section 5.7): the in-memory
 * rule snapshot.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {
    @Binds
    abstract fun bindRuleSnapshots(impl: RuleSnapshotSource): RuleSnapshots
}
