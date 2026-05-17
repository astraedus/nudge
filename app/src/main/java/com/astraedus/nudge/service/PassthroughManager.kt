package com.astraedus.nudge.service

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PassthroughManager @Inject constructor() {
    @Volatile var lastPackage: String? = null
        private set
    @Volatile var lastFeature: String? = null
        private set
    @Volatile var lastTime: Long = 0L
        private set

    fun grant(packageName: String, featureKey: String? = null) {
        lastPackage = packageName
        lastFeature = featureKey
        lastTime = System.currentTimeMillis()
    }

    fun isGranted(packageName: String): Boolean = packageName == lastPackage

    fun shouldSkipForegroundEvaluation(packageName: String): Boolean = isGranted(packageName)

    fun shouldSkipFeatureEvaluation(packageName: String, featureKey: String): Boolean =
        isGranted(packageName) && lastFeature == featureKey

    fun clearIfAppChanged(packageName: String): Boolean {
        if (lastPackage == null || packageName == lastPackage) return false
        clear()
        return true
    }

    fun clear() {
        lastPackage = null
        lastFeature = null
        lastTime = 0L
    }

    fun resetForTests() = clear()
}
