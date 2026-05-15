package com.astraedus.nudge.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class NudgeAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // TODO: implement foreground app detection + block engine evaluation
    }

    override fun onInterrupt() {}
}
