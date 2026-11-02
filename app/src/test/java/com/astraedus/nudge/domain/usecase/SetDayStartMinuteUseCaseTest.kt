package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.RuleSnapshot
import com.astraedus.nudge.data.repository.RuleSnapshots
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one entry point the first-unlock editors use to change the logical day start. It owns the
 * "does this need the unlock challenge?" decision, so these tests pin both halves: the question
 * asked before saving, and the clamped write behind it.
 */
class SetDayStartMinuteUseCaseTest {

    /** Only `enabledRules` matters here; `current` just has to be answerable. */
    private class FakeSnapshots(private val enabled: List<BlockRule>) : RuleSnapshots {
        var enabledRulesReads = 0

        override suspend fun current(): RuleSnapshot =
            RuleSnapshot.of(enabledRules = enabled, groups = emptyList(), members = emptyList())

        override suspend fun enabledRules(): List<BlockRule> {
            enabledRulesReads++
            return enabled
        }
    }

    private fun prefs(currentMinute: Int, strictModeEnabled: Boolean = false): NudgePreferences =
        mockk {
            every { dayStartMinute } returns flowOf(currentMinute)
            every { isStrictModeEnabled } returns flowOf(strictModeEnabled)
            coEvery { setDayStartMinute(any()) } just Runs
        }

    private fun rule(firstUnlockMinutes: Int? = null): BlockRule = BlockRule(
        packageName = "com.example",
        mode = "DELAY",
        firstUnlockMinutes = firstUnlockMinutes,
        // A first-unlock rule ignores these, but a days-and-times rule needs both to be one.
        scheduleStartMinute = if (firstUnlockMinutes == null) 540 else null,
        scheduleEndMinute = if (firstUnlockMinutes == null) 1020 else null
    )

    @Test
    fun `an unchanged minute never asks anything`() = runTest {
        // No stubs for isStrictModeEnabled: asking it here would throw, which is the point.
        val preferences = mockk<NudgePreferences> { every { dayStartMinute } returns flowOf(240) }
        val snapshots = FakeSnapshots(listOf(rule(firstUnlockMinutes = 30)))

        val requires = SetDayStartMinuteUseCase(preferences, snapshots).requiresUnlock(240)

        assertFalse(requires)
        assertEquals("unchanged value must not even read the rules", 0, snapshots.enabledRulesReads)
    }

    @Test
    fun `a changed minute with an enabled first-unlock rule requires the unlock`() = runTest {
        val useCase = SetDayStartMinuteUseCase(
            prefs(currentMinute = 240, strictModeEnabled = true),
            FakeSnapshots(listOf(rule(firstUnlockMinutes = 30)))
        )

        assertTrue(useCase.requiresUnlock(300))
    }

    @Test
    fun `a changed minute with only days-and-times rules does not require the unlock`() = runTest {
        val useCase = SetDayStartMinuteUseCase(
            prefs(currentMinute = 240, strictModeEnabled = true),
            FakeSnapshots(listOf(rule()))
        )

        assertFalse(useCase.requiresUnlock(300))
    }

    @Test
    fun `execute clamps out of range values and writes the minute`() = runTest {
        val preferences = prefs(currentMinute = 240)
        val useCase = SetDayStartMinuteUseCase(preferences, FakeSnapshots(emptyList()))

        useCase.execute(5_000)
        useCase.execute(-5)
        useCase.execute(300)

        coVerify(exactly = 1) { preferences.setDayStartMinute(1439) }
        coVerify(exactly = 1) { preferences.setDayStartMinute(0) }
        coVerify(exactly = 1) { preferences.setDayStartMinute(300) }
    }
}
