package com.astraedus.nudge.domain.autokick

import com.astraedus.nudge.domain.autokick.UseReminderEvaluator.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UseReminderEvaluatorTest {

    private fun minutes(n: Long): Long = n * 60_000L

    private fun eval(
        remind: Int?,
        kick: Int? = null,
        baseline: Long? = 0L,
        usage: Long,
        reminded: Boolean = false
    ) = UseReminderEvaluator.evaluate(remind, kick, baseline, usage, reminded)

    // ── off ──

    @Test
    fun `null reminder is off`() {
        assertEquals(Decision.OFF, eval(remind = null, usage = minutes(999)))
    }

    @Test
    fun `zero and negative reminders are off, never remind-immediately`() {
        assertEquals(Decision.OFF, eval(remind = 0, usage = minutes(999)))
        assertEquals(Decision.OFF, eval(remind = -5, usage = minutes(999)))
    }

    // ── kick precedence ──

    @Test
    fun `a kick at the same minute supersedes the reminder`() {
        assertEquals(Decision.SUPERSEDED_BY_KICK, eval(remind = 20, kick = 20, usage = minutes(25)))
    }

    @Test
    fun `a kick before the reminder supersedes it`() {
        assertEquals(Decision.SUPERSEDED_BY_KICK, eval(remind = 30, kick = 20, usage = minutes(31)))
    }

    @Test
    fun `a kick after the reminder leaves it live`() {
        assertEquals(Decision.REMIND, eval(remind = 10, kick = 20, usage = minutes(10)))
    }

    @Test
    fun `a disabled kick never suppresses a reminder`() {
        assertEquals(Decision.REMIND, eval(remind = 20, kick = 0, usage = minutes(20)))
        assertEquals(Decision.REMIND, eval(remind = 20, kick = -1, usage = minutes(20)))
        assertEquals(Decision.REMIND, eval(remind = 20, kick = null, usage = minutes(20)))
    }

    @Test
    fun `effectiveMinutes is the one precedence predicate`() {
        assertNull(UseReminderEvaluator.effectiveMinutes(null, 20))
        assertNull(UseReminderEvaluator.effectiveMinutes(0, null))
        assertNull(UseReminderEvaluator.effectiveMinutes(20, 20))
        assertNull(UseReminderEvaluator.effectiveMinutes(21, 20))
        assertEquals(19, UseReminderEvaluator.effectiveMinutes(19, 20))
        assertEquals(20, UseReminderEvaluator.effectiveMinutes(20, null))
        assertEquals(20, UseReminderEvaluator.effectiveMinutes(20, 0))
    }

    @Test
    fun `evaluate agrees with effectiveMinutes over the whole grid`() {
        // The class, not the instance: for every reminder/kick pair, evaluate supersedes exactly
        // when effectiveMinutes says there is no live reminder.
        for (remind in 1..40) {
            for (kick in listOf<Int?>(null, 0) + (1..40).toList()) {
                val superseded = eval(remind = remind, kick = kick, usage = 0L) == Decision.SUPERSEDED_BY_KICK
                val live = UseReminderEvaluator.effectiveMinutes(remind, kick) != null
                assertEquals("remind=$remind kick=$kick", !live, superseded)
            }
        }
    }

    // ── baseline ──

    @Test
    fun `no baseline starts the session`() {
        assertEquals(Decision.START_SESSION, eval(remind = 20, baseline = null, usage = minutes(300)))
    }

    @Test
    fun `a backwards reading re-baselines`() {
        assertEquals(Decision.REBASELINE, eval(remind = 20, baseline = minutes(300), usage = minutes(1)))
    }

    // ── once at N ──

    @Test
    fun `waits until N`() {
        assertEquals(Decision.WAIT, eval(remind = 20, baseline = minutes(100), usage = minutes(119)))
        assertEquals(Decision.WAIT, eval(remind = 20, baseline = minutes(100), usage = minutes(120) - 1))
    }

    @Test
    fun `reminds at exactly N and after`() {
        assertEquals(Decision.REMIND, eval(remind = 20, baseline = minutes(100), usage = minutes(120)))
        assertEquals(Decision.REMIND, eval(remind = 20, baseline = minutes(100), usage = minutes(500)))
    }

    @Test
    fun `an already-reminded session stays quiet`() {
        assertEquals(
            Decision.ALREADY_REMINDED,
            eval(remind = 20, baseline = minutes(100), usage = minutes(500), reminded = true)
        )
    }

    @Test
    fun `an already-reminded session still repairs its baseline`() {
        // A midnight rollover inside a reminded session re-baselines; it does not re-arm.
        assertEquals(
            Decision.REBASELINE,
            eval(remind = 20, baseline = minutes(300), usage = minutes(1), reminded = true)
        )
    }
}
