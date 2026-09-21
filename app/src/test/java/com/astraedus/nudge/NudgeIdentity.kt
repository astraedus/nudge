package com.astraedus.nudge

/**
 * The app's REAL identity, for tests, read off the real build instead of typed out.
 *
 * ## The trap this closes
 *
 * This app has two identities that look like one another and are never interchangeable:
 *
 * | | value | where it appears |
 * |---|---|---|
 * | `applicationId` | `dev.astraedus.nudge` | `AccessibilityEvent.getPackageName()` |
 * | `namespace` | `com.astraedus.nudge` | `AccessibilityEvent.getClassName()`, every class we ship |
 *
 * [#33](https://github.com/astraedus/nudge/issues/33) is what happens when a predicate treats them
 * as one: `NudgeAccessibilityService.shouldClearForOwnPackageEvent` asked
 * `className.startsWith(applicationId)`, which is false for every event this app can emit, so the
 * branch it guarded was dead in production for months. **Every one of its unit tests passed** —
 * they built the classifier with `ownPackageName = "com.astraedus.nudge"` and then fed that same
 * literal as the event's package, so the two agreed by construction and the real applicationId was
 * never in the room. `tasks/lessons.md` (2026-09-14) records the general form: *a test that supplies
 * its own value for a production constant cannot see that the production value breaks the
 * predicate.*
 *
 * So there is one place, it is derived, and `OwnClassNamespaceContractTest` asserts the two are
 * different strings — which is the assertion that makes collapsing them back into one a failing
 * test rather than a silently disabled branch.
 */
object NudgeIdentity {

    /**
     * The applicationId, i.e. what `event.packageName` carries at runtime.
     *
     * From `BuildConfig`, which the Android plugin generates from `app/build.gradle.kts`, so a
     * change to the applicationId moves this without anybody remembering to.
     */
    const val APPLICATION_ID: String = BuildConfig.APPLICATION_ID

    /**
     * The class namespace, i.e. the prefix `event.className` carries for our own windows.
     *
     * Derived from a real class in the root namespace rather than written out. `BuildConfig` is
     * generated INTO `namespace`, so `BuildConfig::class.java.packageName` IS the namespace by
     * construction — the same derivation production uses
     * (`NudgeAccessibilityService.OWN_CLASS_NAMESPACE`).
     */
    val CLASS_NAMESPACE: String = BuildConfig::class.java.packageName
}
