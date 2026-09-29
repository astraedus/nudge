package com.astraedus.nudge

import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * The instrument that found [#53](https://github.com/astraedus/nudge/issues/53) twice. **Off unless
 * you ask for it:**
 *
 * ```
 * ./gradlew test -Dnudge.leakprobe=1
 * ```
 *
 * ## What it is for
 *
 * When a test dies of `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`, the test named in the
 * report is the VICTIM: some earlier code in the same JVM fork let a coroutine exception reach
 * `handleCoroutineException` with no handler in its context, `ExceptionCollector` queued it because
 * no `runTest` was active to take it, and the next `runTest` anywhere in the fork threw it at its
 * first line. Which test that is depends only on Gradle's class order, so the victim can never tell
 * you the offender.
 *
 * This is registered as a second process-global `CoroutineExceptionHandler` through
 * `app/src/test/resources/META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler`, so
 * kotlinx hands it every such exception AT THE MOMENT IT HAPPENS, with its stack. kotlinx's own
 * `ExceptionCollector` does not swallow the exception when no `runTest` is active — it adds it to a
 * static list and returns — so iteration continues and this handler sees exactly the leaks that
 * matter.
 *
 * Output goes to **stderr**, and `app/build.gradle.kts` configures the test tasks with
 * `TestLogEvent.STANDARD_ERROR`, so Gradle prints it under the name of the test that was RUNNING at
 * the time — i.e. the offender. That attribution is what named `HomeBlockedTileTest` in one CI run
 * out of twelve after two local investigations had found nothing.
 *
 * ## Why it stays in the tree rather than being deleted
 *
 * Two of these leaks have shipped, ~4% of CI runs reproduce the second one, and neither reproduced
 * on the dev machine at all: a class-boundary hunter that drove all 158 test classes through
 * `JUnitCore` and drained between them found ZERO leakers even with a 40-second settle. When it
 * happens again, the difference between a two-hour hunt and one read of the CI log is this file
 * being here already. Leaving it disabled by default costs one `ServiceLoader` class load.
 */
class LeakProbeHandler : CoroutineExceptionHandler {

    override val key: CoroutineContext.Key<*> get() = CoroutineExceptionHandler

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        if (!enabled) return
        System.err.print(
            buildString {
                appendLine("=== NUDGE-LEAK-PROBE hit #${hits.incrementAndGet()} ===")
                appendLine("thread: ${Thread.currentThread().name}")
                appendLine("context: $context")
                appendLine("exception: ${exception::class.java.name}: ${exception.message}")
                appendLine("--- exception stack (read the LAST 'Caused by' for the origin) ---")
                append(stackOf(exception))
                appendLine("--- nudge frames live in any thread (names the running test) ---")
                Thread.getAllStackTraces().forEach { (thread, frames) ->
                    frames.filter { it.className.startsWith(NUDGE_PACKAGE) }
                        .forEach { appendLine("  [${thread.name}] $it") }
                }
                appendLine("=== end NUDGE-LEAK-PROBE hit ===")
            }
        )
        System.err.flush()
    }

    private fun stackOf(t: Throwable): String = StringWriter()
        .also { t.printStackTrace(PrintWriter(it)) }
        .toString()

    private companion object {
        const val NUDGE_PACKAGE = "com.astraedus.nudge"
        val hits = AtomicInteger()

        /**
         * Read once. `app/build.gradle.kts` forwards `-Dnudge.leakprobe` from the Gradle
         * invocation into the test fork; without it this handler is a no-op return.
         */
        val enabled: Boolean = System.getProperty("nudge.leakprobe") != null
    }
}
