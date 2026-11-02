package dev.astraedus.qadriver

import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName

/**
 * The bench baseline every ported case starts from, put in place WITHOUT restarting Nudge.
 *
 * The bash harness reached a clean state with `pm clear`, which kills the process, drops the
 * accessibility binding and the debug-logging switch, and then needed a re-grant plus an
 * onboarding walk. Here the qa control receiver does it in-process: `reset` (no rules, no history,
 * logging on), then the shared fixture through the app's own import path. Grants are the runner's
 * job (`scripts/qa-driver.sh`); this only CHECKS them, and fails as a harness problem, never as an
 * app finding, when they are missing.
 *
 * Unlocking is the runner's job too: the bench PIN stays out of this public repo, so each case is
 * its own `am instrument` and the runner unlocks before every one.
 */
abstract class NudgeCase {

    @get:Rule
    val testName = TestName()

    protected val device get() = QaDevice.device

    protected val youtube = "com.google.android.youtube"

    /** BlockEngine's line for the fixture's YouTube rule: proves WHICH rule fired, not just that something appeared. */
    protected val delayDecision = "block package=$youtube reason=delay_rule delaySeconds=5"

    private var notes = 0

    /**
     * Logged, AND added to the instrumentation's final result bundle, which `am instrument -r`
     * prints as `INSTRUMENTATION_RESULT: qa.note.NN=...` into the run's `<case>.instrument.txt`.
     * Logcat alone loses them: every `Logcat.await` clears the buffer before it waits.
     */
    protected fun note(message: String) {
        Log.i("QaDriver", "${testName.methodName}: $message")
        notes++
        InstrumentationRegistry.getInstrumentation()
            .addResults(Bundle().apply { putString("qa.note.%02d".format(notes), message) })
    }

    /** `<Class>-<nn>-<what>`, so a pulled run directory sorts into case order. */
    protected fun shot(name: String) = QaDevice.screenshot("${javaClass.simpleName}-$name")

    @Before
    fun baseline() {
        check(QaDevice.dontSuppress) { "the ported cases never run with qa.suppress (that is the phase 0 control only)" }
        // Forces the flagged UiAutomation connection BEFORE anything else can open an unflagged one.
        device.waitForIdle()
        assertTrue(
            "HARNESS: Nudge's accessibility service is not bound before the case started; " +
                "run through scripts/qa-driver.sh, which applies the grants (not an app finding)",
            A11yState.awaitBound(true, 10_000)
        )
        QaControl.reset()
        val seeded = QaControl.seedFixture()
        note("seeded $seeded")
        val state = QaControl.state()
        assertEquals("rules after seeding the fixture: $state", 2, state.getInt("rules"))
        assertTrue("the app's side of the binding is down: $state", state.getBoolean("a11yConnected"))
        assertTrue("debug logging is off, so the logcat oracle is blind: $state", state.getBoolean("debugLogging"))
    }

    @After
    fun evidence() {
        shot("zz-end")
        note("end state ${runCatching { QaControl.state() }.getOrElse { "unreadable: $it" }}")
    }
}
