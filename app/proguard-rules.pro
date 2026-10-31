# Nudge ProGuard rules

# Shrink + optimise, but never rename. Not a size question, a correctness one:
# NudgeAccessibilityService.OWN_CLASS_NAMESPACE is read off BuildConfig's class NAME, and the
# windows it is compared against are manifest activities, whose names R8 always keeps. Renaming
# BuildConfig to `a.b` would make "is this our own window" false for every event, which is
# issue #33 shipped again. Unrenamed class names also keep user bug-report stack traces
# readable without a mapping file, for an open-source app with nothing to hide.
-dontobfuscate
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
