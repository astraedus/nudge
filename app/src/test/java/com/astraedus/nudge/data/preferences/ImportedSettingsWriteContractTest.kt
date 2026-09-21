package com.astraedus.nudge.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard for how an imported backup's settings are WRITTEN.
 *
 * `NudgePreferences` is not JVM-testable (Android `Context`, a DataStore delegate), and the two
 * invariants that matter here are not values a unit test could inspect — they are the SHAPE of the
 * write:
 *
 * 1. **All-or-nothing.** Every carried key goes inside ONE `dataStore.edit { }` block, which
 *    DataStore commits as a single transaction. Split across two `edit` calls, a restore could land
 *    `strictModeEnabled = false` without the challenge length that came with it — a protection
 *    state the user's backup never described. This is the same discipline the history restore has
 *    (one `insertAll`, pinned by `UsageRepositoryInsertEventsTest`).
 * 2. **A null field is SKIPPED, never written.** Absent is the format's word for "this file does not
 *    carry that setting", so the importing device must keep its own value. Writing a null through as
 *    a default would silently blank settings a partial or older backup simply said nothing about.
 *
 * Following the precedent of `BlockOverlayWalkAwayContractTest` and `ContentFilterAssetTest`: where
 * behaviour cannot be exercised, test the thing that actually goes wrong.
 */
class ImportedSettingsWriteContractTest {

    private val source: String by lazy {
        val path = "src/main/java/com/astraedus/nudge/data/preferences/NudgePreferences.kt"
        val candidates = listOf(File(path), File("app/$path"))
        (candidates.firstOrNull { it.exists() }
            ?: error("NudgePreferences.kt not found from working dir ${File("").absolutePath}"))
            .readText()
    }

    /** The body of `applyImportedSettings`, from its signature to the end of the file. */
    private val applyBody: String by lazy {
        val start = source.indexOf("suspend fun applyImportedSettings(")
        assertTrue("applyImportedSettings must exist", start >= 0)
        source.substring(start)
    }

    @Test
    fun `imported settings are written in a single transaction`() {
        assertEquals(
            "every carried key must be written inside ONE dataStore.edit block",
            1,
            Regex("""dataStore\.edit""").findAll(applyBody).count()
        )
    }

    /**
     * The nine settings a backup carries. Each must be written through a null-guard, so a file that
     * does not carry it leaves this device's value alone.
     */
    @Test
    fun `every carried setting is written only when the file actually carries it`() {
        val carried = listOf(
            "contentFilterEnabled" to "CONTENT_FILTER_ENABLED",
            "contentFilterMode" to "CONTENT_FILTER_MODE",
            "contentFilterStrictKeywords" to "CONTENT_FILTER_STRICT_KEYWORDS",
            "strictModeEnabled" to "STRICT_MODE_ENABLED",
            "strictModeChallengeLength" to "STRICT_MODE_CHALLENGE_LENGTH",
            "emergencyPassEnabled" to "EMERGENCY_PASS_ENABLED",
            "customDelayTitles" to "CUSTOM_DELAY_TITLES",
            "customDelaySubtitles" to "CUSTOM_DELAY_SUBTITLES",
            "customHardBlockMessages" to "CUSTOM_HARD_BLOCK_MESSAGES"
        )

        carried.forEach { (field, key) ->
            assertTrue(
                "$field must be written under a null guard, not unconditionally",
                applyBody.contains("settings.$field?.let")
            )
            assertTrue("$field must write Keys.$key", applyBody.contains("Keys.$key"))
        }
    }

    /**
     * Device-local state must never be written by an import. These four describe THIS phone, not the
     * user's configuration: restoring onboarding would skip the accessibility-permission
     * walkthrough on a device that has granted nothing, and restoring the emergency-pass ledger
     * would either hand out a fresh daily pass or spend one that was never used.
     *
     * `GLOBAL_ENABLED` is on this list too, deliberately: a fresh install already defaults to
     * enabled, so carrying `true` restores nothing while carrying `false` silently switches the
     * whole blocker off. The only behaviour it could add is a file that disables protection.
     */
    @Test
    fun `device-local state is never written by an import`() {
        listOf(
            "ONBOARDING_COMPLETE",
            "EMERGENCY_PASS_USAGE",
            "PIP_ESCAPE_PROMPTED",
            "DEBUG_LOGGING_ENABLED",
            "GLOBAL_ENABLED"
        ).forEach { key ->
            assertTrue(
                "$key must not be restorable from a backup file",
                !applyBody.contains("Keys.$key")
            )
        }
    }

    /**
     * The export side of the same list: what a file carries and what an import writes must stay the
     * same set, or a setting silently becomes one-way.
     */
    @Test
    fun `the exported settings are exactly the ones an import can write`() {
        val exportStart = source.indexOf("suspend fun exportableSettings()")
        assertTrue("exportableSettings must exist", exportStart >= 0)
        val exportBody = source.substring(exportStart, source.indexOf("suspend fun applyImportedSettings("))

        listOf(
            "contentFilterEnabled",
            "contentFilterMode",
            "contentFilterStrictKeywords",
            "strictModeEnabled",
            "strictModeChallengeLength",
            "emergencyPassEnabled",
            "customDelayTitles",
            "customDelaySubtitles",
            "customHardBlockMessages"
        ).forEach { field ->
            assertTrue("$field must be exported as well as importable", exportBody.contains("$field ="))
        }
        listOf("isGlobalEnabled", "isOnboardingComplete", "isDebugLoggingEnabled", "emergencyPassUsage")
            .forEach { flow ->
                assertTrue(
                    "$flow is device-local and must not be exported",
                    !exportBody.contains("$flow.first()")
                )
            }
    }
}
