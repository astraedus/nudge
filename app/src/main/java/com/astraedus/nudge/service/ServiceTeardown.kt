package com.astraedus.nudge.service

/**
 * Runs a service's teardown as a sequence of independently-failing steps
 * ([#57](https://github.com/astraedus/nudge/issues/57)).
 *
 * ## Why this exists rather than a plain sequence of calls
 *
 * A throw out of [android.app.Service.onDestroy] is not an ordinary crash. Android wraps it as
 * *"Unable to stop service"* and records the component under `Crashed services:` in
 * `dumpsys accessibility` — and a crashed accessibility service is never rebound, so **every rule
 * silently becomes a no-op with nothing shown to the user**. The bench Pixel produced four of these
 * traces across 1.18.0 and 1.18.1: `stopForegroundTimeTicker` read a `lateinit` that
 * `onServiceConnected` had not assigned yet, because the service was created and destroyed without
 * a completed connect (install-over, force stop, memory-pressure kill and rebind, or the user
 * toggling the permission quickly).
 *
 * Guarding that one field fixes that one trace. This fixes the CLASS: teardown is the last thing
 * the process does, nothing after it can observe a failure, and the only thing a throw can buy is
 * the two outcomes we never want — the crashed-service state above, and the steps *after* the
 * throw being skipped. So every step runs, each one's failure is contained and reported, and the
 * step order stays a sequence of ordinary statements at the call site.
 *
 * [Throwable], not [Exception], deliberately: `NoSuchMethodError` from an API-level mistake (a bug
 * class this repo has shipped before, see `docs/TESTING.md`) is an `Error`, and it would leave the
 * service just as crashed. The process is going away regardless, so there is nothing left to
 * protect by letting one propagate.
 *
 * @param onStepFailure notified with the failing step's name. Its own failure is swallowed too —
 *   a logger that throws during teardown must not become the thing that crashes the service.
 */
internal class ServiceTeardown(
    private val onStepFailure: (step: String, error: Throwable) -> Unit
) {

    /** Runs [body], containing and reporting any failure. Named so a report says which step died. */
    fun step(name: String, body: () -> Unit) {
        try {
            body()
        } catch (error: Throwable) {
            try {
                onStepFailure(name, error)
            } catch (_: Throwable) {
                // Nothing left to report to, and reporting is not worth the crash it would cause.
            }
        }
    }
}
