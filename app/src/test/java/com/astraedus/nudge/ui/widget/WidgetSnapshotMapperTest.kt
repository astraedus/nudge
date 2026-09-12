package com.astraedus.nudge.ui.widget

import com.astraedus.nudge.ui.screens.stats.AppInterventionStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [WidgetSnapshotMapper]: the pure half of every widget, raw repository values
 * in, a finished [WidgetSnapshot] out. No Context, no Android types, no suspension — everything
 * here is exercised on the JVM.
 */
class WidgetSnapshotMapperTest {

    // ------------------------------------------------------------------------ today

    @Test
    fun `today with permission passes the formatted screen time through verbatim`() {
        val result = WidgetSnapshotMapper.today(
            screenTimeFormatted = "1h 23m",
            blockedCount = 5,
            walkAwayCount = 2,
            hasUsagePermission = true
        )

        assertEquals("1h 23m", result.screenTime)
        assertEquals(5, result.blocked)
        assertEquals(2, result.walkedAway)
        assertTrue(result.hasUsagePermission)
    }

    @Test
    fun `today without permission blanks screen time but keeps our own counts`() {
        val result = WidgetSnapshotMapper.today(
            screenTimeFormatted = "1h 23m",
            blockedCount = 7,
            walkAwayCount = 3,
            hasUsagePermission = false
        )

        assertEquals(
            "screen time is Usage-Access-gated and must never leak the formatted value",
            WidgetSnapshot.NO_VALUE,
            result.screenTime
        )
        // Block and walk-away counts come from our own database, not Usage Access, so a missing
        // permission must not zero them out.
        assertEquals(7, result.blocked)
        assertEquals(3, result.walkedAway)
        assertFalse(result.hasUsagePermission)
    }

    @Test
    fun `today floors negative counts at zero`() {
        val result = WidgetSnapshotMapper.today(
            screenTimeFormatted = "0s",
            blockedCount = -5,
            walkAwayCount = -1,
            hasUsagePermission = true
        )

        assertEquals(0, result.blocked)
        assertEquals(0, result.walkedAway)
    }

    @Test
    fun `Today EMPTY has no usage permission and zero counts`() {
        val empty = WidgetSnapshot.Today.EMPTY

        assertFalse(empty.hasUsagePermission)
        assertEquals(0, empty.blocked)
        assertEquals(0, empty.walkedAway)
        assertEquals(WidgetSnapshot.NO_VALUE, empty.screenTime)
    }

    // ------------------------------------------------------------------------ topBlocked

    @Test
    fun `topBlocked preserves the incoming order it was handed`() {
        // Trimming is NOT this function's job: the list arrives already ranked and truncated by
        // InsightsCalculator.topBlockedApps, and the widget trims again per size variant when it
        // lays the rows out. A third limit here was dead - it never received a list it could
        // shorten - so what is left to pin is that the order it is given is the order it keeps.
        val stats = listOf(
            stat("app.a", total = 10),
            stat("app.b", total = 8),
            stat("app.c", total = 5)
        )

        val result = WidgetSnapshotMapper.topBlocked(stats, labels = emptyMap())

        assertEquals(listOf("app.a", "app.b", "app.c"), result.apps.map { it.packageName })
    }

    @Test
    fun `topBlocked yields EMPTY for an empty stats list`() {
        assertEquals(
            WidgetSnapshot.TopBlocked.EMPTY,
            WidgetSnapshotMapper.topBlocked(emptyList(), emptyMap())
        )
    }

    @Test
    fun `topBlocked gives the top row 100 percent and a half-count row 50 percent`() {
        val stats = listOf(
            stat("app.top", total = 100),
            stat("app.half", total = 50)
        )

        val result = WidgetSnapshotMapper.topBlocked(stats, emptyMap())

        assertEquals(100, result.apps.first { it.packageName == "app.top" }.barPercent)
        assertEquals(50, result.apps.first { it.packageName == "app.half" }.barPercent)
    }

    @Test
    fun `topBlocked barPercent is always within 0 to 100 even for an unsorted list`() {
        // Deliberately out of order: the smaller row comes first. barPercent is computed relative
        // to this visible list's own maximum (maxOf, not first()), so a later, larger row must not
        // be able to exceed the 0..100 track its bar is drawn against.
        val stats = listOf(
            stat("app.small", total = 10),
            stat("app.big", total = 100)
        )

        val result = WidgetSnapshotMapper.topBlocked(stats, emptyMap())

        result.apps.forEach { app ->
            assertTrue("barPercent for ${app.packageName} was ${app.barPercent}", app.barPercent in 0..100)
        }
        assertEquals(100, result.apps.first { it.packageName == "app.big" }.barPercent)
    }

    @Test
    fun `topBlocked falls back to the raw package name when it is absent from labels`() {
        val stats = listOf(stat("com.uninstalled.app", total = 4))

        val result = WidgetSnapshotMapper.topBlocked(stats, labels = emptyMap())

        assertEquals("com.uninstalled.app", result.apps.single().label)
    }

    @Test
    fun `topBlocked falls back to the raw package name when the resolved label is blank`() {
        val stats = listOf(stat("com.blank.label", total = 4))
        val labels = mapOf("com.blank.label" to "   ")

        val result = WidgetSnapshotMapper.topBlocked(stats, labels)

        assertEquals("com.blank.label", result.apps.single().label)
    }

    @Test
    fun `topBlocked uses a resolved label verbatim when present`() {
        val stats = listOf(stat("com.instagram.android", total = 4))
        val labels = mapOf("com.instagram.android" to "Instagram")

        val result = WidgetSnapshotMapper.topBlocked(stats, labels)

        assertEquals("Instagram", result.apps.single().label)
    }

    // ------------------------------------------------------------------------ protection

    @Test
    fun `protection walks all eight boolean combinations for state and togglesInWidget`() {
        data class Case(
            val enabled: Boolean,
            val degraded: Boolean,
            val strict: Boolean,
            val expectedState: ProtectionState,
            val expectedToggles: Boolean
        )

        val cases = listOf(
            Case(enabled = false, degraded = false, strict = false, expectedState = ProtectionState.OFF, expectedToggles = true),
            Case(enabled = false, degraded = false, strict = true, expectedState = ProtectionState.OFF, expectedToggles = true),
            // Degraded is suppressed whenever protection is switched off.
            Case(enabled = false, degraded = true, strict = false, expectedState = ProtectionState.OFF, expectedToggles = true),
            Case(enabled = false, degraded = true, strict = true, expectedState = ProtectionState.OFF, expectedToggles = true),
            Case(enabled = true, degraded = false, strict = false, expectedState = ProtectionState.ON, expectedToggles = true),
            // Strict Mode gates turning protection OFF: enabled + strict must refuse the widget toggle.
            Case(enabled = true, degraded = false, strict = true, expectedState = ProtectionState.ON, expectedToggles = false),
            Case(enabled = true, degraded = true, strict = false, expectedState = ProtectionState.DEGRADED, expectedToggles = true),
            Case(enabled = true, degraded = true, strict = true, expectedState = ProtectionState.DEGRADED, expectedToggles = false)
        )

        cases.forEach { case ->
            val result = WidgetSnapshotMapper.protection(
                enabled = case.enabled,
                degraded = case.degraded,
                strictModeEnabled = case.strict
            )

            assertEquals(
                "state for enabled=${case.enabled} degraded=${case.degraded} strict=${case.strict}",
                case.expectedState,
                result.state
            )
            assertEquals(
                "togglesInWidget for enabled=${case.enabled} degraded=${case.degraded} strict=${case.strict}",
                case.expectedToggles,
                result.togglesInWidget
            )
        }
    }

    @Test
    fun `togglesInWidget is false exactly when enabled and strictModeEnabled are both true`() {
        // Strict Mode must gate turning protection OFF: a widget that wrote the pref directly
        // would be a one-tap bypass of the commitment lock the app's typed challenge exists to
        // enforce. So the ONLY combination that refuses the toggle is enabled && strictModeEnabled.
        for (enabled in listOf(true, false)) {
            for (degraded in listOf(true, false)) {
                for (strict in listOf(true, false)) {
                    val result = WidgetSnapshotMapper.protection(enabled, degraded, strict)
                    val expectedRefusal = enabled && strict
                    assertEquals(
                        "enabled=$enabled degraded=$degraded strict=$strict",
                        !expectedRefusal,
                        result.togglesInWidget
                    )
                }
            }
        }
    }

    @Test
    fun `degraded is suppressed when protection is off so state reads OFF not DEGRADED`() {
        val result = WidgetSnapshotMapper.protection(enabled = false, degraded = true, strictModeEnabled = false)

        // A switched-off Nudge is not a broken Nudge.
        assertEquals(ProtectionState.OFF, result.state)
        assertFalse(result.degraded)
    }

    @Test
    fun `Protection EMPTY does not offer an in-widget toggle because it fails toward locked`() {
        val empty = WidgetSnapshot.Protection.EMPTY

        assertTrue(empty.enabled)
        assertFalse(empty.degraded)
        assertTrue(empty.strictModeEnabled)
        assertEquals(ProtectionState.ON, empty.state)
        assertFalse("a failed read must never render an inviting one-tap OFF affordance", empty.togglesInWidget)
    }

    // ------------------------------------------------------------------------ fixtures

    private fun stat(packageName: String, total: Int, byMode: Map<String, Int> = emptyMap()) =
        AppInterventionStat(packageName = packageName, total = total, byMode = byMode)
}
