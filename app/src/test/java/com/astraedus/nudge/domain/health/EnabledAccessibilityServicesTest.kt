package com.astraedus.nudge.domain.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single signal the whole watchdog rests on. Everything that can go wrong on a user's phone —
 * an OS memory kill, MIUI revoking the grant when it kills our process, Android disabling the
 * service on an in-place update — shows up here as our component leaving this list, so a wrong
 * answer in either direction is expensive: a false negative pushes "blocking has stopped" at a
 * user whose blocking is fine, a false positive is the silent failure we shipped for months.
 *
 * Note the two package names in play. Nudge's `applicationId` is `dev.astraedus.nudge` and its
 * `namespace` is `com.astraedus.nudge`, so the component string pairs one with the other. Any
 * matcher that assumes the class is a child of the package is wrong here.
 */
class EnabledAccessibilityServicesTest {

    private val pkg = "dev.astraedus.nudge"
    private val cls = "com.astraedus.nudge.service.NudgeAccessibilityService"

    private fun contains(raw: String?) = EnabledAccessibilityServices.contains(raw, pkg, cls)

    @Test
    fun `the flattened component counts as enabled`() {
        assertTrue(contains("$pkg/$cls"))
    }

    @Test
    fun `our entry is found among other apps services`() {
        assertTrue(
            contains(
                "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService:" +
                    "$pkg/$cls:" +
                    "com.example.other/com.example.other.Service"
            )
        )
    }

    @Test
    fun `a short-flattened relative class is resolved by suffix, not by prepending the package`() {
        // ComponentName.flattenToShortString writes this form. Prepending `dev.astraedus.nudge`
        // to `.service.NudgeAccessibilityService` would produce a class that does not exist.
        assertTrue(contains("$pkg/.service.NudgeAccessibilityService"))
    }

    @Test
    fun `entries are trimmed`() {
        assertTrue(contains(" $pkg/$cls "))
    }

    @Test
    fun `a never-written setting is not enabled`() {
        assertFalse(contains(null))
        assertFalse(contains(""))
        assertFalse(contains("   "))
    }

    @Test
    fun `an empty list of other services is not enabled`() {
        assertFalse(contains("com.example.other/com.example.other.Service"))
    }

    /**
     * The defect in the reading this replaces: the Settings screen asked
     * `enabledServices.contains(context.packageName)`, a substring test. A package that merely
     * contains ours, or one of our OWN components that is not the blocking service, both answered
     * "protection is on".
     */
    @Test
    fun `a package that merely contains ours is not us`() {
        assertFalse(contains("com.evil.dev.astraedus.nudge.clone/com.evil.Service"))
    }

    @Test
    fun `a different service in our own package is not the blocking service`() {
        assertFalse(contains("$pkg/com.astraedus.nudge.service.SomeOtherService"))
    }

    @Test
    fun `a malformed entry does not match`() {
        assertFalse(contains("$pkg/"))
        assertFalse(contains("/$cls"))
        assertFalse(contains(":::"))
    }

    /**
     * Deliberately lenient: AOSP always writes `package/class`, but an OEM variant that stored the
     * bare package must not read as "the service is gone" and fire a false alarm.
     */
    @Test
    fun `a bare package entry is treated as enabled`() {
        assertTrue(contains(pkg))
    }

    // --- The bound-services list ---------------------------------------------------------------
    //
    // Same component shapes, different source: AccessibilityServiceInfo.getId() is a
    // ComponentName.flattenToShortString, and the list it comes from is the system's
    // mBoundServices. This is the liveness half; the settings string above is the intent half.

    @Test
    fun `our component is found in the bound-services list`() {
        assertTrue(
            EnabledAccessibilityServices.containsAny(listOf("$pkg/$cls"), pkg, cls)
        )
    }

    @Test
    fun `a bound list holding only other apps services does not contain us`() {
        assertFalse(
            EnabledAccessibilityServices.containsAny(
                listOf("com.example.other/com.example.other.Service"),
                pkg,
                cls
            )
        )
    }

    /**
     * The empty list is the crash state: AOSP drops a crashed service from `mBoundServices` and
     * never puts it back, while leaving it in the settings string. This must read as NOT connected,
     * or the whole watchdog is blind to the failure it exists for.
     */
    @Test
    fun `an empty bound list is not connected`() {
        assertFalse(EnabledAccessibilityServices.containsAny(emptyList(), pkg, cls))
    }

    /** `getId()` is nullable, and a null entry must not blow up a check that runs every 15 minutes. */
    @Test
    fun `null entries in the bound list are skipped`() {
        assertFalse(EnabledAccessibilityServices.containsAny(listOf(null, null), pkg, cls))
        assertTrue(EnabledAccessibilityServices.containsAny(listOf(null, "$pkg/$cls"), pkg, cls))
    }

    @Test
    fun `a short-flattened bound entry is matched too`() {
        assertTrue(
            EnabledAccessibilityServices.containsAny(
                listOf("$pkg/.service.NudgeAccessibilityService"),
                pkg,
                cls
            )
        )
    }
}
