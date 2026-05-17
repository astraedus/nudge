package com.astraedus.nudge.service

import kotlinx.coroutines.flow.Flow

/** Provides global enabled state. Extracted for testability. */
interface GlobalEnabledProvider {
    val isGlobalEnabled: Flow<Boolean>
}
