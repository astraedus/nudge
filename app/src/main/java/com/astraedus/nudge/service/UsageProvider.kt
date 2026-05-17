package com.astraedus.nudge.service

/** Provides usage data. Extracted for testability. */
interface UsageProvider {
    fun getDailyForegroundTimeMs(packageName: String): Long
}
