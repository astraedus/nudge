package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo

/** Thin interface extracted for testability. */
interface InAppDetectorApi {
    fun detectFeature(packageName: String, rootNode: AccessibilityNodeInfo?): InAppDetector.Feature?
}
