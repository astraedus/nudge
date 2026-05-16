# Nudge ProGuard rules
-keepattributes *Annotation*

# AccessibilityService is declared in manifest and instantiated by the system
-keep class com.astraedus.nudge.service.NudgeAccessibilityService { *; }

# BlockOverlayActivity is launched via Intent from the service
-keep class com.astraedus.nudge.ui.overlay.BlockOverlayActivity { *; }

# Hilt entry points are accessed via reflection
-keep interface com.astraedus.nudge.service.NudgeAccessibilityService$NudgeAccessibilityEntryPoint { *; }

# Room entities and DAOs
-keep class com.astraedus.nudge.data.db.entity.** { *; }
-keep class com.astraedus.nudge.data.db.dao.** { *; }

# Keep all service classes (GrayscaleManager, CounterOverlayManager, etc.)
-keep class com.astraedus.nudge.service.** { *; }
