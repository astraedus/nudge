package com.astraedus.nudge.service

import com.astraedus.nudge.testing.SourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #119: no floating-window decision in the service may be made from ONE window list.
 *
 * The service is not JVM-testable, so this checks for the ABSENCE of the defect class (rule (e) in
 * docs/testing-strategy.md): any `isFloating(` / `coverFor(` / `floatingBoundsOf(` call in the
 * service is a decision that skipped [com.astraedus.nudge.domain.windows.FloatingWindowSettle].
 * `FloatingWindowCover.maintain` is deliberately not on the list: it moves and removes a cover that
 * is already up, it never puts one up or presses BACK.
 */
class FloatingSettleContractTest {

    private val source: String by lazy {
        SourceScan.kotlinFile("com/astraedus/nudge/service/NudgeAccessibilityService.kt").readText()
    }

    @Test
    fun `the service never asks a single window list whether a window is floating`() {
        assertTrue("source slice is the real service", source.contains("class NudgeAccessibilityService"))
        for (singleSnapshotCall in listOf(".isFloating(", "coverFor(", ".floatingBoundsOf(")) {
            assertFalse(
                "$singleSnapshotCall in the service bypasses the #119 settle; ask floatingVerdictFor()",
                source.contains(singleSnapshotCall)
            )
        }
    }
}
