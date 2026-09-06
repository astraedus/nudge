package com.astraedus.nudge.domain.health

/**
 * Component matching for the two accessibility lists Nudge has to read, kept pure so both can be
 * unit-tested and so neither call site re-derives the parsing.
 *
 * **These two lists answer different questions, and conflating them is the bug this subsystem
 * exists for.** `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` is the user's INTENT; the bound
 * list behind `AccessibilityManager.getEnabledAccessibilityServiceList` is REALITY. Per AOSP
 * (`AccessibilityServiceConnection.binderDied` → `AccessibilityUserState.mCrashedServices`, which
 * `AccessibilityManagerService.updateServicesLocked` explicitly `continue`s past), a service whose
 * process is killed is **never rebound and is NOT removed from the settings string**. So the
 * settings string reads "enabled" over a permanently dead service, forever. Reading only it — which
 * is all Nudge ever did — is a detector that cannot see the failure it was pointed at.
 *
 * The Settings screen used to answer with `enabledServices.contains(context.packageName)`. That is
 * also a substring test: it says yes for any component of ours in the list (Nudge only ships one
 * accessibility service today, so it happened to be right) and it would say yes for an unrelated
 * package whose name merely contains ours. Compare components.
 */
object EnabledAccessibilityServices {

    /** AOSP writes the list with `TextUtils.SimpleStringSplitter(':')`. */
    private const val ENTRY_SEPARATOR = ':'
    private const val COMPONENT_SEPARATOR = '/'

    /**
     * True when [packageName]/[className] is present in [raw].
     *
     * [raw] is null when the setting has never been written (no accessibility service has ever
     * been enabled on the device), which reads as "not enabled" rather than as an error.
     *
     * Entries are `package/class`. The class half is normally the flattened absolute name, but a
     * relative form (`.service.NudgeAccessibilityService`) is accepted too: `ComponentName`'s own
     * short-flattening produces it, and it is resolved by suffix rather than by prepending the
     * package, because Nudge's `applicationId` (`dev.astraedus.nudge`) is deliberately not its
     * `namespace` (`com.astraedus.nudge`) — prepending would never match.
     */
    fun contains(raw: String?, packageName: String, className: String): Boolean {
        if (raw.isNullOrBlank() || packageName.isBlank() || className.isBlank()) return false

        return raw.split(ENTRY_SEPARATOR).any { entry ->
            matches(entry.trim(), packageName, className)
        }
    }

    /**
     * True when [packageName]/[className] appears among [entries].
     *
     * Used for the bound-services list, whose entries are `AccessibilityServiceInfo.getId()` —
     * a `ComponentName.flattenToShortString()`, i.e. the same `package/class` shape, sometimes
     * with the class half relative. Nulls are tolerated because that getter is nullable.
     */
    fun containsAny(entries: List<String?>, packageName: String, className: String): Boolean {
        if (packageName.isBlank() || className.isBlank()) return false
        return entries.any { entry ->
            entry != null && matches(entry.trim(), packageName, className)
        }
    }

    private fun matches(entry: String, packageName: String, className: String): Boolean {
        if (entry.isEmpty()) return false

        val slash = entry.indexOf(COMPONENT_SEPARATOR)
        // A bare package with no component half is not a shape AOSP writes, but treating it as a
        // match keeps a lenient OEM variant from reading as "the service is gone". A false
        // negative here costs the user a wrong "blocking has stopped" push notification.
        if (slash < 0) return entry == packageName

        val entryPackage = entry.substring(0, slash)
        val entryClass = entry.substring(slash + 1)
        if (entryPackage != packageName || entryClass.isEmpty()) return false

        return if (entryClass.startsWith(".")) {
            className.endsWith(entryClass)
        } else {
            entryClass == className
        }
    }
}
